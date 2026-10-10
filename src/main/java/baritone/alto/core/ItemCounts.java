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

package baritone.alto.core;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * A mutable multiset of items (item to count). Counts are never negative; zero entries are removed.
 * Ordered by item id so output (plans, shopping lists, files) is deterministic.
 */
public final class ItemCounts {

    private final TreeMap<ItemKey, Integer> counts = new TreeMap<>();

    public ItemCounts() {
    }

    public ItemCounts(ItemCounts other) {
        counts.putAll(other.counts);
    }

    public static ItemCounts of(Object... idThenCount) {
        ItemCounts c = new ItemCounts();
        for (int i = 0; i + 1 < idThenCount.length; i += 2) {
            ItemKey key = idThenCount[i] instanceof ItemKey k ? k : ItemKey.of(idThenCount[i].toString());
            c.add(key, ((Number) idThenCount[i + 1]).intValue());
        }
        return c;
    }

    public int get(ItemKey item) {
        return counts.getOrDefault(item, 0);
    }

    public void add(ItemKey item, int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("negative add " + amount);
        }
        if (amount == 0 || item.isEmpty()) {
            return;
        }
        counts.merge(item, amount, Integer::sum);
    }

    public void addAll(ItemCounts other) {
        other.counts.forEach(this::add);
    }

    /**
     * Removes up to {@code amount} and returns how many were actually removed.
     */
    public int take(ItemKey item, int amount) {
        int have = get(item);
        int taken = Math.min(have, Math.max(0, amount));
        if (taken == 0) {
            return 0;
        }
        if (have - taken == 0) {
            counts.remove(item);
        } else {
            counts.put(item, have - taken);
        }
        return taken;
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    public int total() {
        int t = 0;
        for (int v : counts.values()) {
            t += v;
        }
        return t;
    }

    public Map<ItemKey, Integer> asMap() {
        return Collections.unmodifiableMap(counts);
    }

    public void forEach(BiConsumer<ItemKey, Integer> action) {
        counts.forEach(action);
    }

    public ItemCounts copy() {
        return new ItemCounts(this);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ItemCounts other && counts.equals(other.counts);
    }

    @Override
    public int hashCode() {
        return counts.hashCode();
    }

    /** {@code 3x oak_log, 2x stick} */
    @Override
    public String toString() {
        return counts.entrySet().stream()
                .map(e -> e.getValue() + "x " + e.getKey().shortName())
                .collect(Collectors.joining(", "));
    }
}
