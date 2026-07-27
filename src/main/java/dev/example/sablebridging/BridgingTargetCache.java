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
     * @return the sub-level the player was riding as of the last tick
     *         this was computed, or null if not on one (or not
     *         applicable right now — same conditions as get()).
     */
    @Nullable
    public static SubLevelAccess getSubLevel() {
        return cachedSubLevel;
    }
}
