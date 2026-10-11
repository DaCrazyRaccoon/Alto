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

package baritone.alto.storage;

import baritone.alto.core.ItemCounts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What Alto last saw in one container. Pure data, persisted by {@link StorageIndex}.
 */
public final class ContainerRecord {

    /** Position of a container block; {@link #ENDER} is the player's ender chest inventory (any ender chest block). */
    public record Key(String dimension, int x, int y, int z) {
        public static final Key ENDER = new Key("*", 0, Integer.MIN_VALUE, 0);

        public String id() {
            return this == ENDER || y == Integer.MIN_VALUE ? "enderchest" : dimension + "|" + x + "," + y + "," + z;
        }

        public static Key parse(String id) {
            if (id.equals("enderchest")) {
                return ENDER;
            }
            int bar = id.lastIndexOf('|');
            String[] c = id.substring(bar + 1).split(",");
            return new Key(id.substring(0, bar), Integer.parseInt(c[0].trim()), Integer.parseInt(c[1].trim()), Integer.parseInt(c[2].trim()));
        }

        public boolean isEnder() {
            return y == Integer.MIN_VALUE;
        }

        public double distanceTo(String dim, double px, double py, double pz) {
            if (isEnder()) {
                return 0; // usable from any ender chest; the executor still has to walk to one
            }
            if (!dimension.equals(dim)) {
                return Double.POSITIVE_INFINITY;
            }
            double dx = x + 0.5 - px;
            double dy = y + 0.5 - py;
            double dz = z + 0.5 - pz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        @Override
        public String toString() {
            return id();
        }
    }

    public Key key;
    /** Other half of a double chest (both halves map to one record). */
    public Key partner;
    public String blockId = "";
    public String adapter = "";
    public ItemCounts contents = new ItemCounts();
    /** Items inside shulker boxes stored in this container (not directly usable; counted for information). */
    public ItemCounts nested = new ItemCounts();
    public int totalSlots;
    public int freeSlots;
    public long lastSeenMillis;
    public boolean depositAllowed = true;
    /** Free text the user may set, e.g. "ores". */
    public String label = "";

    public ContainerRecord() {
    }

    public ContainerRecord(Key key) {
        this.key = key;
    }

    public List<Key> keys() {
        List<Key> k = new ArrayList<>();
        k.add(key);
        if (partner != null) {
            k.add(partner);
        }
        return k;
    }

    public String describe() {
        String where = key.isEnder() ? "ender chest" : shortBlock() + " at " + key.x() + " " + key.y() + " " + key.z();
        return label.isEmpty() ? where : where + " (" + label + ")";
    }

    public String shortBlock() {
        String b = blockId.startsWith("minecraft:") ? blockId.substring(10) : blockId;
        return b.isEmpty() ? "container" : b.toLowerCase(Locale.ROOT);
    }

    public double ageSeconds(long now) {
        return (now - lastSeenMillis) / 1000.0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ContainerRecord r && Objects.equals(key, r.key) && Objects.equals(partner, r.partner)
                && Objects.equals(blockId, r.blockId) && Objects.equals(contents, r.contents) && Objects.equals(nested, r.nested)
                && totalSlots == r.totalSlots && freeSlots == r.freeSlots && lastSeenMillis == r.lastSeenMillis
                && depositAllowed == r.depositAllowed && Objects.equals(label, r.label) && Objects.equals(adapter, r.adapter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, blockId, lastSeenMillis);
    }
}
