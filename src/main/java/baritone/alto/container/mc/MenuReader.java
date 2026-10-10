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

import baritone.alto.container.MenuView;
import baritone.alto.container.SlotRole;
import baritone.alto.container.SlotView;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceFuelSlot;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.ShulkerBoxSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a live {@link AbstractContainerMenu} into an immutable {@link MenuView}, assigning slot roles through the
 * matching {@link ContainerAdapter}. Client thread only.
 */
public final class MenuReader {

    private static final String SLOT_ITEM_HANDLER = "net.neoforged.neoforge.items.SlotItemHandler";

    private MenuReader() {
    }

    public static MenuView read(AbstractContainerMenu menu, Inventory playerInventory) {
        ContainerAdapter adapter = ContainerAdapters.forMenu(menu);
        List<SlotView> slots = new ArrayList<>(menu.slots.size());
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            SlotRole role = null;
            try {
                role = adapter.roleOf(menu, slot, i, playerInventory);
            } catch (Throwable ignored) {
                // fall back to the generic rule
            }
            if (role == null) {
                role = genericRole(menu, slot, i, playerInventory);
            }
            int invIndex = slot.container == playerInventory ? slot.getContainerSlot() : -1;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                slots.add(SlotView.empty(i, role, invIndex));
            } else {
                int count;
                int max;
                try {
                    count = adapter.countOf(slot);
                    max = adapter.maxStackOf(slot, stack);
                } catch (Throwable t) {
                    count = stack.getCount();
                    max = slot.getMaxStackSize(stack);
                }
                slots.add(new SlotView(i, role, McItems.key(stack), count, Math.max(1, max), invIndex));
            }
        }
        ItemStack carried = menu.getCarried();
        return new MenuView(menu.containerId, menu.getStateId(), menuTypeId(menu), adapter.id(), slots,
                carried.isEmpty() ? ItemKey.EMPTY : McItems.key(carried), carried.isEmpty() ? 0 : carried.getCount());
    }

    /** Registry id of the menu type, {@code minecraft:player} for the player inventory. */
    public static String menuTypeId(AbstractContainerMenu menu) {
        if (menu instanceof InventoryMenu) {
            return "minecraft:player";
        }
        try {
            ResourceLocation id = BuiltInRegistries.MENU.getKey(menu.getType());
            return id == null ? menu.getClass().getName() : id.toString();
        } catch (UnsupportedOperationException e) {
            return menu.getClass().getName();
        }
    }

    /** The generic slot rule used when no adapter decides. */
    public static SlotRole genericRole(AbstractContainerMenu menu, Slot slot, int index, Inventory playerInventory) {
        if (slot.container == playerInventory) {
            int ci = slot.getContainerSlot();
            if (ci >= 0 && ci < 9) {
                return SlotRole.PLAYER_HOTBAR;
            }
            if (ci < 36) {
                return SlotRole.PLAYER_MAIN;
            }
            if (ci < 40) {
                return SlotRole.PLAYER_ARMOR;
            }
            return ci == 40 ? SlotRole.PLAYER_OFFHAND : SlotRole.OTHER;
        }
        if (slot instanceof ResultSlot) {
            return SlotRole.CRAFT_RESULT;
        }
        if (slot instanceof FurnaceResultSlot) {
            return SlotRole.FURNACE_RESULT;
        }
        if (slot instanceof FurnaceFuelSlot) {
            return SlotRole.FURNACE_FUEL;
        }
        if (menu instanceof AbstractFurnaceMenu && index == 0) {
            return SlotRole.FURNACE_INPUT;
        }
        Container c = slot.container;
        if (c instanceof CraftingContainer || (menu instanceof CraftingMenu || menu instanceof InventoryMenu) && index >= 1 && index <= 9) {
            return SlotRole.CRAFT_GRID;
        }
        if (c instanceof ResultContainer) {
            return SlotRole.OUTPUT_ONLY;
        }
        if (slot.getClass() == Slot.class || slot instanceof ShulkerBoxSlot || slot.getClass().getName().equals(SLOT_ITEM_HANDLER)) {
            return SlotRole.STORAGE;
        }
        return SlotRole.OTHER;
    }
}
