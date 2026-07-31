package dev.example.sablebridging;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

/**
 * Draws a wireframe outline box at the exact position a gap-fill block
 * would land, the same way vanilla outlines a block you're looking
 * directly at. Only fires for gap-fill targets (isGapFill()) — an
 * ordinary direct-look placement already has vanilla's own outline.
 *
 * SUB-LEVEL TRANSFORM, v2 — the first version (baking the sub-level's
 * ENTIRE pose, including its translation, into a single Matrix4f) shipped
 * broken: scattered, fragmented outline geometry in the wrong places,
 * confirmed by the user's own screenshots. Root cause: Sable stores
 * sub-level blocks at EXTREME plot-grid coordinates (established earlier
 * this session — potentially hundreds of thousands of units out).
 * Baking a translation of that magnitude into a single-precision
 * Matrix4f, then using it to transform small local coordinates, is a
 * textbook floating-point precision failure — float32 only has ~7 useful
 * decimal digits, nowhere near enough to represent both a
 * hundred-thousand-unit offset AND sub-block precision at the same time.
 *
 * Fixed by never combining "huge translation" with float precision at
 * all:
 *   1. Transform ONLY the box's center point through the pose, using
 *      Pose3dc.transformPosition(Vec3) — Minecraft's Vec3 is already
 *      double-precision, so this whole step stays precise regardless of
 *      how large the local coordinates are. This gives a small,
 *      ordinary-magnitude GLOBAL position.
 *   2. Translate there (camera-relative, still double-precision via
 *      PoseStack#translate(double,double,double)).
 *   3. THEN apply ONLY the sub-level's rotation (Quaternionf, converted
 *      from the pose's Quaterniondc) — a pure rotation has no large
 *      magnitude to lose precision over, so float32 is completely fine
 *      for this part.
 *   4. Draw the box CENTERED AT LOCAL ORIGIN (small, symmetric extents),
 *      not at its real local coordinates — since the center point and
 *      rotation are already correctly positioned/oriented by steps 1-3,
 *      the box's own shape only needs to be expressed relative to its
 *      own center.
 * This sidesteps the precision problem entirely: the only place huge
 * local coordinates ever get combined with a transform is inside
 * transformPosition, which does that math in double precision the whole
 * way through.
 *
 * Uses logicalPose() rather than the smoother renderPose(partialTick) —
 * that needs casting to ClientSubLevelAccess and extracting a float
 * partial-tick from DeltaTracker, neither of which were verified this
 * session. logicalPose() trades a small amount of visual smoothness
 * (potential slight per-tick jitter rather than perfectly smooth
 * interpolation) for not adding a second unverified API surface.
 *
 * Confidence note: Pose3dc.orientation() (returns Quaterniondc) was
 * re-verified directly against the real source this pass, not assumed
 * from memory. Quaternionf's Quaterniondc-copying constructor and
 * PoseStack#mulPose(Quaternionf) were both verified against docs before
 * writing. This is still the least-tested rendering path in the mod —
 * if the outline is still wrong on a sub-level after this, the box
 * should at minimum be a single coherent cube now rather than scattered
 * fragments; a still-wrong POSITION or ROTATION (as opposed to garbled
 * geometry) would point at a genuine logic error rather than a precision
 * one, which narrows down where to look next.
 *
 * Uses AFTER_PARTICLES per NeoForge's own guidance for translucent/alpha
 * effects (AFTER_TRANSLUCENT_BLOCKS "may not work properly with
 * translucency").
 */
public final class BridgingHighlightRenderer {

    private BridgingHighlightRenderer() {}

    // Requested polish fix: in tight 1-wide tunnels, a gap-fill target can
    // be near enough to the player's own eye that the outline box visually
    // renders "inside" the camera -- jarring. Below this distance, skip
    // drawing the outline entirely (placement and the crosshair icon are
    // both unaffected; this only suppresses the world-space box). Now a
    // real config value (BridgingConfig.MIN_RENDER_DISTANCE) instead of a
    // fixed constant.

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        if (!BridgingKeybinds.enabled) {
            return;
        }
        if (!BridgingConfig.SHOW_OUTLINE.get()) {
            // Client-side preference only -- placement and the crosshair
            // indicator are both untouched either way. Checked first since
            // it's the cheapest possible early-out, ahead of even reading
            // the target cache.
            return;
        }

        // Shared per-tick cache, not a fresh raycast every frame -- see
        // BridgingTargetCache's doc comment for why this matters (this
        // used to be a real, confirmed source of noticeable lag near
        // Sable sub-levels). A null cache already covers "no player" and
        // "not holding a block item," so neither needs checking again here.
        BridgingPlacement.Target target = BridgingTargetCache.get();
        if (target == null || !target.isGapFill() || target.hit().getType() != HitResult.Type.BLOCK) {
            return;
        }

        // Requested polish fix: suppress the outline whenever a normal
        // block exists anywhere along the player's reach, even though the
        // gap-fill candidate itself is still valid. Found via real
        // confusion: the outline promised a placement "in front" (the
        // bridged gap position), but the block sometimes landed on a
        // different, reachable block instead.
        //
        // FIXED VERSION, after a real proven bug in the original one:
        // this used to recompute its own raycast once per TICK from a
        // non-interpolated eye position, which turned out to disagree
        // with vanilla's own frame-accurate targeting for thin collision
        // shapes specifically (confirmed via Jade showing info for a
        // Create shaft that this mod's own check reported as a MISS).
        // Reading Minecraft's own already-computed hitResult instead
        // costs nothing extra (no redundant raycast at all) and is
        // guaranteed to match whatever vanilla's real crosshair -- and
        // Jade, which reads the same value -- actually shows.
        HitResult mcHit = Minecraft.getInstance().hitResult;
        if (mcHit != null && mcHit.getType() == HitResult.Type.BLOCK) {
            return;
        }

        // Use the shared per-tick cache here too, not an independent
        // per-frame lookup -- see BridgingTargetCache's doc comment for
        // why (found on this same review pass: this was the one place
        // still making a redundant per-frame Sable API call, the exact
        // category of issue that cache exists to eliminate elsewhere).
        SubLevelAccess subLevel = BridgingTargetCache.getSubLevel();

        // Use Target's own authoritative placementPos rather than deriving
        // it here -- applySlabAssist can construct hits using a different
        // BlockHitResult convention than the rest of this mod (see its doc
        // comment), so blockPos.relative(direction) is NOT reliably correct
        // for every hit. placementPos handles both conventions uniformly.
        // It's in LOCAL (plot-grid) space when on a sub-level.
        BlockPos placementPos = target.placementPos();
        AABB localBox = buildOutlineBox(placementPos, target.slabType());

        PoseStack poseStack = event.getPoseStack();
        if (poseStack == null) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();

        // Compute the box's actual GLOBAL render center once, up front --
        // used for both the too-close check below and, on a sub-level,
        // reused directly in the draw step rather than recomputed.
        Vec3 localCenter = new Vec3(
                (localBox.minX + localBox.maxX) / 2.0,
                (localBox.minY + localBox.maxY) / 2.0,
                (localBox.minZ + localBox.maxZ) / 2.0
        );
        Pose3dc pose = subLevel != null ? subLevel.logicalPose() : null;
        Vec3 renderCenter = pose != null ? pose.transformPosition(localCenter) : localCenter;

        if (camPos.distanceTo(renderCenter) < BridgingConfig.MIN_RENDER_DISTANCE.get()) {
            return;
        }

        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());

        poseStack.pushPose();

        if (pose != null) {
            // Step 1+2: translate to the already-computed global center
            // (camera-relative, still double-precision via
            // PoseStack#translate(double,double,double)).
            poseStack.translate(renderCenter.x - camPos.x, renderCenter.y - camPos.y, renderCenter.z - camPos.z);

            // Step 3: rotation only -- no large magnitude involved, so
            // float precision is completely fine here.
            poseStack.mulPose(new Quaternionf(pose.orientation()));

            // Step 4: draw the box centered at local origin, using its own
            // (small, symmetric) half-extents -- position and rotation are
            // already handled by the transform above.
            //
            // NOTE, found on review: this ignores pose.scale() entirely --
            // only position and rotation are applied. Fine in practice,
            // since Sable sub-levels are always uniform (1,1,1) scale as
            // far as anything in this session has seen, but if that's
            // ever not true, the outline's SIZE (not position/rotation)
            // would be the thing to check first.
            double halfX = (localBox.maxX - localBox.minX) / 2.0;
            double halfY = (localBox.maxY - localBox.minY) / 2.0;
            double halfZ = (localBox.maxZ - localBox.minZ) / 2.0;
            AABB centeredBox = new AABB(-halfX, -halfY, -halfZ, halfX, halfY, halfZ);

            LevelRenderer.renderLineBox(poseStack, consumer, centeredBox, 0.0f, 0.0f, 0.0f, 0.4f);
        } else {
            poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

            // Same color/alpha vanilla uses for its own block-selection
            // outline, so this reads as "a normal Minecraft outline"
            // rather than something visually foreign.
            LevelRenderer.renderLineBox(poseStack, consumer, localBox, 0.0f, 0.0f, 0.0f, 0.4f);
        }

        poseStack.popPose();
        bufferSource.endBatch(RenderType.lines());
    }

    /**
     * A full 1x1x1 box normally, or a half-height box when the placement
     * will actually be a slab (matching whichever half BridgingPlacement
     * determined). Doesn't attempt to match other non-full-block shapes
     * (stairs, etc.) -- those still get the full-block approximation.
     *
     * Always returns LOCAL block coordinates -- the caller is responsible
     * for transforming/positioning appropriately depending on whether the
     * player is on a sub-level.
     */
    private static AABB buildOutlineBox(BlockPos placementPos, @Nullable SlabType slabType) {
        if (slabType == null) {
            return new AABB(placementPos);
        }

        double x = placementPos.getX();
        double y = placementPos.getY();
        double z = placementPos.getZ();
        double midY = y + 0.5;

        return slabType == SlabType.BOTTOM
                ? new AABB(x, y, z, x + 1, midY, z + 1)
                : new AABB(x, midY, z, x + 1, y + 1, z + 1);
    }
}
