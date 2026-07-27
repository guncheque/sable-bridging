package dev.example.sablebridging;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact 3D voxel line traversal for the gap-fill search.
 *
 * Adapted from squeeglii/BridgingMod (https://github.com/squeeglii/BridgingMod),
 * specifically Path.calculateBresenhamVoxels / calculateMissedPoints, used
 * here under that project's MIT license (Copyright (c) 2026 Will Scully —
 * full license text in THIRD_PARTY_NOTICES.md at the project root, as MIT
 * requires). The traversal algorithm and the collision math in
 * calculateMissedPoints are otherwise unchanged from the original; the only
 * adaptation is dropping the original mod's config system (a toggle for
 * whether to catch diagonal misses at all, plus a "snap strength" dial on
 * the collision box size) in favor of fixed defaults matching its most
 * permissive setting -- this project has no config system yet.
 *
 * WHY THIS REPLACES A SIMPLE RAYMARCH: a fixed-step raymarch (what this
 * project used before) trades precision against wasted iterations via a
 * step-size constant, and can in principle skip a thin gap if that step is
 * too coarse. A Bresenham voxel walk instead visits precisely the sequence
 * of blocks a ray geometrically passes through, with no step size at all —
 * and calculateMissedPoints below catches the one case plain Bresenham
 * still misses: a ray that only clips a shared edge or corner between two
 * blocks without ever landing inside either one.
 */
final class GapFillVoxelPath {

    private static final double NEAR_ZERO = 0.01D;
    private static final Vec3 CUBE_EXTENT = new Vec3(0.5, 0.5, 0.5);
    // Now a real config value (BridgingConfig.SNAP_STRENGTH) instead of a
    // fixed constant -- this WAS a user-configurable "bridging snap
    // strength" dial upstream too, so exposing it here just restores that.

    // SAFETY CAP, not part of the original algorithm: a legitimate search
    // at the default ~4.5 block reach needs well under 100 total voxels
    // even accounting for missed-point insertions. This is generous
    // headroom above that, existing purely to guarantee termination if
    // startPos/endPos are ever absurdly far apart -- which normal usage
    // should never produce, but a transiently invalid Sable sub-level pose
    // (e.g. during the exact moment a contraption assembles, a real
    // reported freeze this was added in response to) plausibly could:
    // Bresenham still terminates mathematically in that case, just
    // potentially after millions of iterations, which is indistinguishable
    // from a genuine hang to whoever's playing. Hitting this cap means
    // "something fed this function garbage input," not "the search found
    // a very long path" -- reaching it returns whatever's been found so
    // far rather than the true answer, which is an acceptable tradeoff
    // against actually freezing the client.
    private static final int MAX_STEPS = 512;

    private GapFillVoxelPath() {}

    /**
     * @return every block position the straight line from startPos to
     *         endPos passes through, in traversal order. Capped at
     *         MAX_STEPS iterations -- see that constant's doc comment.
     */
    static List<BlockPos> calculateVoxels(BlockPos startPos, BlockPos endPos) {
        List<BlockPos> points = new ArrayList<>();
        points.add(startPos);

        BlockPos delta = endPos.subtract(startPos);

        int dx = Math.abs(delta.getX());
        int dy = Math.abs(delta.getY());
        int dz = Math.abs(delta.getZ());

        int xStep = delta.getX() > 0 ? 1 : -1;
        int yStep = delta.getY() > 0 ? 1 : -1;
        int zStep = delta.getZ() > 0 ? 1 : -1;

        Vec3 workingVec = new Vec3(startPos.getX(), startPos.getY(), startPos.getZ());
        Vec3 targetVec = new Vec3(endPos.getX(), endPos.getY(), endPos.getZ());

        // Driving axis = whichever of dx/dy/dz is largest; standard 3D
        // Bresenham, one branch per driving axis.
        if (dx >= dy && dx >= dz) {
            int point1 = 2 * dy - dx;
            int point2 = 2 * dz - dx;

            int steps = 0;
            while (Math.abs(workingVec.x() - targetVec.x()) > NEAR_ZERO) {
                if (++steps > MAX_STEPS) {
                    return points;
                }
                workingVec = workingVec.add(xStep, 0, 0);

                if (point1 >= 0) {
                    workingVec = workingVec.add(0, yStep, 0);
                    point1 -= 2 * dx;
                }
                if (point2 >= 0) {
                    workingVec = workingVec.add(0, 0, zStep);
                    point2 -= 2 * dx;
                }
                point1 += 2 * dy;
                point2 += 2 * dz;

                BlockPos newPoint = BlockPos.containing(workingVec);
                points.addAll(calculateMissedPoints(points, newPoint, startPos, endPos));
                points.add(newPoint);
            }
            return points;
        }

        if (dy >= dx && dy >= dz) {
            int point1 = 2 * dx - dy;
            int point2 = 2 * dz - dy;

            int steps = 0;
            while (Math.abs(workingVec.y() - targetVec.y()) > NEAR_ZERO) {
                if (++steps > MAX_STEPS) {
                    return points;
                }
                workingVec = workingVec.add(0, yStep, 0);

                if (point1 >= 0) {
                    workingVec = workingVec.add(xStep, 0, 0);
                    point1 -= 2 * dy;
                }
                if (point2 >= 0) {
                    workingVec = workingVec.add(0, 0, zStep);
                    point2 -= 2 * dy;
                }
                point1 += 2 * dx;
                point2 += 2 * dz;

                BlockPos newPoint = BlockPos.containing(workingVec);
                points.addAll(calculateMissedPoints(points, newPoint, startPos, endPos));
                points.add(newPoint);
            }
            return points;
        }

        int point1 = 2 * dy - dz;
        int point2 = 2 * dx - dz;

        int steps = 0;
        while (Math.abs(workingVec.z() - targetVec.z()) > NEAR_ZERO) {
            if (++steps > MAX_STEPS) {
                return points;
            }
            workingVec = workingVec.add(0, 0, zStep);

            if (point1 >= 0) {
                workingVec = workingVec.add(0, yStep, 0);
                point1 -= 2 * dz;
            }
            if (point2 >= 0) {
                workingVec = workingVec.add(xStep, 0, 0);
                point2 -= 2 * dz;
            }
            point1 += 2 * dy;
            point2 += 2 * dx;

            BlockPos newPoint = BlockPos.containing(workingVec);
            points.addAll(calculateMissedPoints(points, newPoint, startPos, endPos));
            points.add(newPoint);
        }
        return points;
    }

    /**
     * Catches the one case plain Bresenham still misses: consecutive
     * points sharing only an edge or corner (not a face), where the ray
     * may have clipped a diagonal neighbor without ever landing in it.
     *
     * PERFORMANCE NOTE: this used to filter candidates via
     * reviewPositions.stream().filter(pos -> {...}).toList(). Functionally
     * identical to the plain loop below, but a capturing lambda (it closes
     * over lineStart/lineEnd/newExtent) plus the Stream/Spliterator/toList
     * machinery allocates meaningfully more per call than a loop does.
     * This method runs on every Bresenham step where the ray clips a
     * diagonal neighbor -- rare on flat terrain, but common on irregular
     * geometry like a ship hull, which made it a real, confirmed source of
     * lag specifically near Sable sub-levels once this ran every tick.
     */
    private static List<BlockPos> calculateMissedPoints(List<BlockPos> points, BlockPos newPoint, BlockPos lineStart, BlockPos lineEnd) {
        if (points.isEmpty()) {
            return List.of();
        }

        BlockPos lastPoint = points.get(points.size() - 1);
        BlockPos pointDelta = newPoint.subtract(lastPoint);
        int diff = newPoint.distManhattan(lastPoint);

        if (diff < 0 || diff > 3) {
            throw new IllegalArgumentException("The last point and the new point share no common boundaries");
        }
        // Shared face, or the same point: the line stays contained between them.
        if (diff == 1 || diff == 0) {
            return List.of();
        }

        BlockPos[] checkDirections = (diff == 2)
                // Shared edge: one of pointDelta's components is zero.
                ? new BlockPos[] {
                        new BlockPos(pointDelta.getX(), 0, 0),
                        new BlockPos(0, pointDelta.getY(), 0),
                        new BlockPos(0, 0, pointDelta.getZ())
                }
                // Shared corner: harder to visualize in 3D, but the same
                // idea — a few more candidate positions than the edge case.
                : new BlockPos[] {
                        new BlockPos(pointDelta.getX(), 0, 0),
                        new BlockPos(0, pointDelta.getY(), 0),
                        new BlockPos(0, 0, pointDelta.getZ()),
                        new BlockPos(pointDelta.getX(), pointDelta.getY(), 0),
                        new BlockPos(pointDelta.getX(), 0, pointDelta.getZ()),
                        new BlockPos(0, pointDelta.getY(), pointDelta.getZ())
                };

        // Swept-line vs. box separating-axis test — unchanged from the
        // original. Reference: https://3dkingdoms.com/weekly/weekly.php?a=21
        final Vec3 newExtent = CUBE_EXTENT.scale(BridgingConfig.SNAP_STRENGTH.get());

        List<BlockPos> reviewPositions = new ArrayList<>(checkDirections.length);
        for (BlockPos direction : checkDirections) {
            if (direction.equals(BlockPos.ZERO)) {
                continue;
            }
            BlockPos candidate = lastPoint.offset(direction);
            if (intersectsSweptLine(candidate, lineStart, lineEnd, newExtent)) {
                reviewPositions.add(candidate);
            }
        }
        return reviewPositions;
    }

    /** The actual separating-axis test, factored out so calculateMissedPoints can call it in a plain loop instead of a capturing-lambda filter. */
    private static boolean intersectsSweptLine(BlockPos pos, BlockPos lineStart, BlockPos lineEnd, Vec3 newExtent) {
        Vec3 boxSpaceTransform = Vec3.atLowerCornerOf(pos);
        Vec3 lineStartD = Vec3.atLowerCornerOf(lineStart).subtract(boxSpaceTransform);
        Vec3 lineEndD = Vec3.atLowerCornerOf(lineEnd).subtract(boxSpaceTransform);
        Vec3 lineMid = lineStartD.add(lineEndD).scale(0.5);
        Vec3 line = lineStartD.subtract(lineMid);
        Vec3 lineExt = new Vec3(Math.abs(line.x), Math.abs(line.y), Math.abs(line.z));

        if (Math.abs(lineMid.x) > newExtent.x + lineExt.x) return false;
        if (Math.abs(lineMid.y) > newExtent.y + lineExt.y) return false;
        if (Math.abs(lineMid.z) > newExtent.z + lineExt.z) return false;

        if (Math.abs(lineMid.y * line.z - lineMid.z * line.y) > (newExtent.y * lineExt.z + newExtent.z * lineExt.y)) return false;
        if (Math.abs(lineMid.x * line.z - lineMid.z * line.x) > (newExtent.x * lineExt.z + newExtent.z * lineExt.x)) return false;
        if (Math.abs(lineMid.x * line.y - lineMid.y * line.x) > (newExtent.x * lineExt.y + newExtent.y * lineExt.x)) return false;

        return true;
    }
}
