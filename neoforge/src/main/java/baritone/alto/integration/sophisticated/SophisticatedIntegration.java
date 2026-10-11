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

package baritone.alto.integration.sophisticated;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.alto.container.SlotRole;
import baritone.alto.container.mc.ContainerAdapter;
import baritone.alto.container.mc.ContainerAdapters;
import baritone.alto.integration.AltoIntegration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sophisticated Storage 1.5.89 / Sophisticated Backpacks 3.25.77 (through Sophisticated Core 1.4.87). Only loaded
 * when Sophisticated Core is installed.
 * <ul>
 * <li>Menus: storage slots are the menu's own storage slots; upgrade slots and upgrade settings (filter) slots are
 * never clicked. Slots can hold more than 64 with stack upgrades; Alto reads the real count and limit.</li>
 * <li>Storage index: the menu reports its own block position, so a backpack opened with its hotkey is never
 * recorded as the chest under your crosshair; a backpack you carry is not indexed at all.</li>
 * <li>Deposits are not allowed into storage with a Void upgrade (it could delete what Alto puts in).</li>
 * <li>Zone scans include Sophisticated chests, barrels and shulker boxes, and placed backpacks. Limited barrels are
 * left out: right-clicking their front inserts the held item instead of opening a menu.</li>
 * </ul>
 */
public final class SophisticatedIntegration implements AltoIntegration {

    @Override
    public String name() {
        return "Sophisticated Storage/Backpacks";
    }

    @Override
    public boolean enabled() {
        return Baritone.settings().altoSophisticated.value;
    }

    @Override
    public void init(Alto alto) {
        ContainerAdapters.register(new Adapter());
        List<Block> blocks = storageBlocks();
        alto.storage().addStorageBlocks(blocks);
        AltoLog.debug("sophisticated storage blocks: " + blocks.size());
    }

    static List<Block> storageBlocks() {
        List<Block> out = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            if (isStorageBlockId(BuiltInRegistries.BLOCK.getKey(b))) {
                out.add(b);
            }
        }
        return out;
    }

    static boolean isStorageBlockId(ResourceLocation id) {
        String path = id.getPath();
        if (id.getNamespace().equals("sophisticatedstorage")) {
            return !path.contains("limited") && (path.endsWith("chest") || path.endsWith("barrel") || path.endsWith("shulker_box"));
        }
        return id.getNamespace().equals("sophisticatedbackpacks") && path.endsWith("backpack");
    }

    static final class Adapter implements ContainerAdapter {

        @Override
        public String id() {
            return "sophisticated:storage";
        }

        @Override
        public int priority() {
            return 20;
        }

        @Override
        public boolean matches(AbstractContainerMenu menu) {
            return menu instanceof StorageContainerMenuBase<?>;
        }

        @Override
        public SlotRole roleOf(AbstractContainerMenu menu, Slot slot, int index, Inventory playerInventory) {
            if (slot.container == playerInventory) {
                return null; // generic rule: hotbar / main inventory / armor
            }
            StorageContainerMenuBase<?> m = (StorageContainerMenuBase<?>) menu;
            return index >= 0 && index < m.getNumberOfStorageInventorySlots() && m.isStorageInventorySlot(index)
                    ? SlotRole.STORAGE : SlotRole.OTHER;
        }

        @Override
        public Optional<BlockPos> blockPos(AbstractContainerMenu menu) {
            return ((StorageContainerMenuBase<?>) menu).getBlockPosition();
        }

        @Override
        public boolean indexable(AbstractContainerMenu menu) {
            return ((StorageContainerMenuBase<?>) menu).getBlockPosition().isPresent();
        }

        @Override
        public boolean depositAllowed(AbstractContainerMenu menu) {
            for (Slot s : ((StorageContainerMenuBase<?>) menu).upgradeSlots) {
                ItemStack st = s.getItem();
                if (!st.isEmpty() && BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().contains("void")) {
                    return false;
                }
            }
            return true;
        }
    }
}
