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

package baritone.alto.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Where a block's filter slots are and what they hold (feature G). Implemented by mod integrations (Create);
 * the generic {@code SetFilterTask} aims at a slot and uses a normal right-click with the item in hand.
 */
public interface FilterAccess {

    /**
     * @param face      the block face the slot sits on (the click must hit this face)
     * @param center    world position of the slot's centre
     * @param radius    how far from the centre a click still hits the slot
     * @param countUi   the slot shows an amount; the mod's own client code confirms the click a tick later, so the
     *                  aim must stay on the slot and the use key must not be held
     * @param label     human description ("deployer filter (north)")
     */
    record FilterSlot(Direction face, Vec3 center, double radius, boolean countUi, String label) {
    }

    /** Filter slots of the block at {@code pos}, or an empty list if it has none (client thread). */
    List<FilterSlot> slots(Level level, BlockPos pos);

    /** What the slot on {@code face} filters for now (EMPTY if nothing). */
    ItemStack current(Level level, BlockPos pos, Direction face);
}
