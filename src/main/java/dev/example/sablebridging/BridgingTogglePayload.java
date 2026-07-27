package dev.example.sablebridging;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent client -> server whenever the player toggles bridging assist, so
 * the server (which is what actually performs placement) knows about the
 * client's preference too.
 *
 * Closes a real limitation flagged earlier: BridgingKeybinds.enabled is a
 * plain client-side static field. Without this payload, the toggle only
 * ever affected the connecting player's own local copy of
 * PlayerInteractEvent.RightClickItem in genuine multiplayer — the real
 * dedicated server's own copy of that event (the one that actually
 * places blocks) had no way to know the player had turned it off.
 *
 * A ByteBuf-based StreamCodec is used rather than RegistryFriendlyByteBuf,
 * since a single boolean needs no registry access — matches the official
 * docs' own note that plain ByteBuf codecs work fine when the convenience
 * FriendlyByteBuf/RegistryFriendlyByteBuf methods aren't needed.
 */
public record BridgingTogglePayload(boolean enabled) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BridgingTogglePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SableBridgingMod.MOD_ID, "toggle"));

    public static final StreamCodec<ByteBuf, BridgingTogglePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, BridgingTogglePayload::enabled,
            BridgingTogglePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
