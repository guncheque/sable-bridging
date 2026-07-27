package dev.example.sablebridging;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

// Verified against the official NeoForged docs (docs.neoforged.net/docs/gettingstarted/modfiles):
// the mod event bus is injected directly as a constructor parameter, not looked
// up via FMLJavaModLoadingContext -- that was Forge-era API and doesn't exist
// in NeoForge. ModContainer is also injectable if needed later (metadata access,
// config registration, etc.) -- included here since it's cheap to have on hand.
@Mod(SableBridgingMod.MOD_ID)
public class SableBridgingMod {

    public static final String MOD_ID = "sablebridging";

    public SableBridgingMod(IEventBus modEventBus, ModContainer modContainer) {
        // Config registration -- see BridgingConfig's doc comment for why
        // gameplay-affecting values live in SERVER_SPEC (auto-synced to
        // clients by NeoForge) rather than CLIENT_SPEC. SERVER_SPEC is
        // registered unconditionally, same reasoning as payload
        // registration below: both physical sides need it (the server to
        // load/serve it, the client to receive the synced copy). Neither
        // ModConfigSpec nor ModContainer#registerConfig are client-only
        // classes, so this is safe either way.
        modContainer.registerConfig(ModConfig.Type.SERVER, BridgingConfig.SERVER_SPEC);

        // PlayerInteractEvent is a gameplay event, not a mod-lifecycle
        // event, so it belongs on NeoForge.EVENT_BUS (the game bus) rather
        // than modEventBus (the mod bus, for setup/registration events).
        //
        // Registered at EventPriority.LOWEST (verified: NeoForge runs
        // listeners HIGHEST -> LOWEST, default NORMAL, and does NOT
        // deliver an already-canceled event to a listener unless it
        // explicitly opts into receiveCanceled) -- deliberately, found
        // via a real compat bug where this mod's own gap-fill find stole
        // a right-click from Create's Deployer/Filter item-filter-setting.
        // Running last means every other mod's own RightClickItem
        // handling gets first crack at an ambiguous click; if any of them
        // cancels the event (as Create's own handling plausibly does),
        // this mod's listener is simply never invoked at all for it.
        // Complements (doesn't replace) the EntityBlock exclusion in
        // BridgingPlacement.findSupportedFace, which covers the case
        // where the other mod's interaction runs through a shared
        // fallback path rather than its own separate listener -- the
        // exact internal mechanism Create uses wasn't verifiable without
        // reading its source, so both fixes are applied rather than
        // guessing which one alone would be sufficient.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, BridgingInteractionHandler::onRightClickItem);

        // Payload registration is NOT client-only, deliberately outside
        // the Dist guard below: both the client (to send) and a genuine
        // dedicated server (to receive and decode) need to know about
        // this payload type. Neither BridgingTogglePayload nor its
        // handler touch any client-only classes, so this is safe on
        // both physical sides.
        modEventBus.addListener(SableBridgingMod::registerPayloads);

        // Everything below touches client-only classes (KeyMapping,
        // GuiGraphics, PoseStack, etc.), so it's gated behind
        // FMLEnvironment.dist.isClient() -- the standard NeoForge idiom
        // for this. Without the guard, simply evaluating a method
        // reference like BridgingKeybinds::register would force-load
        // BridgingKeybinds (and its client-only KeyMapping field) even on
        // a dedicated server, which doesn't have those classes on its
        // classpath at all and would crash with NoClassDefFoundError.
        if (FMLEnvironment.dist.isClient()) {
            modContainer.registerConfig(ModConfig.Type.CLIENT, BridgingConfig.CLIENT_SPEC);

            modEventBus.addListener(BridgingKeybinds::register);
            NeoForge.EVENT_BUS.addListener(BridgingKeybinds::onClientTick);
            NeoForge.EVENT_BUS.addListener(BridgingTargetCache::onClientTick);

            // Client-only rendering registration goes inside
            // FMLClientSetupEvent (fires only on the client) rather than
            // directly here, matching the same reasoning as the guard
            // above, one layer further in.
            modEventBus.addListener(this::onClientSetup);
        }
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(BridgingTogglePayload.TYPE, BridgingTogglePayload.STREAM_CODEC, BridgingTogglePayloadHandler::handle);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        NeoForge.EVENT_BUS.addListener(BridgingCrosshairRenderer::onRenderCrosshairLayer);
        NeoForge.EVENT_BUS.addListener(BridgingHighlightRenderer::onRenderLevelStage);
    }
}
