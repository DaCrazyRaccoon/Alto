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

package baritone.alto.planner;

import baritone.alto.core.ItemKey;

import java.util.List;

/**
 * One step of a {@link Plan}, in execution order. Steps are immutable descriptions; the executor
 * ({@code baritone.alto.task}) turns each into a task that re-checks the live world before acting.
 */
public sealed interface PlanStep {

    String describe();

    /** Items this step adds to the inventory when it succeeds (used by the HUD and by tests). */
    ItemKey producedItem();

    int producedCount();

    /** Take items out of a shulker box carried in the inventory. */
    record UnpackShulker(ItemKey item, int count) implements PlanStep {
        public String describe() {
            return "Take " + count + "x " + item.shortName() + " out of a carried shulker box";
        }

        public ItemKey producedItem() {
            return item;
        }

        public int producedCount() {
            return count;
        }
    }

    /** Walk to a known container and take items. */
    record Fetch(String storageId, String label, ItemKey item, int count, double distance) implements PlanStep {
        public String describe() {
            return "Fetch " + count + "x " + item.shortName() + " from " + label;
        }

        public ItemKey producedItem() {
            return item;
        }

        public int producedCount() {
            return count;
        }
    }

    /**
     * Craft {@code crafts} times. {@code count} is what the caller needed (the rest is surplus).
     */
    record Craft(RecipeDef recipe, int crafts, StationKind station) implements PlanStep {
        public String describe() {
            return "Craft " + (crafts * recipe.outputCount()) + "x " + recipe.output().shortName()
                    + " (" + crafts + (crafts == 1 ? " craft" : " crafts") + ", " + station.displayName + ")";
        }

        public ItemKey producedItem() {
            return recipe.output();
        }

        public int producedCount() {
            return crafts * recipe.outputCount();
        }
    }

    /** Smelt/blast/smoke {@code operations} items with {@code fuelCount} of {@code fuel}. */
    record Smelt(RecipeDef recipe, int operations, StationKind station, ItemKey fuel, int fuelCount) implements PlanStep {
        public String describe() {
            return "Smelt " + operations + "x " + recipe.inputs().get(0).displayLabel() + " -> "
                    + recipe.output().shortName() + " (" + station.displayName + ", "
                    + (fuel.isEmpty() ? "no fuel needed" : fuelCount + "x " + fuel.shortName()) + ")";
        }

        public ItemKey producedItem() {
            return recipe.output();
        }

        public int producedCount() {
            return operations * recipe.outputCount();
        }
    }

    /** Mine blocks that drop the item. */
    record Gather(ItemKey item, int count, List<String> blocks) implements PlanStep {
        public String describe() {
            return "Mine " + count + "x " + item.shortName() + " (from " + String.join(", ", blocks) + ")";
        }

        public ItemKey producedItem() {
            return item;
        }

        public int producedCount() {
            return count;
        }
    }

    enum StationAction {
        /** Use a station that already exists in the world. */
        USE_EXISTING,
        /** Place one from the inventory. */
        PLACE,
        /** Empty it, break it and collect the drop (only stations Alto placed). */
        PICK_UP
    }

    record Station(StationKind kind, StationAction action, String siteId) implements PlanStep {
        public String describe() {
            return switch (action) {
                case USE_EXISTING -> "Use the " + kind.displayName + (siteId == null ? "" : " at " + siteId);
                case PLACE -> "Place a " + kind.displayName;
                case PICK_UP -> "Pick up the " + kind.displayName;
            };
        }

        public ItemKey producedItem() {
            return action == StationAction.PICK_UP ? kind.item() : ItemKey.EMPTY;
        }

        public int producedCount() {
            return action == StationAction.PICK_UP ? 1 : 0;
        }
    }
}
