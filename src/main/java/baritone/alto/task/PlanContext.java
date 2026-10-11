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

import baritone.alto.planner.Ingredient;
import baritone.alto.planner.PlanStep;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
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

    /** A furnace that was loaded and is cooking while the plan continues with other steps. */
    public record PendingSmelt(BlockPos furnace, StationKind kind, ItemKey output, int expected, long cookTicks) {
    }

    private final List<PendingSmelt> pendingSmelts = new ArrayList<>();

    public void addPendingSmelt(PendingSmelt p) {
        pendingSmelts.add(p);
    }

    public void removePendingSmelt(PendingSmelt p) {
        pendingSmelts.remove(p);
    }

    public List<PendingSmelt> pendingSmelts() {
        return List.copyOf(pendingSmelts);
    }

    /**
     * A pending smelt whose output {@code step} needs (it crafts or smelts with it, or it picks up that furnace),
     * or null if the step can run while the furnaces cook.
     */
    public PendingSmelt pendingNeededBy(PlanStep step) {
        for (PendingSmelt p : pendingSmelts) {
            if (step instanceof PlanStep.Craft c && uses(c.recipe().inputs(), p.output())) {
                return p;
            }
            if (step instanceof PlanStep.Smelt sm && uses(sm.recipe().inputs(), p.output())) {
                return p;
            }
            if (step instanceof PlanStep.Smelt sm && sm.station() == p.kind()) {
                return p; // the same furnace kind would be reused: free it first
            }
            if (step instanceof PlanStep.Station st && st.kind() == p.kind() && st.action() == PlanStep.StationAction.PICK_UP) {
                return p;
            }
        }
        return null;
    }

    private static boolean uses(List<Ingredient> inputs, ItemKey item) {
        for (Ingredient in : inputs) {
            if (in.accepts(item)) {
                return true;
            }
        }
        return false;
    }

    public boolean isReserved(ItemKey item) {
        return reserved.get(item) > 0;
    }
}
