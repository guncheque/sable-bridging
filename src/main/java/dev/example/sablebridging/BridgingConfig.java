package dev.example.sablebridging;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Config values, verified against NeoForge's real config docs before
 * writing (untouched by this mod until now).
 *
 * ModConfig.Type.SERVER is the ONLY config type NeoForge automatically
 * syncs from server to connecting clients (confirmed directly: "Only
 * ModConfig.Type.SERVER configurations are synchronized"). That's
 * specifically why the gameplay-affecting values (reach distance,
 * face-priority threshold, snap strength) live in SERVER_SPEC here:
 * without that sync, a player could tune these on their own client and
 * see a preview (crosshair/outline) that doesn't match what the server
 * — authoritative for real placement — actually does. Picking the right
 * config type solves the same class of client/server mismatch already
 * solved once this session for the toggle keybind, but for free here
 * instead of needing custom networking again.
 *
 * MIN_RENDER_DISTANCE is CLIENT-only by contrast: it only affects what's
 * drawn on one player's own screen, so there's nothing to keep in sync.
 */
public final class BridgingConfig {

    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec.DoubleValue REACH_DISTANCE;
    public static final ModConfigSpec.DoubleValue DIRECTION_SIMILARITY_THRESHOLD;
    public static final ModConfigSpec.DoubleValue SNAP_STRENGTH;

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.DoubleValue MIN_RENDER_DISTANCE;

    static {
        ModConfigSpec.Builder serverBuilder = new ModConfigSpec.Builder();
        serverBuilder.push("gameplay");

        REACH_DISTANCE = serverBuilder
                .comment("How far (in blocks) the reach-around search looks for a gap-fill placement. Matches vanilla survival reach by default.")
                .defineInRange("reachDistance", 4.5, 1.0, 32.0);

        DIRECTION_SIMILARITY_THRESHOLD = serverBuilder
                .comment("How closely you need to be looking toward an axis for it to count as a candidate support face. Lower = more forgiving/ambiguous placements; higher = stricter, more precise aim required.")
                .defineInRange("directionSimilarityThreshold", 0.1, 0.0, 1.0);

        SNAP_STRENGTH = serverBuilder
                .comment("Margin used when checking for diagonal-adjacency misses in the gap-fill search. Matches Bridging Mod's own 'snap strength' dial. Higher = catches more diagonal edge cases; lower = stricter/more literal.")
                .defineInRange("snapStrength", 1.0, 0.0, 2.0);

        serverBuilder.pop();
        SERVER_SPEC = serverBuilder.build();

        ModConfigSpec.Builder clientBuilder = new ModConfigSpec.Builder();
        clientBuilder.push("rendering");

        MIN_RENDER_DISTANCE = clientBuilder
                .comment("Minimum distance (in blocks) from your eye before the outline box is drawn. Prevents the box rendering uncomfortably close to the camera in tight tunnels.")
                .defineInRange("minRenderDistance", 1.5, 0.0, 8.0);

        clientBuilder.pop();
        CLIENT_SPEC = clientBuilder.build();
    }

    private BridgingConfig() {}
}
