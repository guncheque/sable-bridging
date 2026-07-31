package dev.example.sablebridging;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The reach-around raycast + gap-fill search, with optional Sable awareness.
 *
 * This mod works as a plain reach-around bridging mod on its own -- if
 * Sable isn't installed, getPlayerSubLevel() always returns null (that's
 * sable-companion's safe default behavior) and raycastForBridging() falls
 * straight through to an ordinary global-space search, same as any other
 * bridging mod.
 *
 * When Sable IS installed and the player happens to be riding a moving
 * sub-level, the same call picks that up automatically and transforms the
 * raycast into the sub-level's local space first:
 *   1. Figure out if the player is riding a moving sub-level.
 *   2. If so, transform the ray (eye position + look vector) from global
 *      space into the sub-level's local space.
 *   3. Run the ordinary Minecraft raycast + gap-fill search in whichever
 *      space applies (local if on a sub-level, global otherwise).
 *
 * IMPORTANT, and easy to get backwards: the BlockHitResult this returns is
 * used AS-IS for placement, with NO transform back to global space. Sable
 * sub-levels store their blocks in the SAME Level object as everything
 * else, just at extreme plot-grid coordinates (confirmed from
 * sable-companion's README, which threads a single `level` reference
 * through every position-lookup example) -- so a BlockPos found via the
 * local-space raycast IS the real position to write to in that Level.
 * The pose transform IS still needed for anything shown to the PLAYER
 * visually (a world-space outline box), since rendering happens in the
 * visually-transformed global frame — but BridgingHighlightRenderer does
 * NOT yet apply it (see that class's own doc comment for the honest
 * current state: it guards against drawing in the wrong place on a
 * sub-level by not drawing at all there yet, rather than drawing
 * somewhere wrong).
 *
 * The gap-fill search itself (finding a placeable position when the
 * player isn't looking directly at a block) uses an exact voxel traversal
 * AND a view-direction-aware face-priority ranking, both adapted from the
 * popular Bridging Mod, used under its MIT license — see GapFillVoxelPath's
 * own doc comment, computeValidAssistSides's doc comment, and
 * THIRD_PARTY_NOTICES.md at the project root for the full attribution.
 *
 * Two other companion utilities confirmed in the README that may simplify
 * this later: SableCompanion.INSTANCE.projectOutOfSubLevel(level, pos) is a
 * one-call shortcut for "transform this position to global space if it's in
 * a sub-level, otherwise leave it alone" -- useful for the future rendering
 * step above. distanceSquaredWithSubLevels(level, a, b) is needed for any
 * reach-distance check done OUTSIDE this class, since a plain
 * Vec3#distanceToSqr is wrong when either point is inside a sub-level's
 * plot (plot-local coordinates use extreme values) -- not needed inside
 * raycastForBridging itself, since the reach limit here is enforced by
 * capping the ray length before the transform, in whichever space applies.
 */
public final class BridgingPlacement {

    private BridgingPlacement() {}

    /**
     * @param hit           the placement target — BLOCK-type if either a normal
     *                      direct-look target or a valid gap-fill position was
     *                      found, MISS-type otherwise.
     * @param isGapFill     true if `hit` came from the gap-fill search (the
     *                      player wasn't looking directly at a block), false if
     *                      it's an ordinary ray hit vanilla would've found
     *                      anyway. Lets callers like the crosshair renderer show
     *                      a reach-around indicator only when it's actually
     *                      adding something over vanilla's own crosshair.
     * @param placementPos  the ACTUAL block position a placement will land at.
     *                      Computed the same way vanilla's own BlockPlaceContext
     *                      does (checking whether hit.getBlockPos() is itself
     *                      replaceable), rather than assuming any one fixed
     *                      convention -- necessary because applySlabAssist
     *                      constructs its BlockHitResults differently (air
     *                      target directly) than the rest of this class does
     *                      (solid neighbor + outward face). Consumers that need
     *                      to know WHERE a block will land (e.g. the outline
     *                      renderer) should use this instead of trying to
     *                      derive it from hit themselves. When holding a slab,
     *                      this uses vanilla's own CONTEXT-AWARE replaceability
     *                      check rather than the plain one, since that's the
     *                      only way to correctly predict a double-slab combine
     *                      against an existing half-slab -- see the doc comment
     *                      at the gap-fill branch of findPlacementTarget.
     * @param indicatorFace the face to use for crosshair-icon selection —
     *                      always the true geometric placement direction, even
     *                      when applySlabAssist has overridden hit's own
     *                      direction to steer vanilla's slab logic. Consumers
     *                      picking a UI icon should use this, not hit.getDirection().
     * @param slabType      BOTTOM or TOP if this placement will result in a
     *                      standalone half-slab, null if it's not a slab
     *                      placement at all, OR if it IS a slab placement but
     *                      one that will combine into a full double slab
     *                      against an existing half-slab -- in that case the
     *                      outline should be a full box, same as any other
     *                      full-block placement, which is exactly what null
     *                      already means to the outline renderer.
     * @param hasDirectBlockInReach true if a normal vanilla raycast along
     *                      this exact line of sight finds SOME block within
     *                      full reach distance, regardless of whether it's
     *                      nearer or farther than the gap-fill candidate
     *                      itself. Requested after real user testing showed
     *                      the outline promising a "bridged" placement in
     *                      front, while the block actually landed on a
     *                      different, reachable block behind/near it instead
     *                      -- plausibly because vanilla's own separate
     *                      pre-click targeting check (which gates whether
     *                      RightClickItem even fires at all) can disagree
     *                      with this mod's own doVanillaClip in ways not
     *                      independently verified here. Rather than guess at
     *                      the exact internal mechanism, this flag lets
     *                      consumers just play it safe: the outline renderer
     *                      uses it to suppress the box entirely whenever
     *                      ANY reachable block exists along the ray, even
     *                      though gap-fill PLACEMENT itself is untouched and
     *                      still works exactly as before either way.
     */
    public record Target(BlockHitResult hit, boolean isGapFill, BlockPos placementPos, Direction indicatorFace,
                          @Nullable SlabType slabType, boolean hasDirectBlockInReach) {}

    /**
     * @return the sub-level the player is currently riding, or null if the
     *         player is on static ground (this is also sable-companion's
     *         safe default return value when Sable isn't installed at all).
     */
    @Nullable
    public static SubLevelAccess getPlayerSubLevel(Player player) {
        // Entity-relationship query, not a spatial one — see the deep dive:
        // this is the better fit for "is the player on a moving contraption"
        // than inferring it from chunk position via getContaining().
        return SableCompanion.INSTANCE.getTrackingOrVehicleSubLevel(player);
    }

    /**
     * Runs a reach-around-aware placement search from the player's eye,
     * correctly accounting for a moving sub-level if the player is standing
     * on one.
     */
    public static Target raycastForBridging(Player player, double reachDistance) {
        Level level = player.level();
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getViewVector(1.0f);
        Vec3 endPos = eyePos.add(lookVec.scale(reachDistance));

        // Entity picking MUST use the real global ray, never the
        // sub-level-transformed one: unlike Sable's blocks (stored at
        // fixed local plot-grid coordinates), entities riding a moving
        // contraption have real, continuously-updated GLOBAL positions
        // each tick -- Sable's physics simulation needs that for normal
        // world collision/interaction. player.getBoundingBox() is also
        // always global. Using the transformed local ray here would
        // silently compare local and global coordinates against each
        // other on a sub-level -- caught on review before this shipped,
        // not from a live bug report, but the same category of mistake
        // as an earlier real one this session (the original slab-half
        // heuristic comparing global eye height against local block
        // coordinates).
        //
        // The result is bridged into findPlacementTarget as a FRACTION
        // along the ray (0..1) rather than a raw coordinate, specifically
        // so it stays meaningful regardless of which space
        // findPlacementTarget ends up operating in -- a fraction along
        // "the same conceptual ray" is valid in either space, where a raw
        // coordinate from one space plugged into the other wouldn't be.
        double entityBoundFraction = computeEntityBoundFraction(level, eyePos, endPos, player);

        SubLevelAccess subLevel = getPlayerSubLevel(player);

        if (subLevel == null) {
            // Static ground: ordinary global-space search.
            return findPlacementTarget(level, eyePos, endPos, player, entityBoundFraction);
        }

        // On a moving contraption: transform the ray into the sub-level's
        // local (plot-grid) space before running the search there — see the
        // class doc above for why the result needs no transform back out.
        Pose3dc pose = subLevel.logicalPose();
        Vec3 localEye = pose.transformPositionInverse(eyePos);
        Vec3 localEnd = pose.transformPositionInverse(endPos);

        return findPlacementTarget(level, localEye, localEnd, player, entityBoundFraction);
    }

    /**
     * @return the fraction (0..1) along [eyePos, endPos] where the nearest
     *         entity the player could plausibly be aiming at sits, or 1.0
     *         if there's no such entity (no bound needed).
     */
    private static double computeEntityBoundFraction(Level level, Vec3 eyePos, Vec3 endPos, Player player) {
        EntityHitResult entityHit = doEntityClip(level, eyePos, endPos, player);
        if (entityHit == null) {
            return 1.0;
        }
        double totalDist = eyePos.distanceTo(endPos);
        if (totalDist < 1.0e-6) {
            return 1.0;
        }
        double entityDist = eyePos.distanceTo(entityHit.getLocation());
        return Mth.clamp(entityDist / totalDist, 0.0, 1.0);
    }

    /**
     * @return the nearest entity along the ray the player could plausibly
     *         be aiming at, or null if none. Deliberately excludes the
     *         player themselves (can't target your own hitbox) and
     *         spectators/non-pickable entities, matching vanilla's own
     *         entity-picking exclusions.
     */
    @Nullable
    private static EntityHitResult doEntityClip(Level level, Vec3 from, Vec3 to, Player player) {
        AABB searchBox = player.getBoundingBox().expandTowards(to.subtract(from)).inflate(1.0D);
        return ProjectileUtil.getEntityHitResult(
                level, player, from, to, searchBox,
                candidate -> !candidate.isSpectator() && candidate.isPickable()
        );
    }

    /**
     * Runs the normal block raycast first, then checks for a gap-fill
     * candidate ONLY within whatever distance that raycast reached (the
     * full reach distance if it missed entirely, or up to the hit point if
     * it found something).
     *
     * This distance-bounding matters: a naive "only try gap-fill on a full
     * MISS" check breaks the moment anything solid — a wall, a tree, distant
     * terrain — sits anywhere further along the same sightline within reach,
     * since the vanilla raycast happily travels straight through the gap's
     * empty air and hits that far object instead, "shadowing" the gap
     * entirely even though the player is clearly aiming at the near gap, not
     * the far object. Bounding the gap-fill search to whatever's nearer
     * means: a valid gap-fill spot before the far hit wins (matching what
     * the player's actually aiming at); a far hit with nothing valid before
     * it falls through to that hit, same as before.
     *
     * The same bounding now also applies to entityBoundFraction (see
     * raycastForBridging) — whichever of the block hit or the entity
     * fraction point is nearer wins as the actual search boundary. Found
     * via a real compat bug: Create packages riding a chain were being
     * silently interfered with, because nothing previously stopped the
     * gap-fill search from treating a position at or near an entity as
     * fair game the same way it does empty terrain.
     */

    // TEMPORARY DIAGNOSTIC, part of the debug-instrumentation branch only
    // -- never meant for main. Exposes the raw vanillaHit computed inside
    // findPlacementTarget (type/position/distance), which the permanent
    // Target record doesn't carry -- only the derived boolean does.
    // Testing a specific hypothesis: that the still-unresolved
    // hasDirectBlockInReach mismatch is a coordinate-space bug, where
    // getPlayerSubLevel() returning non-null transforms the ray into a
    // sub-level's LOCAL space even when the block actually being aimed at
    // is ordinary global-space terrain -- causing the raycast to search
    // entirely the wrong coordinate frame and miss a real, visible block.
    @Nullable
    static BlockHitResult debugLastVanillaHit = null;

    private static Target findPlacementTarget(Level level, Vec3 from, Vec3 to, Player player, double entityBoundFraction) {
        BlockHitResult vanillaHit = doVanillaClip(level, from, to, player);
        debugLastVanillaHit = vanillaHit; // TEMPORARY DIAGNOSTIC

        // Any block along the FULL reach ray, independent of the bounding
        // logic below (which only cares about the NEARER of a block/entity
        // hit as a search limit). Requested specifically to suppress the
        // outline whenever a reachable block exists anywhere on the sight
        // line -- see the Target record's own doc comment for why.
        boolean hasDirectBlockInReach = vanillaHit.getType() == HitResult.Type.BLOCK;

        // Bound the search to whichever of the block hit or the entity
        // fraction (computed in raycastForBridging, always in real global
        // space -- see that method's doc comment) is nearer. The entity
        // fraction is converted to an actual point on THIS ray (whichever
        // space from/to represents) here, rather than ever comparing raw
        // coordinates across spaces directly.
        //
        // FOUND VIA A REAL COMPAT BUG (Create packages riding a chain
        // getting silently picked up/interfered with): the same
        // "shadow the gap-fill search" logic already applied to blocks
        // was never applied to entities at all before this.
        Vec3 blockBoundTo = vanillaHit.getType() == HitResult.Type.BLOCK ? vanillaHit.getLocation() : to;
        Vec3 entityBoundTo = from.add(to.subtract(from).scale(entityBoundFraction));
        Vec3 searchTo = from.distanceToSqr(blockBoundTo) <= from.distanceToSqr(entityBoundTo) ? blockBoundTo : entityBoundTo;

        BlockHitResult gapFillHit = findGapFillTarget(level, from, searchTo);

        if (gapFillHit != null) {
            // Capture the true geometric face BEFORE slab-assist potentially
            // overrides it (to steer vanilla's own slab half-selection) --
            // this is what the crosshair icon should reflect, not whatever
            // applySlabAssist ends up setting hit.getDirection() to.
            Direction indicatorFace = gapFillHit.getDirection();

            // Only gap-fill hits need this — a normal direct-look vanilla
            // hit already carries real click-location Y-fraction data, so
            // vanilla's own SlabBlock placement logic already picks the
            // correct half on its own for that case.
            //
            // Passing from/to (not player.getEyePosition() directly)
            // matters: those are already in whatever space this search is
            // running in (local plot-grid space on a sub-level), matching
            // airPos's own space. Using the player's raw global eye
            // position here would silently compare global and local
            // coordinates against each other on a sub-level.
            SlabAssistResult slabResult = applySlabAssist(gapFillHit, player, from, to);
            BlockHitResult finalHit = slabResult.hit();

            // REAL BUG, found via user testing: the outline could show a
            // gap-fill target one block away from an existing half-slab,
            // while the actual placement landed AT that slab instead,
            // combining into a double slab -- because computePlacementPos
            // decides using the plain, context-independent canBeReplaced(),
            // but vanilla's real placement (via BlockPlaceContext) uses the
            // CONTEXT-AWARE overload, which is where SlabBlock's own
            // combine-or-not decision actually lives. The two checks agree
            // almost everywhere except exactly this case.
            //
            // Fixed by asking vanilla's own context-aware check directly,
            // rather than reimplementing its combine logic ourselves --
            // this is prediction only, not a new placement feature (actual
            // combining already works today; it's vanilla's own code doing
            // it, same as it always has). Deliberately scoped to only the
            // slab-holding case, to avoid changing placementPos behavior
            // for every other item type based on an unverified assumption
            // that the context-aware check is safe everywhere.
            InteractionHand slabHand = isSlabItem(player.getMainHandItem()) ? InteractionHand.MAIN_HAND
                    : isSlabItem(player.getOffhandItem()) ? InteractionHand.OFF_HAND
                    : null;

            BlockPos placementPos;
            SlabType outlineSlabType = slabResult.slabType();

            if (slabHand != null) {
                BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(player, slabHand, finalHit));
                boolean replaceable = level.getBlockState(finalHit.getBlockPos()).canBeReplaced(context);
                placementPos = replaceable
                        ? finalHit.getBlockPos()
                        : finalHit.getBlockPos().relative(finalHit.getDirection());

                if (replaceable && isSlabCombineTarget(level, finalHit)) {
                    // Landing directly on an existing (non-double) slab
                    // that vanilla says is replaceable in this context
                    // means it's about to combine into a double slab --
                    // the outline should be a full box, not a half one,
                    // since that's what the block will actually look like.
                    outlineSlabType = null;
                }
            } else {
                placementPos = computePlacementPos(level, finalHit);
            }

            return new Target(finalHit, true, placementPos, indicatorFace, outlineSlabType, hasDirectBlockInReach);
        }

        BlockPos placementPos = vanillaHit.getType() == HitResult.Type.BLOCK
                ? computePlacementPos(level, vanillaHit)
                : vanillaHit.getBlockPos(); // meaningless for a MISS; never read by callers
        return new Target(vanillaHit, false, placementPos, vanillaHit.getDirection(), null, hasDirectBlockInReach);
    }

    /**
     * Computes where a block will actually land for a given hit, the same
     * way vanilla's own BlockPlaceContext does: if the position the hit
     * points at is itself replaceable (air, tall grass, etc.), the block
     * lands there directly; otherwise it lands one step further, in the
     * direction the hit's face points.
     *
     * This is deliberately convention-agnostic rather than assuming
     * "blockPos is always the solid neighbor" — applySlabAssist
     * constructs its hits using the OTHER convention (blockPos = the air
     * target itself), and this method gives the right answer for either
     * case without callers needing to know which one applies.
     */
    private static BlockPos computePlacementPos(Level level, BlockHitResult hit) {
        return level.getBlockState(hit.getBlockPos()).canBeReplaced()
                ? hit.getBlockPos()
                : hit.getBlockPos().relative(hit.getDirection());
    }

    /**
     * @return true if the block already at hit.getBlockPos() is a genuine
     *         (non-double) slab -- used alongside a context-aware
     *         canBeReplaced() check to distinguish "landing here combines
     *         into a double slab" from "landing here because it's just
     *         ordinary air/replaceable terrain," which needs a different
     *         outline shape (full box vs. half box) even though both cases
     *         resolve to the same placementPos.
     */
    private static boolean isSlabCombineTarget(Level level, BlockHitResult hit) {
        BlockState existing = level.getBlockState(hit.getBlockPos());
        return existing.getBlock() instanceof SlabBlock && existing.getValue(SlabBlock.TYPE) != SlabType.DOUBLE;
    }

    /**
     * Adjusts a gap-fill hit for slab half-selection when the held item is
     * a slab, adapted from squeeglii/BridgingMod's handleHorizontalSlabAssist
     * (see THIRD_PARTY_NOTICES.md) — same underlying trick, adapted to our
     * BlockHitResult convention, which differs from theirs (see below).
     *
     * ONLY the horizontal case is ported. Two things from the original
     * mod's Bridge.java are deliberately NOT included, both worth a closer
     * look later with the ability to actually test in-game rather than
     * risk shipping a subtly wrong port:
     *   - handleVerticalSlabAssist (combining into a double slab when
     *     building against an existing half-slab of the opposite type) —
     *     its exact offset arithmetic didn't resolve cleanly against this
     *     mod's BlockHitResult convention even after reading it carefully,
     *     and getting slab-combining subtly wrong (e.g. destroying an
     *     existing slab, or placing in the wrong spot) is worse than not
     *     having it yet.
     *   - Stairs: the original mod's own code comment says it doesn't
     *     support stair orientation either ("requires major jank or
     *     server-side mods"), so nothing is being lost by not chasing it.
     */
    private record SlabAssistResult(BlockHitResult hit, @Nullable SlabType slabType) {}

    private static SlabAssistResult applySlabAssist(BlockHitResult candidate, Player player, Vec3 from, Vec3 to) {
        if (!isHoldingSlab(player)) {
            return new SlabAssistResult(candidate, null);
        }

        Direction face = candidate.getDirection();
        if (face.getAxis() == Direction.Axis.Y) {
            // Vertical gap-fill (placing directly on top of / underneath a
            // support): vanilla's own slab placement logic already reads
            // the UP/DOWN face correctly here, same as a normal vanilla
            // click would -- no override needed for placement. But the
            // resulting half IS fully deterministic from the face alone
            // (UP -> bottom half, DOWN -> top half, matching vanilla's own
            // SlabBlock.getStateForPlacement), so it costs nothing extra to
            // report it for the outline renderer too.
            var slabType = face == Direction.UP
                    ? SlabType.BOTTOM
                    : SlabType.TOP;
            return new SlabAssistResult(candidate, slabType);
        }

        // Horizontal gap-fill: the original mod's trick is to steer
        // vanilla's own SlabBlock.getStateForPlacement into picking a half
        // by setting the FACE to UP or DOWN, rather than computing the
        // half itself. Their BlockHitResult convention is blockPos=the air
        // target directly (relying on BlockPlaceContext's "replaceClicked"
        // path, since that position is air) -- different from the rest of
        // THIS mod's convention (blockPos=the solid neighbor, direction=
        // the outward face). We rebuild that air-target-based convention
        // just for this one case, since that's specifically what makes
        // the "set the face, let vanilla figure out the half" trick work:
        // relativePos would be computed via blockPos.relative(direction)
        // for a non-replaceable blockPos, which is NOT what we want here.
        BlockPos airPos = candidate.getBlockPos().relative(face);

        // Which half to target: find where the player's actual look ray
        // crosses the target column's vertical centerline, and compare
        // that Y to the block's own vertical center.
        //
        // FIXED: an earlier version compared the player's raw eye height
        // to the block's center instead. That's almost always true in a
        // normal standing bridging pose regardless of where you're
        // actually aiming (your eye is ~1.5 blocks above your feet, and a
        // bridging target is usually at or below foot level) -- so it
        // picked bottom half essentially every time, ignoring look angle
        // entirely. This version tracks the actual aim instead: pitching
        // up gives a higher crossing point (top half), pitching down
        // gives a lower one (bottom half), matching what a player expects
        // from tilting their view.
        boolean lowerHalf = computeLowerHalf(airPos, from, to);
        Direction slabFace = lowerHalf ? Direction.UP : Direction.DOWN;
        var slabType = lowerHalf
                ? SlabType.BOTTOM
                : SlabType.TOP;

        return new SlabAssistResult(new BlockHitResult(Vec3.atCenterOf(airPos), slabFace, airPos, false), slabType);
    }

    /**
     * @return true if the ray from `from` to `to` crosses the target
     *         column's vertical centerline at or below that column's own
     *         vertical center -- i.e. the player is aiming at the lower
     *         half of the gap.
     */
    private static boolean computeLowerHalf(BlockPos airPos, Vec3 from, Vec3 to) {
        double centerX = airPos.getX() + 0.5;
        double centerZ = airPos.getZ() + 0.5;
        Vec3 rayDir = to.subtract(from);

        double crossY;
        if (Math.abs(rayDir.x) >= Math.abs(rayDir.z) && Math.abs(rayDir.x) > 1.0e-6) {
            double t = (centerX - from.x) / rayDir.x;
            crossY = from.y + rayDir.y * t;
        } else if (Math.abs(rayDir.z) > 1.0e-6) {
            double t = (centerZ - from.z) / rayDir.z;
            crossY = from.y + rayDir.y * t;
        } else {
            // Degenerate (looking straight up/down along neither horizontal
            // axis) -- shouldn't happen for a horizontal-face candidate,
            // but fall back to the ray's own start height rather than throw.
            crossY = from.y;
        }

        return crossY <= (airPos.getY() + 0.5);
    }

    private static boolean isHoldingSlab(Player player) {
        return isSlabItem(player.getMainHandItem()) || isSlabItem(player.getOffhandItem());
    }

    private static boolean isSlabItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof SlabBlock;
    }

    private static BlockHitResult doVanillaClip(Level level, Vec3 from, Vec3 to, Player player) {
        ClipContext ctx = new ClipContext(
                from, to,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        );
        return level.clip(ctx);
    }

    /**
     * Walks the exact sequence of blocks between `from` and `to` (via
     * GapFillVoxelPath -- see that class for attribution) looking for the
     * nearest air (or otherwise replaceable) position that has at least
     * one solid neighbor to place a block against -- the core
     * "reach-around" gap-fill check.
     *
     * @return a synthetic BlockHitResult matching vanilla's own convention
     *         (blockPos = the SOLID neighbor block, direction = the face of
     *         THAT block facing the empty position), so it can be handed
     *         straight to Item#useOn(UseOnContext) and go through the exact
     *         same placement logic as a real block click. Returns null if
     *         no valid candidate was found within reach.
     */
    @Nullable
    private static BlockHitResult findGapFillTarget(Level level, Vec3 from, Vec3 to) {
        BlockPos startPos = BlockPos.containing(from);
        BlockPos endPos = BlockPos.containing(to);

        Vec3 viewDirection = to.subtract(from).normalize();
        List<Direction> prioritizedSides = computeValidAssistSides(viewDirection);
        if (prioritizedSides.isEmpty()) {
            // Looking too obliquely between axes for any face to clearly
            // qualify -- matches the original mod's behavior in this case.
            return null;
        }

        for (BlockPos pos : GapFillVoxelPath.calculateVoxels(startPos, endPos)) {
            BlockState state = level.getBlockState(pos);
            if (!state.canBeReplaced()) {
                // A genuinely solid block directly on the ray would already
                // have been caught by the vanilla raycast above, so seeing
                // one here just means "not a valid gap-fill spot" — skip it.
                continue;
            }
            if (!state.getFluidState().isEmpty()) {
                // REAL REGRESSION FOUND VIA USER TESTING: water is
                // canBeReplaced()==true (that's why you can drop a block
                // straight into it normally), so without this check, every
                // single water-filled position along the ray counts as a
                // legitimate gap-fill target -- meaning underwater, this
                // search finds a "gap" almost everywhere at once, instead
                // of only where there's genuinely open air. Vanilla's own
                // ordinary reach already handles placing directly into
                // water within normal range; the assist doesn't need to
                // (and clearly shouldn't) extend that further.
                continue;
            }

            BlockHitResult candidate = findSupportedFace(level, pos, prioritizedSides);
            if (candidate != null) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Ranks which faces are reasonable to build a gap-fill placement
     * against, based on how well each one aligns with the direction the
     * player is actually looking — adapted from squeeglii/BridgingMod's
     * PathTraversalHandler.getValidAssistSides (see THIRD_PARTY_NOTICES.md
     * for attribution).
     *
     * THIS IS THE PIECE THAT WAS MISSING BEFORE, AND CAUSED "UNDER" TO COME
     * OUT AS "IN FRONT": without it, findSupportedFace checked faces in a
     * fixed Direction.values() order (down, up, then the four horizontals)
     * for every gap regardless of view angle, so whichever of those axes
     * happened to have solid support first would win — not necessarily the
     * one the player was actually looking toward. Ranking by view alignment
     * instead means a mostly-downward look prioritizes a vertical result
     * and a mostly-forward look prioritizes a horizontal one, matching what
     * the original mod (and player expectation) actually does.
     *
     * @return candidate `dir` values (pointing from the air target toward
     *         its solid neighbor), ordered by how well they match where the
     *         player is looking. May be empty if the view is too oblique to
     *         any single axis to clear the similarity threshold.
     */
    private static List<Direction> computeValidAssistSides(Vec3 viewDirection) {
        double similarityThreshold = BridgingConfig.DIRECTION_SIMILARITY_THRESHOLD.get();
        List<Direction> validSides = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            Vec3 directionNormal = new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
            double similarity = viewDirection.dot(directionNormal);
            if (similarity < similarityThreshold) {
                continue;
            }
            validSides.add(direction.getOpposite());
        }
        return validSides;
    }

    /**
     * @return a BlockHitResult for the first solid neighbor of `airPos`
     *         found, checked in `candidateSides` order (see
     *         computeValidAssistSides), or null if none of those
     *         candidate sides has solid support.
     *
     * Skips blocks with a real GUI/menu (chests, furnaces, and similar
     * machines) as valid support, checked via MenuProvider rather than
     * the broader EntityBlock. REAL REGRESSION FOUND VIA USER TESTING:
     * this used to check `instanceof EntityBlock` instead, which also
     * has a block entity -- but so does anything with block-entity-held
     * STATE even without a GUI, like Create's shafts/cogwheels/gearboxes
     * (kinetic network data lives in their block entity), and those got
     * silently excluded as support too, breaking bridging against them
     * entirely. MenuProvider is the more precise signal for "has an
     * actual interactive menu to preserve," matching the real reason
     * for the original exclusion (Create's Deployer, specifically,
     * losing its item-filter-setting click).
     *
     * NOT INDEPENDENTLY VERIFIED: whether Create's Filter block (the
     * other original motivating case, alongside Deployer) implements
     * MenuProvider or handles its right-click filter-setting some other
     * way. If Filter's own interaction gets stolen again after this
     * change, that's the thing to check next -- rather than guess at
     * Create's internals without being able to inspect its source here.
     */
    @Nullable
    private static BlockHitResult findSupportedFace(Level level, BlockPos airPos, List<Direction> candidateSides) {
        for (Direction dir : candidateSides) {
            BlockPos solidPos = airPos.relative(dir);
            BlockState solidState = level.getBlockState(solidPos);
            if (solidState.canBeReplaced()) {
                continue;
            }
            if (solidState.getBlock() instanceof EntityBlock
                    && solidState.getMenuProvider(level, solidPos) != null) {
                continue;
            }

            // dir points FROM airPos TOWARD solidPos; the face of solidPos
            // that faces back toward airPos is the opposite direction —
            // this matches vanilla's BlockHitResult convention exactly.
            Direction face = dir.getOpposite();
            Vec3 hitLocation = Vec3.atCenterOf(solidPos).add(
                    face.getStepX() * 0.5,
                    face.getStepY() * 0.5,
                    face.getStepZ() * 0.5
            );
            return new BlockHitResult(hitLocation, face, solidPos, false);
        }
        return null;
    }
}
