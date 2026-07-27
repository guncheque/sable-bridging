# Sable Bridging

Reach-around ("Bedrock bridging") block placement for NeoForge 1.21.1 —
place blocks in air gaps in front of, above, or below you, even when
you're not looking directly at a solid face. Works as a plain standalone
bridging mod on its own; when [Sable](https://github.com/ryanhcode/sable)
is also installed, it stays correct while standing on a moving physics
contraption (a Create Aeronautics ship, plane, or any other sub-level in
flight or motion) too.

## Features

- Bedrock-style reach-around placement, on any of the 6 axes
- A crosshair indicator shows when a gap-fill placement is available and
  which face it'll build against
- A world-space outline box shows exactly where the block will land
- Slabs placed into a gap correctly pick top or bottom half based on
  where you're looking, matching vanilla's own slab placement feel
- A toggle keybind (unbound by default — set it in Controls) to turn
  reach-around assist on/off
- Sable-aware: gap-fill placement keeps working correctly while riding a
  moving sub-level, without needing Sable installed at all for the base
  feature to work
- No hard dependency on Sable — install it and get sub-level support, or
  don't and just get an ordinary bridging mod

## Installation

Requires NeoForge on Minecraft 1.21.1. Drop the `-all.jar` build (the one
containing the embedded `sable-companion` library) into your `mods`
folder. Sable itself is optional.

## Known Limitations

Being upfront about what this mod doesn't do yet, rather than let you
find out the hard way:

- **The outline box now attempts to render on Sable sub-levels too**
  (moving and rotating with the contraption, via the sub-level's own
  pose matrix), but this is the single least-tested piece of the whole
  mod — pure world-rendering math I have no way to visually verify
  without an actual client run. If the box appears in the wrong place
  or orientation specifically on a sub-level, that's the first thing to
  report back. Placement itself and the crosshair icon are unaffected
  either way, since they don't depend on this.
- **The toggle keybind now syncs to the server** (a small custom network
  payload sends your preference over whenever you toggle it), so it
  correctly affects real placement on a remote dedicated server, not
  just your own local crosshair/outline display. One piece of this is
  lower-confidence than the rest of the mod: retrieving the sending
  player from the server-side payload handler (`IPayloadContext.player()`)
  is the standard, documented approach, but I wasn't able to confirm the
  literal method signature against a primary source — if multiplayer
  sync doesn't work, that's the first place to check.
- **Non-slab, non-full-block shapes (stairs, etc.) get a full-block
  outline approximation.** Placement itself works fine for these
  (ordinary vanilla placement logic handles the shape); only the preview
  outline doesn't shrink to match.
- **No vertical slab-combining** (building a new slab against an
  existing half-slab to form a double slab in one placement). Ordinary
  separate placement still works.
- Like the original Bridging Mod this one's inspired by: reach-around
  placement gives a real gameplay advantage, so it's best suited to
  singleplayer or servers where everyone's on board with using it.

## Credits

This mod builds on the work of two other projects:

- [ryanhcode/sable-companion](https://github.com/ryanhcode/sable-companion)
  provides the stable, optional-dependency API used for Sable
  sub-level-awareness.
- [squeeglii/BridgingMod](https://github.com/squeeglii/BridgingMod) (the
  popular Bridging Mod) is the reference this mod's gap-fill search
  algorithm and crosshair indicator are adapted from, used under its MIT
  license. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the
  full attribution and license text.

## AI disclosure

This mod was built collaboratively with Claude (Anthropic's AI) through
an extended series of implementation passes, code review, and real bug
fixes found through actual in-game testing. See
[CHANGELOG.md](CHANGELOG.md) for the full development history, kept
as-is rather than cleaned up because it's genuinely useful context.

## License

MIT — see [LICENSE](LICENSE). Note that the credited third-party code
above retains its own MIT license and attribution requirements; see
THIRD_PARTY_NOTICES.md.
