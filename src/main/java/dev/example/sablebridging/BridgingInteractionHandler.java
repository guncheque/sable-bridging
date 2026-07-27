package dev.example.sablebridging;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Wires BridgingPlacement's gap-fill search into an actual right-click
 * trigger.
 *
 * PlayerInteractEvent.RightClickItem was chosen deliberately, verified
 * against the real NeoForge event docs rather than guessed:
 *   - "Fired on both sides before the player triggers Item.use(...)"
 *   - "NOT fired if the player is targeting a block" (old Forge doc,
 *     same semantics carried into NeoForge) -- meaning vanilla's own
 *     PRE-EVENT check found no block to target.
 *   - Firing on both sides matters: no custom networking is needed for
 *     THIS part. World mutation is still gated to the logical server
 *     (!level.isClientSide()) to avoid double-placement/desync, matching
 *     how vanilla itself only ever writes world state from the server
 *     side. (The toggle check below DOES need networking — see
 *     BridgingTogglePayload — since the toggle preference itself has to
 *     reach the server somehow before this gate can honor it there.)
 *
 * REAL BUG, found via a reported compatibility issue with Create's
 * Deployer/Filter (right-clicking to set their item filter stopped
 * working when something else right-clickable sat further along the
 * sightline): this class used to act on ANY BLOCK-type result from
 * raycastForBridging, on the assumption that vanilla's pre-event miss
 * guaranteed any hit we found must be our own gap-fill candidate. That
 * assumption doesn't actually hold — raycastForBridging's OWN internal
 * vanilla-style check (doVanillaClip) can independently find a normal
 * block hit even when the game's own pre-event check reported a miss,
 * plausibly because Create's Deployer/Filter has a collision/outline
 * shape quirk that reads as "no target" to vanilla's own targeting logic
 * but still resolves to something via our raycast. Acting (and
 * cancelling the event) on THAT kind of hit steals the interaction from
 * whatever else wanted to handle it -- Create's own filter-setting logic
 * in this case. Fixed by only ever acting on target.isGapFill()
 * specifically -- a genuine reach-around find, never a normal hit our
 * own search happened to also turn up.
 */
public final class BridgingInteractionHandler {

    private BridgingInteractionHandler() {}

    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        // FMLEnvironment.dist (PHYSICAL side: is there a client running in
        // this JVM at all) rather than level.isClientSide() (LOGICAL side:
        // is this specific event copy the client's or the server's) —
        // the distinction matters because this method also runs unchanged
        // on a genuine dedicated server, which never loads BridgingKeybinds
        // (client-only class) at all. dist.isClient() is false there, so
        // the first branch short-circuits before ever touching that
        // class, rather than crashing with NoClassDefFoundError.
        //
        // On the client's own physical side (both the connecting player's
        // real client, AND the integrated-server thread in singleplayer,
        // since that's the same JVM/physical side too), BridgingKeybinds
        // is checked directly for immediate local responsiveness, with no
        // network round-trip needed.
        //
        // On a genuine dedicated server specifically, BridgingKeybinds.
        // enabled doesn't exist to check at all — instead this reads
        // BridgingServerState, which is populated by BridgingTogglePayload
        // whenever a connected player toggles their own preference. This
        // is what makes the toggle actually affect real placement in
        // genuine multiplayer, closing the gap an earlier version of this
        // file left open (that version could only ever gate the client's
        // own local copy of this event, never the server's).
        if (FMLEnvironment.dist.isClient()) {
            if (!BridgingKeybinds.enabled) {
                return;
            }
        } else if (!BridgingServerState.isEnabled(event.getEntity())) {
            return;
        }

        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof BlockItem)) {
            return;
        }

        var player = event.getEntity();
        InteractionHand hand = event.getHand();

        BridgingPlacement.Target target = BridgingPlacement.raycastForBridging(player, BridgingConfig.REACH_DISTANCE.get());
        BlockHitResult hit = target.hit();
        if (!target.isGapFill() || hit.getType() != HitResult.Type.BLOCK) {
            // Either no valid gap-fill candidate, or our own search found
            // an ordinary hit that isn't actually a gap-fill find (see
            // class doc — acting on that kind of hit is what caused the
            // Create Deployer/Filter interference). Either way, this
            // isn't our interaction to handle: let the event proceed
            // untouched, so whatever else wants it (another mod's own
            // handler, or vanilla's generic Item.use() fallback) still
            // gets a chance to run.
            return;
        }

        Level level = player.level();
        if (!level.isClientSide()) {
            UseOnContext context = new UseOnContext(player, hand, hit);
            InteractionResult result = stack.useOn(context);
            // TODO: if result indicates failure (e.g. the gap-fill spot got
            // occupied between the search and the place attempt), consider
            // feedback to the player (sound/particle) rather than silence.
        }

        // Cancel either way once we've committed to handling this
        // right-click ourselves, so vanilla's generic Item.use() fallback
        // doesn't also run afterward on top of our placement attempt.
        event.setCanceled(true);
    }
}
