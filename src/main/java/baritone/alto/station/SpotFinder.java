/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.alto.station;

import baritone.api.utils.IPlayerContext;
import baritone.api.utils.RotationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Chooses where to place a station: an empty, liquid-free spot on solid ground, not where the player or another
 * entity stands, not inside the area being worked on, and reachable from where the player stands now (so no walking
 * is needed to place it). Spots two blocks away are preferred over adjacent ones so the station does not box the
 * player in or block the next step of a path.
 */
public final class SpotFinder {

    /** Where to put the block, and the face that is clicked to place it. */
    public record Spot(BlockPos pos, BlockPos support, Direction face) {
    }

    private SpotFinder() {
    }

    /**
     * @param forbidden positions that must stay free (the build area, a farm...); may be {@code p -> false}
     */
    public static Optional<Spot> find(IPlayerContext ctx, Predicate<BlockPos> forbidden) {
        Level level = ctx.world();
        BlockPos feet = ctx.playerFeet();
        double reach = ctx.playerController().getBlockReachDistance();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    candidates.add(feet.offset(dx, dy, dz));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble((BlockPos p) -> preference(feet, p)).thenComparing(BlockPos::asLong));
        for (BlockPos p : candidates) {
            if (!usable(level, ctx, feet, p, forbidden)) {
                continue;
            }
            BlockPos support = p.below();
            Vec3 top = new Vec3(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5);
            if (RotationUtils.reachableOffset(ctx, support, top, reach, false).isPresent()) {
                return Optional.of(new Spot(p, support, Direction.UP));
            }
        }
        return Optional.empty();
    }

    /** Lower is better: horizontal distance 2 first, then 3, then 1; same height as the feet preferred. */
    private static double preference(BlockPos feet, BlockPos p) {
        int dx = Math.abs(p.getX() - feet.getX());
        int dz = Math.abs(p.getZ() - feet.getZ());
        int h = Math.max(dx, dz);
        double ring = h == 2 ? 0 : h == 3 ? 1 : h == 1 ? 2 : 9;
        return ring * 10 + Math.abs(p.getY() - feet.getY()) * 3 + (dx + dz) * 0.1;
    }

    static boolean usable(Level level, IPlayerContext ctx, BlockPos feet, BlockPos p, Predicate<BlockPos> forbidden) {
        if (forbidden.test(p) || p.equals(feet) || p.equals(feet.above()) || p.equals(feet.below())) {
            return false;
        }
        BlockState at = level.getBlockState(p);
        if (!at.isAir() || !at.getFluidState().isEmpty()) {
            return false;
        }
        BlockState below = level.getBlockState(p.below());
        if (!below.isFaceSturdy(level, p.below(), Direction.UP) || !below.getFluidState().isEmpty()) {
            return false;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!level.getFluidState(p.relative(d)).isEmpty()) {
                return false; // keep stations away from water/lava
            }
        }
        if (!level.getFluidState(p.above()).isEmpty()) {
            return false;
        }
        // Never place into any entity (players, mobs, items standing there).
        return level.getEntities(null, new AABB(p)).isEmpty();
    }
}
