package dev.example.sablebridging;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Toggle keybind for enabling/disabling reach-around placement, defaulting
 * to enabled and unbound (matching standard practice for optional QoL
 * toggles — the player can bind it in Controls if they want it).
 *
 * `enabled` itself is still a plain client-side static field — that part
 * is unavoidable, since KeyMapping only exists client-side — but every
 * toggle now also sends BridgingTogglePayload to the server, which stores
 * it in BridgingServerState and is what BridgingInteractionHandler
 * actually checks before performing real placement in genuine
 * multiplayer. `enabled` here still gates the LOCAL client's own
 * responsiveness (crosshair, outline, and the client's own copy of the
 * interaction event) immediately, without waiting on a network
 * round-trip — the server-side state is the authoritative one for
 * whether placement actually happens.
 *
 * This whole class touches client-only Minecraft classes (KeyMapping),
 * so it must only ever be loaded on the physical client — see the
 * FMLEnvironment.dist.isClient() guard around every reference to it in
 * SableBridgingMod.
 */
public final class BridgingKeybinds {

    public static final String CATEGORY = "key.categories.sablebridging";

    public static final KeyMapping TOGGLE_BRIDGING = new KeyMapping(
            "key.sablebridging.toggle",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            CATEGORY
    );

    public static boolean enabled = true;

    private BridgingKeybinds() {}

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_BRIDGING);
    }

    /** Hooked to ClientTickEvent.Post — consumeClick() in a while loop is the documented NeoForge pattern for in-game (non-GUI) keybinds. */
    public static void onClientTick(ClientTickEvent.Post event) {
        while (TOGGLE_BRIDGING.consumeClick()) {
            enabled = !enabled;

            // Tell the server too -- this is what actually makes the
            // toggle affect real placement in genuine multiplayer, not
            // just this client's own local display.
            PacketDistributor.sendToServer(new BridgingTogglePayload(enabled));

            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.literal("Bridging assist " + (enabled ? "enabled" : "disabled")),
                        true // show in the actionbar, not chat
                );
            }
        }
    }
}
