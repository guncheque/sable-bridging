package dev.example.sablebridging;

import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side record of each player's bridging-assist toggle preference,
 * populated by BridgingTogglePayload's handler and read by
 * BridgingInteractionHandler before attempting a gap-fill placement.
 *
 * Deliberately a plain in-memory Map rather than NeoForge's Data
 * Attachment system (AttachmentType/DeferredRegister<AttachmentType<?>>)
 * — that's a real, separate API surface this session hasn't touched or
 * verified at all, and a simple UUID-keyed map is fully sufficient here:
 * this is a UI preference, not save data, so losing it on server restart
 * (defaulting back to enabled) is the right behavior anyway, not a
 * limitation worth taking on unverified API risk to avoid.
 *
 * This class only ever touches plain Java + the shared (non-client-only)
 * Player type, so it's safe to reference from any physical side,
 * including a genuine dedicated server, without any Dist guard.
 */
public final class BridgingServerState {

    private static final Map<UUID, Boolean> enabledByPlayer = new ConcurrentHashMap<>();

    private BridgingServerState() {}

    public static boolean isEnabled(Player player) {
        return enabledByPlayer.getOrDefault(player.getUUID(), true);
    }

    public static void setEnabled(Player player, boolean enabled) {
        enabledByPlayer.put(player.getUUID(), enabled);
    }
}
