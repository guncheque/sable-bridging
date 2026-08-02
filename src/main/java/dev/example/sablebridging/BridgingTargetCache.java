package dev.example.sablebridging;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Caches the current gap-fill search result once per client tick, shared
 * between BridgingCrosshairRenderer and BridgingHighlightRenderer.
 *
 * FOUND VIA A REAL PERFORMANCE BUG REPORT: before this, each of those two
 * renderers independently called BridgingPlacement.raycastForBridging
 * every single RENDER FRAME (not tick) whenever holding a block item —
 * flagged as a "cheap enough for now, revisit if it matters" TODO when
 * the rendering was first added. It ended up mattering: the redundancy
 * (2x per frame, 60+ times a second) turned into noticeable lag
 * specifically when looking at or near a Sable sub-level, where Sable's
 * own block/chunk access is presumably meaningfully heavier than
 * vanilla's ordinary access. Caching per tick instead cuts the call rate
 * from roughly 120/sec (2 renderers x 60fps) down to ~20/sec (1 x tick
 * rate) — about a 6x reduction — while still feeling instantaneous, since
 * a crosshair/outline that updates once per tick rather than every
 * single frame isn't perceptible to a player.
 *
 * Also caches the resolved SubLevelAccess (or null) alongside the
 * search result, found on a later review pass: BridgingHighlightRenderer
 * was independently calling BridgingPlacement.getPlayerSubLevel(player)
 * itself, once per FRAME, to decide whether to draw the sub-level-aware
 * outline path -- the exact same category of redundant per-frame Sable
 * API call this class exists to eliminate, just added after this class
 * already existed and never routed through it. Exposing the cached value
 * here means that check now runs at tick rate too.
 */
public final class BridgingTargetCache {

    @Nullable
    private static BridgingPlacement.Target cached = null;

    @Nullable
    private static SubLevelAccess cachedSubLevel = null;

    // TEMPORARY DIAGNOSTIC, debug-instrumentation branch only. Extended
    // from the earlier version to test a specific hypothesis for the
    // hasDirectBlockInReach mismatch: that it's a coordinate-space bug,
    // where the ray gets transformed into a Sable sub-level's LOCAL space
    // even when the block actually being aimed at is ordinary global-space
    // terrain. Now logs whether a sub-level was detected at all, the raw
    // vanillaHit's own position/distance (in whichever space it was
    // actually searched in), and the player's real global eye position for
    // cross-referencing against F3 coordinates in-game.
    private static int debugTickCounter = 0;

    private BridgingTargetCache() {}

    public static void onClientTick(ClientTickEvent.Post event) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            cached = null;
            cachedSubLevel = null;
            return;
        }

        if (!(player.getMainHandItem().getItem() instanceof BlockItem)
                && !(player.getOffhandItem().getItem() instanceof BlockItem)) {
            cached = null;
            cachedSubLevel = null;
            return;
        }

        cachedSubLevel = BridgingPlacement.getPlayerSubLevel(player);
        cached = BridgingPlacement.raycastForBridging(player, BridgingConfig.REACH_DISTANCE.get());

        if (cached != null && cached.isGapFill()) {
            debugTickCounter++;
            if (debugTickCounter % 30 == 0) {
                net.minecraft.world.phys.BlockHitResult rawHit = BridgingPlacement.debugLastVanillaHit;
                String rawInfo;
                if (rawHit == null) {
                    rawInfo = "null";
                } else {
                    double rawDist = player.getEyePosition().distanceTo(rawHit.getLocation());
                    rawInfo = rawHit.getType() + "@" + rawHit.getBlockPos()
                            + " dist=" + String.format("%.2f", rawDist);
                }
                // Chat instead of the action bar: the action bar is a
                // single line with NO wrapping, so a message this long was
                // getting clipped on both ends -- found via a real
                // screenshot where the start and end were both cut off
                // mid-field. Chat wraps and stays in the scrollable log,
                // so split across two messages for readability rather than
                // one long line.
                // Now also shows Minecraft's own live hitResult alongside
                // the mod's own raw vanillaHit -- this is exactly the
                // comparison that confirmed the original bug (Jade found a
                // Create shaft that this mod's own tick-based check
                // reported as a MISS), so keeping both visible side by
                // side is useful for spotting any future disagreement too.
                net.minecraft.world.phys.HitResult mcHit = Minecraft.getInstance().hitResult;
                String mcInfo = mcHit == null ? "null" : mcHit.getType() + "@"
                        + (mcHit instanceof net.minecraft.world.phys.BlockHitResult blockHit ? blockHit.getBlockPos() : "n/a");
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "[BridgingDebug] onSubLevel=" + (cachedSubLevel != null)
                                        + " rawVanillaHit=[" + rawInfo + "]"
                                        + " mcHitResult=[" + mcInfo + "]"
                        ),
                        false
                );
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "  gapFillHitPos=" + cached.hit().getBlockPos()
                                        + " realEyePos=" + player.getEyePosition()
                        ),
                        false
                );
            }
        } else {
            debugTickCounter = 0;
        }
    }

    /**
     * @return the most recently cached target, or null if not applicable
     *         right now (no player, or not holding a block item in
     *         either hand).
     */
    @Nullable
    public static BridgingPlacement.Target get() {
        return cached;
    }

    /**
     * Like get(), but recomputes fresh at full FRAME rate specifically
     * when the player is on a Sable sub-level, instead of reusing the
     * once-per-tick cached value. For the ordinary (not on a sub-level)
     * case, this is identical to get() -- same cheap tick-rate cache,
     * unchanged.
     *
     * WHY THIS EXISTS: found via real testing on a continuously-rotating
     * platform. The outline's actual RENDER POSITION already
     * re-transforms into world space every frame using the sub-level's
     * live pose (see BridgingHighlightRenderer), so that part was always
     * smooth. But WHICH block is even the correct gap-fill target can
     * itself change continuously while the sub-level rotates underneath
     * a fixed look direction -- purely because "correct target" is a
     * function of the player's LOCAL-space aim, which keeps changing
     * even if the player's real head doesn't move at all. That decision
     * only being re-evaluated once per tick (20/sec) made the outline
     * visibly hop between candidate blocks in discrete jumps instead of
     * sliding, on anything continuously moving -- reported as stutter.
     *
     * Deliberately scoped to ONLY the on-a-sub-level case: normal ground
     * bridging (the vast majority of play time) keeps the original
     * once-per-tick cache completely untouched. The once-per-tick
     * caching exists specifically because of a REAL prior lag bug from
     * raycasting every frame near sub-levels (see this class's own top
     * doc comment) -- this reintroduces that same per-frame cost, but
     * only for exactly the situation that actually needs the extra
     * accuracy, not everywhere. Worth re-verifying there's no
     * regression of that original lag bug specifically near sub-levels
     * after this change.
     */
    @Nullable
    public static BridgingPlacement.Target getForRender(Player player) {
        if (cachedSubLevel == null) {
            return cached;
        }
        return BridgingPlacement.raycastForBridging(player, BridgingConfig.REACH_DISTANCE.get());
    }

    /**
     * @return the sub-level the player was riding as of the last tick
     *         this was computed, or null if not on one (or not
     *         applicable right now — same conditions as get()).
     */
    @Nullable
    public static SubLevelAccess getSubLevel() {
        return cachedSubLevel;
    }
}
