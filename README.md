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

- **Sub-level outline is the least battle-tested part of the mod.** It
  follows the contraption's position and rotation through the sub-level's
  pose matrix, and it's held up in testing so far, but it's newer and
  more fragile than everything else here. If the box ever looks off
  (wrong spot, wrong angle) specifically while riding a sub-level, that's
  the first place to look. Placement and the crosshair icon don't depend
  on this, so they're unaffected either way.
- **Toggle keybind syncs to the server** over a small custom network
  payload, so it actually affects placement on a dedicated server and not
  just your local display. Works in testing; if multiplayer sync ever
  misbehaves, the payload handler's player lookup is the likely suspect.
- **Non-slab, non-full-block shapes (stairs, etc.) get a full-block
  outline approximation.** Placement itself is fine — vanilla handles the
  actual shape — the preview box just doesn't shrink to match.
- **No vertical slab-combining** (stacking a new slab onto an existing
  half-slab to form a double in one placement). Regular separate
  placement still works fine.
- Like the original Bridging Mod this one's inspired by: reach-around
  placement is a real gameplay advantage, so it's best suited to
  singleplayer or servers where everyone's fine with it being in use.

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
