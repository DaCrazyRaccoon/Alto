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

package baritone.alto.script.lang;

/**
 * A work station {@code place_station} / {@code pickup_station} can handle.
 */
public enum StationType implements JsonNamed {
    CRAFTING_TABLE("crafting_table"),
    FURNACE("furnace"),
    BLAST_FURNACE("blast_furnace"),
    SMOKER("smoker");

    private final String jsonName;

    StationType(String jsonName) {
        this.jsonName = jsonName;
    }

    @Override
    public String jsonName() {
        return jsonName;
    }

    /** Block (and item) id, e.g. {@code minecraft:blast_furnace}. */
    public String blockId() {
        return "minecraft:" + jsonName;
    }
}
