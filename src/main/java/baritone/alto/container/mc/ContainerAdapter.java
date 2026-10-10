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

package baritone.alto.container.mc;

import baritone.alto.container.SlotRole;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Teaches Alto how to read one kind of container menu. Small on purpose: most modded containers need only
 * {@link #matches} and {@link #roleOf}. Register with {@link ContainerAdapters#register}; see
 * docs/alto/DEVELOPER.md "Adding support for another mod".
 * <p>
 * Alto never uses screen coordinates: it reads {@code menu.slots} and clicks by slot index through
 * {@code MultiPlayerGameMode.handleInventoryMouseClick}, so scrolling or searching screens work as long as their
 * slots exist in the menu (they do for vanilla-style menus, even when scrolled out of view).
 */
public interface ContainerAdapter {

    /** Stable id used in logs and in the storage index ({@code vanilla:chest}, {@code sophisticatedstorage:storage}). */
    String id();

    /** When several adapters match a menu, the highest priority wins. Generic = 0, vanilla = 10, mods = 20. */
    int priority();

    boolean matches(AbstractContainerMenu menu);

    /**
     * Role of a slot. Return {@code null} to fall back to the generic rule (player slots by inventory index,
     * result/fuel slots by class, plain slots as storage, unknown subclasses as {@link SlotRole#OTHER}).
     */
    SlotRole roleOf(AbstractContainerMenu menu, Slot slot, int index, Inventory playerInventory);

    /** Real count of a slot (modded storage can hold more than the vanilla stack size). */
    default int countOf(Slot slot) {
        return slot.getItem().getCount();
    }

    /** How many of this item the slot can hold. */
    default int maxStackOf(Slot slot, ItemStack stack) {
        return slot.getMaxStackSize(stack);
    }

    /** May the STORAGE slots of this menu be recorded in the storage index? */
    default boolean indexable(AbstractContainerMenu menu) {
        return true;
    }

    /** May Alto put items into this container? (False for per-player loot containers, for example.) */
    default boolean depositAllowed(AbstractContainerMenu menu) {
        return true;
    }
}
