package dev.example.sablebridging;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Handles BridgingTogglePayload on receipt (always the server side, since
 * this payload is only ever registered playToServer).
 *
 * NOTE ON CONFIDENCE: IPayloadContext.player() is used here to get the
 * sending player. This is the standard, widely-documented accessor for
 * this purpose (NeoForge's own networking-rework announcement describes
 * exactly this: "an Optional containing a player is also provided... if
 * the handler is invoked on the server side, then this is the player
 * that sent the payload"), but unlike most of this mod's other API
 * usages, I wasn't able to pin down the LITERAL method signature against
 * a primary source this session — worth double-checking against a real
 * compile error if this specific line is what fails.
 */
public final class BridgingTogglePayloadHandler {

    private BridgingTogglePayloadHandler() {}

    public static void handle(BridgingTogglePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() != null) {
                BridgingServerState.setEnabled(context.player(), payload.enabled());
            }
        });
    }
}
