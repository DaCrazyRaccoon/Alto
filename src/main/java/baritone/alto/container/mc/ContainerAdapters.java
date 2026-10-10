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
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Registry of {@link ContainerAdapter}s. Vanilla adapters are built in; integrations (Sophisticated Storage, Create
 * filters, Lootr...) register theirs at startup. Thread-safe for registration; lookups happen on the client thread.
 */
public final class ContainerAdapters {

    private static final List<ContainerAdapter> ADAPTERS = new CopyOnWriteArrayList<>();

    static {
        // Chests (single/double), barrels and ender chests all use ChestMenu.
        register(simple("vanilla:chest", 10, m -> m instanceof ChestMenu, true));
        register(simple("vanilla:shulker_box", 10, m -> m instanceof ShulkerBoxMenu, true));
        register(simple("vanilla:hopper", 10, m -> m instanceof HopperMenu, true));
        register(simple("vanilla:dispenser", 10, m -> m instanceof DispenserMenu, true));
        register(simple("vanilla:crafting_table", 10, m -> m instanceof CraftingMenu, false));
        register(simple("vanilla:furnace", 10, m -> m instanceof AbstractFurnaceMenu, false));
        register(simple("vanilla:player", 10, m -> m instanceof InventoryMenu, false));
    }

    private ContainerAdapters() {
    }

    public static void register(ContainerAdapter adapter) {
        ADAPTERS.removeIf(a -> a.id().equals(adapter.id()));
        ADAPTERS.add(adapter);
    }

    public static List<ContainerAdapter> all() {
        List<ContainerAdapter> list = new ArrayList<>(ADAPTERS);
        list.sort(Comparator.comparingInt(ContainerAdapter::priority).reversed().thenComparing(ContainerAdapter::id));
        return list;
    }

    /** Best adapter for a menu; the generic adapter if none matches. */
    public static ContainerAdapter forMenu(AbstractContainerMenu menu) {
        ContainerAdapter best = null;
        for (ContainerAdapter a : ADAPTERS) {
            try {
                if (a.matches(menu) && (best == null || a.priority() > best.priority())) {
                    best = a;
                }
            } catch (Throwable t) {
                // A broken integration must never break vanilla containers.
            }
        }
        return best != null ? best : GENERIC;
    }

    /** Generic rules only (also used when an adapter returns null for a slot). */
    public static final ContainerAdapter GENERIC = simple("generic", 0, m -> true, true);

    /** An adapter that only names a menu kind and uses the generic slot rules. */
    public static ContainerAdapter simple(String id, int priority, Predicate<AbstractContainerMenu> matches, boolean indexable) {
        return new ContainerAdapter() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public boolean matches(AbstractContainerMenu menu) {
                return matches.test(menu);
            }

            @Override
            public SlotRole roleOf(AbstractContainerMenu menu, Slot slot, int index, Inventory playerInventory) {
                return null;
            }

            @Override
            public boolean indexable(AbstractContainerMenu menu) {
                return indexable;
            }
        };
    }
}
