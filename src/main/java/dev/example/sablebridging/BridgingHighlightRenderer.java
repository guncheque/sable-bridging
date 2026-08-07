package dev.example.sablebridging;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
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
 * RESOLVED, found via real testing (visible jitter on a continuously
 * rotating platform) and confirmed against the actual sable-companion
 * source this pass: this used to use logicalPose() instead of
 * renderPose(partialTick), with a note that the latter needed
 * verification first (casting to ClientSubLevelAccess, an unverified
 * DeltaTracker call) and wasn't worth the extra unverified API surface
 * at the time. logicalPose() turned out to be Sable's raw TICK-RATE
 * physics state -- polling it more often doesn't smooth anything out,
 * since the value itself only updates 20 times a second regardless.
 * renderPose() (no-arg overload, confirmed to exist and to use the
 * current frame's partial-tick automatically) is Sable's own
 * already-interpolated pose meant specifically for this. See the
 * pose-selection code below for the switch and its defensive fallback.
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

        // Shared per-tick cache for the common case, not a fresh raycast
        // every frame -- see BridgingTargetCache's doc comment for why
        // this matters (this used to be a real, confirmed source of
        // noticeable lag near Sable sub-levels). getForRender() upgrades
        // to a fresh per-frame recompute specifically while on a
        // sub-level, to fix a real stutter found via testing on a
        // continuously-rotating platform -- see getForRender's own doc
        // comment for the full reasoning. A null player here (theoretically
        // possible during this event) just means no target either way.
        Player player = Minecraft.getInstance().player;
        BridgingPlacement.Target target = player != null ? BridgingTargetCache.getForRender(player) : null;
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
        Pose3dc pose;
        if (subLevel instanceof ClientSubLevelAccess clientAccess) {
            // VERIFIED FIX, real jitter root cause found via source
            // inspection: logicalPose() is Sable's raw TICK-RATE physics
            // state -- reading it more often (even every frame, as the
            // getForRender() change above now does) doesn't help at all,
            // since the underlying value itself only changes 20 times a
            // second regardless of polling frequency. renderPose() is
            // Sable's own already-interpolated pose specifically meant
            // for rendering (confirms and replaces an earlier session's
            // unverified note about this same method -- it exists exactly
            // as anticipated, confirmed against the real sable-companion
            // source this pass). No-arg overload uses the current frame's
            // partial-tick automatically, sidestepping any need to fetch
            // DeltaTracker ourselves.
            pose = clientAccess.renderPose();
        } else if (subLevel != null) {
            // Defensive fallback only -- in practice every SubLevelAccess
            // reaching this CLIENT-ONLY rendering code should already be a
            // ClientSubLevelAccess. Falls back to the old tick-rate
            // behavior rather than crashing if that assumption is ever
            // wrong.
            pose = subLevel.logicalPose();
        } else {
            pose = null;
        }
        Vec3 renderCenter = pose != null ? pose.transformPosition(localCenter) : localCenter;

        if (camPos.distanceTo(renderCenter) < BridgingConfig.MIN_RENDER_DISTANCE.get()) {
            return;
        }

        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());

        float[] rgb = parseOutlineColor();
        float alpha = (float) BridgingConfig.OUTLINE_OPACITY.get().doubleValue();

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

            LevelRenderer.renderLineBox(poseStack, consumer, centeredBox, rgb[0], rgb[1], rgb[2], alpha);
        } else {
            poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

            // Same color/alpha vanilla uses for its own block-selection
            // outline BY DEFAULT, so this reads as "a normal Minecraft
            // outline" rather than something visually foreign -- but now
            // configurable via OUTLINE_COLOR/OUTLINE_OPACITY if a player
            // wants something more subtle or a colorblind-friendly choice.
            LevelRenderer.renderLineBox(poseStack, consumer, localBox, rgb[0], rgb[1], rgb[2], alpha);
        }

        poseStack.popPose();
        bufferSource.endBatch(RenderType.lines());
    }

    /**
     * @return {r, g, b} as floats in [0,1], parsed from OUTLINE_COLOR's
     *         hex string. Falls back to black (matching the previous
     *         hardcoded default) on ANY parse failure -- a malformed
     *         config value (typo, missing '#', wrong length) should
     *         degrade gracefully to the old default, never crash
     *         rendering. Re-parses on every call rather than caching,
     *         since config values can change live via the in-game config
     *         screen and this is cheap (a few character comparisons) next
     *         to the actual line-box draw call it feeds into.
     */
    private static float[] parseOutlineColor() {
        String hex = BridgingConfig.OUTLINE_COLOR.get();
        try {
            String cleaned = hex.startsWith("#") ? hex.substring(1) : hex;
            if (cleaned.length() != 6) {
                throw new NumberFormatException("expected 6 hex digits, got: " + cleaned);
            }
            int r = Integer.parseInt(cleaned.substring(0, 2), 16);
            int g = Integer.parseInt(cleaned.substring(2, 4), 16);
            int b = Integer.parseInt(cleaned.substring(4, 6), 16);
            return new float[]{r / 255.0f, g / 255.0f, b / 255.0f};
        } catch (Exception e) {
            return new float[]{0.0f, 0.0f, 0.0f};
        }
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
