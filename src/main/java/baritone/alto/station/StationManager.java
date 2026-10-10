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

import baritone.alto.Alto;
import baritone.alto.planner.StationKind;
import baritone.api.BaritoneAPI;
import baritone.api.utils.IPlayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Finds usable stations (crafting tables, furnaces) and remembers which ones Alto placed itself, because only those
 * may be picked up again. Searches use Baritone's chunk scanner over loaded chunks (a live scan, not the long-range
 * cache, which does not track crafting tables and had height bugs; research.md §2 / upstream #3271).
 */
public final class StationManager {

    private final Alto alto;
    /** Stations placed by Alto in this session: "dimension|x,y,z" -> kind. */
    private final Map<String, StationKind> placedByAlto = new HashMap<>();

    public StationManager(Alto alto) {
        this.alto = alto;
    }

    public static Block blockFor(StationKind kind) {
        return switch (kind) {
            case CRAFTING_TABLE -> Blocks.CRAFTING_TABLE;
            case FURNACE -> Blocks.FURNACE;
            case BLAST_FURNACE -> Blocks.BLAST_FURNACE;
            case SMOKER -> Blocks.SMOKER;
            case INVENTORY_GRID -> Blocks.AIR;
        };
    }

    /** Nearest station of this kind within {@code radius} blocks that can be opened (has an open face). */
    public Optional<BlockPos> findNearest(StationKind kind, double radius, Predicate<BlockPos> extra) {
        List<BlockPos> found = scan(kind, radius);
        IPlayerContext ctx = alto.ctx();
        BlockPos feet = ctx.playerFeet();
        return found.stream()
                .filter(p -> p.distSqr(feet) <= radius * radius)
                .filter(p -> hasOpenFace(ctx.world(), p))
                .filter(extra)
                .min(Comparator.comparingDouble(p -> p.distSqr(feet)));
    }

    /** All stations of this kind in loaded chunks around the player (up to 64). */
    public List<BlockPos> scan(StationKind kind, double radius) {
        Block block = blockFor(kind);
        if (block == Blocks.AIR) {
            return List.of();
        }
        IPlayerContext ctx = alto.ctx();
        int chunks = Math.max(1, (int) Math.ceil(radius / 16.0) + 1);
        return new ArrayList<>(BaritoneAPI.getProvider().getWorldScanner().scanChunkRadius(ctx, List.of(block), 64, -1, chunks));
    }

    public static boolean hasOpenFace(Level level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState n = level.getBlockState(pos.relative(d));
            if (!n.isSolidRender(level, pos.relative(d))) {
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------------------------------- placed-by-Alto registry

    private String key(BlockPos pos) {
        Level level = alto.ctx().world();
        return (level == null ? "?" : level.dimension().location().toString()) + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public void markPlaced(StationKind kind, BlockPos pos) {
        placedByAlto.put(key(pos), kind);
    }

    public boolean placedByAlto(BlockPos pos) {
        return placedByAlto.containsKey(key(pos));
    }

    public void forget(BlockPos pos) {
        placedByAlto.remove(key(pos));
    }

    public int placedCount() {
        return placedByAlto.size();
    }
}
