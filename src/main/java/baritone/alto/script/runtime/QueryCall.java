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

package baritone.alto.script.runtime;

import baritone.alto.script.lang.Axis;
import baritone.alto.script.lang.ValueType;
import baritone.alto.script.lang.Where;

/**
 * A question to the host about the world ({@link ScriptHost#query}). The host must answer with a value of
 * {@link #resultType()}; anything else fails the run.
 */
public sealed interface QueryCall {

    ValueType resultType();

    /** The expression name, for messages. */
    String name();

    /** How many of {@code item} (normalized id) are in the inventory or in known storage. */
    record ItemCount(String item, Where where) implements QueryCall {
        public ValueType resultType() {
            return ValueType.NUMBER;
        }

        public String name() {
            return "item_count";
        }
    }

    /** Health points (20 = full for a normal player). */
    record Health() implements QueryCall {
        public ValueType resultType() {
            return ValueType.NUMBER;
        }

        public String name() {
            return "health";
        }
    }

    /** Food level 0..20. */
    record Hunger() implements QueryCall {
        public ValueType resultType() {
            return ValueType.NUMBER;
        }

        public String name() {
            return "hunger";
        }
    }

    /** Block position of the player on one axis (a whole number). */
    record Position(Axis axis) implements QueryCall {
        public ValueType resultType() {
            return ValueType.NUMBER;
        }

        public String name() {
            return "position";
        }
    }

    /** Day time 0..23999. */
    record TimeOfDay() implements QueryCall {
        public ValueType resultType() {
            return ValueType.NUMBER;
        }

        public String name() {
            return "time_of_day";
        }
    }

    /** Dimension id such as {@code minecraft:overworld}. */
    record Dimension() implements QueryCall {
        public ValueType resultType() {
            return ValueType.STRING;
        }

        public String name() {
            return "dimension";
        }
    }

    /** Block id at a position; {@code minecraft:air} for air or unloaded chunks. */
    record BlockAt(int x, int y, int z) implements QueryCall {
        public ValueType resultType() {
            return ValueType.STRING;
        }

        public String name() {
            return "block_at";
        }
    }

    /** Whether no inventory slot is free. */
    record InventoryFull() implements QueryCall {
        public ValueType resultType() {
            return ValueType.BOOLEAN;
        }

        public String name() {
            return "inventory_full";
        }
    }
}
