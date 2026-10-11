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

package baritone.alto.supply;

import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Feature 10: what a build still needs, what you carry, what your storage has, and what is missing. Pure Java.
 */
public final class ShoppingList {

    /** One material. {@code fromStorage} is what a supply run would fetch; {@code missing} is not anywhere known. */
    public record Line(ItemKey item, int needed, int carried, int inStorage, int fromStorage, int missing, boolean craftable) {
    }

    private final List<Line> lines;

    private ShoppingList(List<Line> lines) {
        this.lines = lines;
    }

    public List<Line> lines() {
        return lines;
    }

    public int totalMissing() {
        return lines.stream().mapToInt(Line::missing).sum();
    }

    /** Items to fetch, most needed first, limited to what fits in {@code freeSlots} inventory slots. */
    public ItemCounts supplyRun(int freeSlots, ToIntFunction<ItemKey> stackSize) {
        ItemCounts out = new ItemCounts();
        int slots = freeSlots;
        List<Line> order = new ArrayList<>(lines);
        order.sort(Comparator.comparingInt(Line::fromStorage).reversed());
        for (Line l : order) {
            if (l.fromStorage() <= 0 || slots <= 0) {
                continue;
            }
            int stack = Math.max(1, stackSize.applyAsInt(l.item()));
            int take = Math.min(l.fromStorage(), slots * stack);
            out.add(l.item(), take);
            slots -= (take + stack - 1) / stack;
        }
        return out;
    }

    public static ShoppingList compute(ItemCounts needed, ItemCounts carried, ItemCounts storage, Predicate<ItemKey> craftable) {
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<ItemKey, Integer> e : needed.asMap().entrySet()) {
            ItemKey item = e.getKey();
            int need = e.getValue();
            int have = Math.min(need, carried.get(item));
            int stored = storage.get(item);
            int fetch = Math.min(need - have, stored);
            int missing = need - have - fetch;
            lines.add(new Line(item, need, carried.get(item), stored, fetch, missing, missing > 0 && craftable.test(item)));
        }
        lines.sort(Comparator.comparingInt(Line::missing).reversed().thenComparing(l -> l.item().id()));
        return new ShoppingList(lines);
    }

    /** Human-readable lines (also written to baritone/alto/shopping-list.txt). */
    public List<String> describe(String title) {
        List<String> out = new ArrayList<>();
        out.add(title);
        for (Line l : lines) {
            StringBuilder s = new StringBuilder();
            s.append(l.needed()).append("x ").append(l.item().shortName()).append(": carrying ").append(l.carried());
            if (l.fromStorage() > 0) {
                s.append(", in storage ").append(l.inStorage());
            }
            if (l.missing() > 0) {
                s.append(", MISSING ").append(l.missing()).append(l.craftable() ? " (craftable)" : "");
            }
            out.add(s.toString());
        }
        return out;
    }
}
