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

package baritone.alto.container;

import baritone.alto.core.ItemKey;

/**
 * Immutable snapshot of one menu slot.
 *
 * @param index         menu slot index (what a click packet uses)
 * @param role          purpose of the slot
 * @param item          item in the slot ({@link ItemKey#EMPTY} if empty)
 * @param count         stack size (may exceed 64 for some modded storage)
 * @param maxStack      how many of this item the slot can hold
 * @param inventoryIndex for player slots: index in the player inventory (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand); -1 otherwise
 */
public record SlotView(int index, SlotRole role, ItemKey item, int count, int maxStack, int inventoryIndex) {

    public static SlotView empty(int index, SlotRole role, int inventoryIndex) {
        return new SlotView(index, role, ItemKey.EMPTY, 0, 64, inventoryIndex);
    }

    public boolean isEmpty() {
        return count <= 0 || item.isEmpty();
    }

    public int space() {
        return isEmpty() ? maxStack : Math.max(0, maxStack - count);
    }

    public boolean holds(ItemKey key) {
        return !isEmpty() && item.equals(key);
    }

    @Override
    public String toString() {
        return "#" + index + " " + role + (isEmpty() ? " empty" : " " + count + "x " + item.shortName());
    }
}
