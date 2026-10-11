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

import baritone.Baritone;
import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import baritone.api.Settings;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What must never be deposited: the keep-list setting, tools/weapons/armor (setting), food (setting), shulker boxes
 * Alto carries for the job, and whatever the current job needs (e.g. the blocks a build places).
 */
public final class KeepList {

    private KeepList() {
    }

    /** A predicate that is true for items to KEEP in the inventory. */
    public static Predicate<ItemKey> keep(ItemCounts jobNeeds) {
        Settings s = Baritone.settings();
        Set<ItemKey> listed = new HashSet<>();
        for (Item i : s.altoKeepItems.value) {
            listed.add(McItems.key(i));
        }
        boolean tools = s.altoKeepTools.value;
        boolean food = s.altoKeepFood.value;
        return key -> {
            if (listed.contains(key) || listed.contains(key.plain())) {
                return true;
            }
            if (jobNeeds != null && jobNeeds.get(key) > 0) {
                return true;
            }
            Item item = McItems.item(key);
            if (tools && (item instanceof TieredItem || item instanceof ArmorItem || item instanceof BowItem
                    || item instanceof CrossbowItem || item instanceof ShieldItem || item instanceof TridentItem)) {
                return true;
            }
            return food && new ItemStack(item).has(DataComponents.FOOD);
        };
    }
}
