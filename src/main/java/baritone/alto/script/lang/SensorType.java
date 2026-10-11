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
 * Expressions without fields that read the player's state ({@code {"expr": "health"}}).
 */
public enum SensorType implements JsonNamed {
    /** Health points, 0 to 20 for a normal player (2 points = 1 heart). */
    HEALTH("health", ValueType.NUMBER),
    /** Food level, 0 to 20. */
    HUNGER("hunger", ValueType.NUMBER),
    /** Time of day in ticks, 0 to 23999 (0 = sunrise, 6000 = noon, 18000 = midnight). */
    TIME_OF_DAY("time_of_day", ValueType.NUMBER),
    /** Dimension id, e.g. {@code minecraft:overworld}. */
    DIMENSION("dimension", ValueType.STRING),
    /** Whether the inventory has no free slot. */
    INVENTORY_FULL("inventory_full", ValueType.BOOLEAN);

    private final String jsonName;
    private final ValueType valueType;

    SensorType(String jsonName, ValueType valueType) {
        this.jsonName = jsonName;
        this.valueType = valueType;
    }

    @Override
    public String jsonName() {
        return jsonName;
    }

    public ValueType valueType() {
        return valueType;
    }
}
