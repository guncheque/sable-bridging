package dev.example.sablebridging;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Draws the reach-around indicator sprite over the crosshair when a
 * gap-fill target is available, deliberately matching the popular Bridging
 * Mod's visuals (see BridgingIndicator's doc comment for attribution) so
 * it feels familiar to anyone who's used that mod before.
 *
 * Uses RenderGuiLayerEvent.Post targeting VanillaGuiLayers.CROSSHAIR — the
 * public, supported NeoForge hook for this — rather than a mixin into
 * Minecraft's Hud class the way the original mod does it. Same reasoning
 * as choosing sable-companion over Sable's own internals: a supported API
 * over something intrusive, wherever one's available. This also means we
 * get vanilla's own crosshair-visibility rules (spectator mode, F1 hidden
 * GUI, etc.) for free — this event simply doesn't fire in those cases,
 * where the original mod's mixin has to check for them manually.
 */
public final class BridgingCrosshairRenderer {

    private static final int ICON_SIZE = 32;

    private BridgingCrosshairRenderer() {}

    public static void onRenderCrosshairLayer(RenderGuiLayerEvent.Post event) {
        if (!VanillaGuiLayers.CROSSHAIR.equals(event.getName())) {
            return;
        }
        if (!BridgingKeybinds.enabled) {
            return;
        }

        // Shared per-tick cache, not a fresh raycast every frame -- see
        // BridgingTargetCache's doc comment for why this matters (this
        // used to be a real, confirmed source of noticeable lag near
        // Sable sub-levels).
        BridgingPlacement.Target target = BridgingTargetCache.get();
        if (target == null) {
            return;
        }

        // Only show our indicator for gap-fill targets -- an ordinary
        // direct-look hit already has vanilla's own crosshair for it.
        if (!target.isGapFill() || target.hit().getType() != HitResult.Type.BLOCK) {
            return;
        }

        // Use indicatorFace, not hit.getDirection() -- applySlabAssist can
        // override the hit's own direction to UP/DOWN to steer vanilla's
        // slab half-selection, even for a placement that's geometrically
        // horizontal. indicatorFace always reflects the true placement
        // orientation, so the icon shown matches what's actually happening.
        //
        // Also still need .getOpposite() here: BridgingIndicator.from()
        // expects the same "checkSide" polarity the original mod uses
        // (pointing FROM the target TOWARD its solid neighbor), but
        // indicatorFace stores the OUTWARD face instead (vanilla's own
        // convention). These are opposites of each other.
        BridgingIndicator indicator = BridgingIndicator.from(target.indicatorFace().getOpposite());
        if (indicator == null) {
            return;
        }

        GuiGraphics gui = event.getGuiGraphics();
        int x = (gui.guiWidth() - ICON_SIZE + 1) / 2;
        int y = (gui.guiHeight() - ICON_SIZE + 1) / 2;

        gui.blitSprite(indicator.getTexturePath(), x, y, ICON_SIZE, ICON_SIZE);
    }
}
