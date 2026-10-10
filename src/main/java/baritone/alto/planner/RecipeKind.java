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

/**
 * Recipe types the planner knows how to execute. Recipes of other types (modded machines, stonecutting,
 * smithing) can still be shown, for example from EMI, but are never part of an executable plan.
 */
public enum RecipeKind {
    CRAFTING(true),
    SMELTING(true),
    BLASTING(true),
    SMOKING(true),
    /** Known but not executed by the bot (campfire, stonecutter, smithing, modded machines). */
    OTHER(false);

    public final boolean executable;

    RecipeKind(boolean executable) {
        this.executable = executable;
    }

    public boolean isCooking() {
        return this == SMELTING || this == BLASTING || this == SMOKING;
    }

    public StationKind cookingStation() {
        return switch (this) {
            case SMELTING -> StationKind.FURNACE;
            case BLASTING -> StationKind.BLAST_FURNACE;
            case SMOKING -> StationKind.SMOKER;
            default -> throw new IllegalStateException(this + " is not a cooking recipe");
        };
    }
}
