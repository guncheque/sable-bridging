# Development history / changelog

This mod was built collaboratively with Claude (Anthropic's AI) through an
extended series of implementation passes and real bug fixes found through
actual in-game testing. This file is that history, kept as-is rather than
cleaned up, because it's genuinely useful context — several of these were
real, confirmed bugs (not theoretical), found through actual gameplay and
fixed the same way.

If you just want to know how to use the mod, see [README.md](README.md)
instead — this file is "what changed and why," not a user guide.

---

## 1. Initial scaffold

Standalone reach-around ("Bedrock bridging") mod for NeoForge 1.21.1, with
optional Sable sub-level awareness via
[sable-companion](https://github.com/ryanhcode/sable-companion) — a
stable, MIT-licensed compatibility library, chosen deliberately over
mixing into Sable's own intrusive internal mixins the way Sable's
Create-compat code does. The mod works without Sable installed at all
(companion's safe default implementation returns "not on a sub-level" in
that case) and gains sub-level correctness automatically when Sable is
present, with no separate build or config needed for either case.

Two real build-breaking issues were found and fixed before the first
successful build:
- `sable-companion`'s own README has a buggy copy-paste Gradle snippet —
  the real published artifact ID includes the Minecraft version suffix
  (`sable-companion-common-1.21.1`), which the README's dependency
  snippet omits (though its own version badge URL gets it right).
- The initial mod entrypoint used `FMLJavaModLoadingContext`, which is
  Forge-era API that doesn't exist in NeoForge — the mod event bus is
  injected directly as a constructor parameter instead.

## 2. Gap-fill placement algorithm

The core mechanic: when a normal raycast misses, search for the nearest
air position with a solid neighbor to place against. First implementation
was a fixed-step raymarch along the look ray.

Replaced with an adapted port of the popular
[Bridging Mod](https://github.com/squeeglii/BridgingMod)'s real algorithm
(MIT licensed; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for
full attribution) — an exact 3D Bresenham voxel traversal plus a
diagonal-edge/corner-adjacency check, meaningfully more correct than a
raymarch (no step-size tuning, no risk of skipping a thin gap).

## 3. Three real bugs found through in-game testing

**Placement direction flipped (front instead of under, or vice versa).**
The ported voxel traversal was there, but the actual face-priority logic
that decides *which* of several valid support faces to use for a given
gap had been skipped — the mod was checking faces in a fixed order
(down, up, then horizontals) regardless of where the player was actually
looking. Fixed by porting `PathTraversalHandler.getValidAssistSides`
(view-direction-aware face ranking) from the same reference mod.

**Placement silently failing when something solid sat further along the
sightline than the intended gap.** The normal raycast was allowed to
"shadow" a nearer valid gap whenever it hit anything at all further away
within reach — a wall, a tree, distant terrain. Fixed by bounding the
gap-fill search to whatever distance the normal raycast actually reached,
so a nearer valid gap wins over a farther unrelated hit.

**Slabs only ever placed on the lower half, regardless of look angle.**
The half-selection heuristic compared the player's raw standing eye
height to the target block's center — which is true in nearly every real
bridging pose regardless of aim, since your eye sits well above a typical
bridging target. Fixed by computing where the player's actual look ray
crosses the target's vertical centerline instead, which correctly
responds to pitching up vs. down. This also fixed a related latent bug:
the old heuristic compared global eye coordinates against local
sub-level coordinates when riding a Sable contraption, which hadn't
surfaced yet only because it hadn't been tested there with a slab.

## 4. Features added

- **Crosshair indicator** — up/down/horizontal sprites and the
  face-to-sprite mapping are adapted directly from Bridging Mod (MIT,
  see THIRD_PARTY_NOTICES.md), so the mod feels immediately familiar to
  anyone who's used the original. The actual render hook is original
  code using NeoForge's public `RenderGuiLayerEvent` rather than a mixin
  into `Hud` the way the reference mod does it — same reasoning as
  choosing sable-companion over Sable's own internals.
- **Toggle keybind** — defaults to unbound and enabled. Client-side only
  for now; see Known Limitations in the README.
- **World-space outline box** — shows exactly where a gap-fill block
  will land, matching vanilla's own block-selection outline style.
  Doesn't yet render while riding a Sable sub-level (see Known
  Limitations) — deliberately skips drawing there rather than draw in
  the wrong place.
- **Slab half-selection** — adapted from Bridging Mod's
  `handleHorizontalSlabAssist`, steering vanilla's own slab-placement
  logic into picking the correct half rather than reimplementing it.
  The outline box also shrinks to match when a slab is what's actually
  landing.

## 5. Code review findings (found before they shipped as bugs)

A deliberate self-review pass caught two real issues before they reached
testing:
- The slab-assist code constructs its synthetic placement hit using a
  different internal convention (position = the air target directly)
  than the rest of the mod (position = the solid neighbor + outward
  face). The outline renderer and crosshair icon were both computing
  position/orientation assuming only the second convention, which would
  have silently drawn the outline in the wrong spot and shown the wrong
  icon whenever a slab was involved. Fixed by giving the internal
  `Target` result explicit `placementPos` and `indicatorFace` fields,
  computed once correctly, rather than leaving each consumer to
  re-derive them.
- A stray doc comment claiming the outline renderer already handled the
  Sable sub-level pose transform turned out to be wrong on immediate
  re-check — it doesn't. Caught and corrected before it could mislead a
  future pass into thinking that part was already done.

## 6. Multiplayer toggle sync

The toggle keybind's `enabled` state was a plain client-side static
field, which only ever affected the connecting player's own local copy
of the interaction event — a genuine dedicated server's own copy (the
one that actually places blocks) had no way to know a player had turned
the assist off. Fixed by adding a small custom network payload
(`BridgingTogglePayload`) sent whenever the keybind is pressed, and a
plain in-memory server-side map (`BridgingServerState`, deliberately not
using NeoForge's Data Attachment system, which wasn't verified this
session) recording each connected player's preference. The interaction
handler now checks the physical side correctly: the client-only static
field for immediate local responsiveness, or the synced server-side
state on a genuine dedicated server, which never loads the client-only
toggle class at all.

