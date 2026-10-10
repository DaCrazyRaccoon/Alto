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

/**
 * What a menu slot is for, independent of the menu class. Adapters assign roles; generic detection covers most menus.
 */
public enum SlotRole {
    /** Player main inventory (inventory slots 9-35). */
    PLAYER_MAIN(true),
    /** Player hotbar (inventory slots 0-8). */
    PLAYER_HOTBAR(true),
    PLAYER_ARMOR(true),
    PLAYER_OFFHAND(true),
    /** A normal storage slot of the opened block (chest, barrel, shulker, hopper, ...). */
    STORAGE(false),
    /** Crafting grid input (2x2 or 3x3). */
    CRAFT_GRID(false),
    /** Crafting result: take-only. */
    CRAFT_RESULT(false),
    FURNACE_INPUT(false),
    FURNACE_FUEL(false),
    /** Furnace result: take-only. */
    FURNACE_RESULT(false),
    /** Any other take-only slot. */
    OUTPUT_ONLY(false),
    /** A "ghost" slot that copies an item without consuming it (filters). */
    GHOST(false),
    /** Upgrade/settings slots of modded storage: never deposit into or take from them automatically. */
    UPGRADE(false),
    /** Unknown purpose: Alto never touches it. */
    OTHER(false);

    public final boolean player;

    SlotRole(boolean player) {
        this.player = player;
    }

    public boolean isOutput() {
        return this == CRAFT_RESULT || this == FURNACE_RESULT || this == OUTPUT_ONLY;
    }

    /** Slots that hold the opened block's own items (what the storage index records). */
    public boolean isContainerStorage() {
        return this == STORAGE;
    }
}
