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
import baritone.alto.core.ItemKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the planner knows about the world when it plans: a snapshot, never a live view.
 * Built by {@code WorldModelBuilder} from the player inventory, the storage index and the station scan,
 * or directly by tests.
 */
public final class WorldModel {

    /**
     * A known container (chest, barrel, ender chest...) and what it held when last seen.
     *
     * @param id       stable id used by the executor ({@code minecraft:overworld|10,64,-3})
     * @param label    human label ({@code chest at 10 64 -3})
     * @param distance straight-line distance from the player in blocks (travel estimate)
     */
    public record StorageSource(String id, String label, double distance, ItemCounts contents) {
    }

    /**
     * A station block already in the world.
     */
    public record StationSite(StationKind kind, String id, double distance) {
    }

    /**
     * How an item can be obtained by mining.
     *
     * @param blocks      block ids that drop the item ({@code minecraft:oak_log})
     * @param ticksPerItem rough mining + walking estimate per item
     */
    public record GatherSource(List<String> blocks, double ticksPerItem) {
    }

    public final ItemCounts inventory;
    public final ItemCounts inShulkers;
    public final List<StorageSource> storages;
    public final List<StationSite> stations;
    public final Map<ItemKey, GatherSource> gatherable;
    /** Fuels the planner may burn, in order of preference, with burn time in ticks per item. */
    public final Map<ItemKey, Integer> fuels;
    /** Amounts that must stay untouched in the inventory (keep-list, blocks the current build needs). */
    public final ItemCounts reserved;
    public final boolean hasPickaxe;

    private WorldModel(Builder b) {
        this.inventory = b.inventory.copy();
        this.inShulkers = b.inShulkers.copy();
        List<StorageSource> s = new ArrayList<>(b.storages);
        s.sort(Comparator.comparingDouble(StorageSource::distance).thenComparing(StorageSource::id));
        this.storages = List.copyOf(s);
        List<StationSite> st = new ArrayList<>(b.stations);
        st.sort(Comparator.comparingDouble(StationSite::distance).thenComparing(StationSite::id));
        this.stations = List.copyOf(st);
        this.gatherable = Map.copyOf(b.gatherable);
        this.fuels = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(b.fuels));
        this.reserved = b.reserved.copy();
        this.hasPickaxe = b.hasPickaxe;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Inventory minus reserved amounts. */
    public ItemCounts usableInventory() {
        ItemCounts usable = inventory.copy();
        reserved.forEach(usable::take);
        return usable;
    }

    public static final class Builder {
        private final ItemCounts inventory = new ItemCounts();
        private final ItemCounts inShulkers = new ItemCounts();
        private final List<StorageSource> storages = new ArrayList<>();
        private final List<StationSite> stations = new ArrayList<>();
        private final Map<ItemKey, GatherSource> gatherable = new LinkedHashMap<>();
        private final Map<ItemKey, Integer> fuels = new LinkedHashMap<>();
        private final ItemCounts reserved = new ItemCounts();
        private boolean hasPickaxe = true;

        public Builder inventory(String id, int count) {
            inventory.add(ItemKey.of(id), count);
            return this;
        }

        public Builder inventory(ItemCounts counts) {
            inventory.addAll(counts);
            return this;
        }

        public Builder inShulker(String id, int count) {
            inShulkers.add(ItemKey.of(id), count);
            return this;
        }

        public Builder inShulkers(ItemCounts counts) {
            inShulkers.addAll(counts);
            return this;
        }

        public Builder storage(String id, String label, double distance, ItemCounts contents) {
            storages.add(new StorageSource(id, label, distance, contents.copy()));
            return this;
        }

        public Builder station(StationKind kind, String id, double distance) {
            stations.add(new StationSite(kind, id, distance));
            return this;
        }

        public Builder gatherable(String item, double ticksPerItem, String... blocks) {
            gatherable.put(ItemKey.of(item), new GatherSource(List.of(blocks), ticksPerItem));
            return this;
        }

        public Builder fuel(String item, int burnTicks) {
            fuels.put(ItemKey.of(item), burnTicks);
            return this;
        }

        public Builder reserve(String id, int count) {
            reserved.add(ItemKey.of(id), count);
            return this;
        }

        public Builder reserve(ItemCounts counts) {
            reserved.addAll(counts);
            return this;
        }

        public Builder hasPickaxe(boolean value) {
            hasPickaxe = value;
            return this;
        }

        public WorldModel build() {
            return new WorldModel(this);
        }
    }
}
