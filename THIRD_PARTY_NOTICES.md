# Third-Party Notices

This project includes code adapted from third-party open-source projects,
used under their respective licenses. Their full license texts are
reproduced below, as their licenses require.

---

## Bridging Mod (squeeglii/BridgingMod)

`src/main/java/dev/example/sablebridging/GapFillVoxelPath.java` is adapted
from `common/src/main/java/me/cg360/mod/bridging/util/Path.java` in
[squeeglii/BridgingMod](https://github.com/squeeglii/BridgingMod)
(`Path.calculateBresenhamVoxels` / `Path.calculateMissedPoints`),
specifically its 3D Bresenham voxel traversal and diagonal-adjacency
collision check used for the reach-around gap-fill search. See that file's
own doc comment for details on what was changed versus the original.

`computeValidAssistSides` in `BridgingPlacement.java` is adapted from
`common/src/main/java/me/cg360/mod/bridging/raytrace/PathTraversalHandler.java`
in the same repository (`PathTraversalHandler.getValidAssistSides`) — the
view-direction-aware face-priority ranking that decides which of several
possible solid neighbors a gap-fill placement should build against.

`applySlabAssist` in `BridgingPlacement.java` is adapted from the
horizontal-case logic in
`common/src/main/java/me/cg360/mod/bridging/building/Bridge.java`
(`Bridge.handleHorizontalSlabAssist`) — the technique of steering vanilla's
own slab-placement logic into picking a half by setting the placement
face to UP or DOWN, rather than computing the half directly. The vertical
combining case (`handleVerticalSlabAssist`) is not ported — see
`applySlabAssist`'s doc comment for why.

The crosshair indicator sprites at
`src/main/resources/assets/sablebridging/textures/gui/sprites/indicator/`
(`up.png`, `down.png`, `horizontal.png`) are copied unmodified from
`common/src/main/resources/assets/bridgingmod/textures/gui/sprites/indicator/`
in the same repository, along with the up/down/horizontal placement-face
mapping in `BridgingIndicator.java` (adapted from `PlacementAlignment.java`).
The actual rendering hook (`BridgingCrosshairRenderer.java`) is original
code using a different, NeoForge-supported API rather than the original's
mixin approach — see that class's doc comment for why.

```
MIT License

Copyright (c) 2026 Will Scully

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
