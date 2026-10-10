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

package baritone.alto.planner;

import baritone.alto.core.ItemKey;

/**
 * Where a recipe is performed.
 */
public enum StationKind {
    /** The 2x2 grid of the player's own inventory screen. Needs no block. */
    INVENTORY_GRID(null, "inventory grid"),
    CRAFTING_TABLE("minecraft:crafting_table", "crafting table"),
    FURNACE("minecraft:furnace", "furnace"),
    BLAST_FURNACE("minecraft:blast_furnace", "blast furnace"),
    SMOKER("minecraft:smoker", "smoker");

    /** Item (and block) id of the station, or {@code null} for the inventory grid. */
    public final String blockId;
    public final String displayName;

    StationKind(String blockId, String displayName) {
        this.blockId = blockId;
        this.displayName = displayName;
    }

    public boolean needsBlock() {
        return blockId != null;
    }

    public ItemKey item() {
        return blockId == null ? ItemKey.EMPTY : ItemKey.of(blockId);
    }

    public boolean isFurnaceLike() {
        return this == FURNACE || this == BLAST_FURNACE || this == SMOKER;
    }

    /** Pickaxe needed to get the block back when breaking it (furnaces drop nothing without one). */
    public boolean needsPickaxeToCollect() {
        return isFurnaceLike();
    }
}
