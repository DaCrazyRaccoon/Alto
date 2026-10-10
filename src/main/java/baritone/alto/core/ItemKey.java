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

import java.util.Objects;

/**
 * Identifies a kind of item independent of stack size, without referencing Minecraft classes.
 * <p>
 * {@code id} is the registry id ({@code minecraft:oak_planks}). {@code variant} distinguishes items whose data
 * components matter (a potion type, an enchanted book, a filled shulker box); it is empty for plain items.
 * Pure value class so the planner, the storage index and the script interpreter can be unit tested without
 * bootstrapping the game. {@code baritone.alto.mc.McItems} converts to and from {@code ItemStack}.
 */
public final class ItemKey implements Comparable<ItemKey> {

    public static final ItemKey EMPTY = new ItemKey("minecraft:air", "");

    private final String id;
    private final String variant;

    private ItemKey(String id, String variant) {
        this.id = id;
        this.variant = variant;
    }

    public static ItemKey of(String id) {
        return of(id, "");
    }

    public static ItemKey of(String id, String variant) {
        Objects.requireNonNull(id, "id");
        String normalized = id.indexOf(':') < 0 ? "minecraft:" + id : id;
        return new ItemKey(normalized, variant == null ? "" : variant);
    }

    public String id() {
        return id;
    }

    public String variant() {
        return variant;
    }

    public boolean isEmpty() {
        return id.equals("minecraft:air");
    }

    /** The same item without its variant (e.g. any potion). */
    public ItemKey plain() {
        return variant.isEmpty() ? this : new ItemKey(id, "");
    }

    /** Short display form: {@code oak_planks} for vanilla items, {@code create:shaft} otherwise. */
    public String shortName() {
        String base = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        return variant.isEmpty() ? base : base + "{" + variant + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ItemKey other)) {
            return false;
        }
        return id.equals(other.id) && variant.equals(other.variant);
    }

    @Override
    public int hashCode() {
        return id.hashCode() * 31 + variant.hashCode();
    }

    @Override
    public int compareTo(ItemKey o) {
        int c = id.compareTo(o.id);
        return c != 0 ? c : variant.compareTo(o.variant);
    }

    @Override
    public String toString() {
        return variant.isEmpty() ? id : id + "{" + variant + "}";
    }
}
