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

package baritone.alto.task;

import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import baritone.alto.planner.StationKind;
import net.minecraft.core.BlockPos;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * State shared by the steps of one plan: which station to use, what must not be consumed, which area to keep free.
 */
public final class PlanContext {

    private final Map<StationKind, BlockPos> stations = new EnumMap<>(StationKind.class);
    private final Map<StationKind, BlockPos> placed = new EnumMap<>(StationKind.class);
    /** Items that must stay in the inventory (keep-list, blocks the build is placing). */
    public final ItemCounts reserved;
    /** Positions that must stay free (the build area). */
    public final Predicate<BlockPos> forbiddenArea;
    /** Items to move to the hotbar at the end (the builder places from the hotbar). */
    public final ItemCounts deliverToHotbar = new ItemCounts();

    public PlanContext(ItemCounts reserved, Predicate<BlockPos> forbiddenArea) {
        this.reserved = reserved == null ? new ItemCounts() : reserved.copy();
        this.forbiddenArea = forbiddenArea == null ? p -> false : forbiddenArea;
    }

    public void useStation(StationKind kind, BlockPos pos) {
        stations.put(kind, pos);
    }

    public BlockPos station(StationKind kind) {
        return stations.get(kind);
    }

    public void placedStation(StationKind kind, BlockPos pos) {
        placed.put(kind, pos);
        stations.put(kind, pos);
    }

    public BlockPos placed(StationKind kind) {
        return placed.get(kind);
    }

    public void forgetStation(StationKind kind) {
        stations.remove(kind);
        placed.remove(kind);
    }

    public boolean isReserved(ItemKey item) {
        return reserved.get(item) > 0;
    }
}
