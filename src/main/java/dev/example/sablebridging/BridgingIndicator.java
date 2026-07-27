package dev.example.sablebridging;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/**
 * Which crosshair indicator sprite to show for a given placement face.
 *
 * Adapted from squeeglii/BridgingMod's PlacementAlignment
 * (https://github.com/squeeglii/BridgingMod), used under its MIT license
 * (see THIRD_PARTY_NOTICES.md at the project root) — including the actual
 * up/down/horizontal sprite assets, deliberately kept identical so this
 * mod's crosshair looks and feels like the one people are already used to.
 */
public enum BridgingIndicator {

    UP("up"),
    DOWN("down"),
    HORIZONTAL("horizontal");

    private final ResourceLocation textureLocation;

    BridgingIndicator(String textureName) {
        this.textureLocation = ResourceLocation.fromNamespaceAndPath(
                SableBridgingMod.MOD_ID, "indicator/" + textureName);
    }

    public ResourceLocation getTexturePath() {
        return this.textureLocation;
    }

    /**
     * @return which sprite to show for a block placement against the given
     *         face, or null if there's no target (direction is null).
     *
     * NOTE: UP/DOWN are intentionally swapped versus the face direction —
     * carried over unchanged from the original mod's convention, matching
     * how it actually looks when placing underneath vs. on top of a block.
     */
    public static BridgingIndicator from(Direction direction) {
        if (direction == null) {
            return null;
        }
        return switch (direction) {
            case UP -> DOWN;
            case DOWN -> UP;
            default -> HORIZONTAL;
        };
    }
}
