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

import baritone.alto.core.ItemCounts;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of planning: ordered steps plus totals. A plan with a non-empty {@link #missing()} list cannot be fully
 * executed; the missing list is the "shopping list".
 *
 * @param targets        what was asked for
 * @param steps          steps in execution order
 * @param fromInventory  items the plan uses that are already in the inventory
 * @param missing        items that could not be obtained in any allowed way
 * @param leftovers      items the plan produces beyond what was asked (craft surplus, empty buckets)
 * @param estimatedTicks rough duration (walking, clicking, smelting, mining)
 * @param cost           internal cost used to compare alternatives
 * @param warnings       notes such as "planner budget exceeded"
 */
public record Plan(ItemCounts targets, List<PlanStep> steps, ItemCounts fromInventory, ItemCounts missing,
                   ItemCounts leftovers, long estimatedTicks, double cost, List<String> warnings) {

    public Plan {
        steps = List.copyOf(steps);
        warnings = List.copyOf(warnings);
    }

    public boolean isComplete() {
        return missing.isEmpty();
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /** Multi-line human description (used by {@code #plan} and the HUD). */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("Plan for " + targets + ": " + steps.size() + " step(s), about " + formatTicks(estimatedTicks));
        if (!fromInventory.isEmpty()) {
            lines.add("  uses from inventory: " + fromInventory);
        }
        for (int i = 0; i < steps.size(); i++) {
            lines.add("  " + (i + 1) + ". " + steps.get(i).describe());
        }
        if (!leftovers.isEmpty()) {
            lines.add("  leftovers: " + leftovers);
        }
        if (!missing.isEmpty()) {
            lines.add("  MISSING (cannot be produced): " + missing);
        }
        for (String w : warnings) {
            lines.add("  note: " + w);
        }
        return lines;
    }

    public static String formatTicks(long ticks) {
        long seconds = Math.max(0, ticks) / 20;
        if (seconds < 60) {
            return seconds + " s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " min " + (seconds % 60) + " s";
        }
        return (minutes / 60) + " h " + (minutes % 60) + " min";
    }
}
