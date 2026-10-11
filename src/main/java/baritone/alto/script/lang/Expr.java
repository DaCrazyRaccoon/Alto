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

import java.util.ArrayList;
import java.util.List;

/**
 * An expression (value block). In JSON a literal is a plain number, string or boolean; everything else is an object
 * {@code {"expr": "<kind>", ...}}. Every node keeps its location {@link #path()} for error messages.
 */
public sealed interface Expr {

    /** Location in the script, e.g. {@code events[0].body[2].times}. */
    String path();

    /** The JSON {@code expr} name ({@code var}, {@code add}, ...), or {@code literal}. */
    String kind();

    /** Names of every {@code expr} kind, for messages and schema checks. */
    static List<String> kinds() {
        List<String> kinds = new ArrayList<>(List.of("var", "param", "item_count", "position", "block_at",
                "random", "join"));
        kinds.addAll(JsonNamed.names(SensorType.values()));
        kinds.addAll(JsonNamed.names(BinaryOp.values()));
        kinds.addAll(JsonNamed.names(UnaryOp.values()));
        return kinds;
    }

    record Literal(String path, Value value) implements Expr {
        @Override
        public String kind() {
            return "literal";
        }
    }

    /** Reads a script variable. */
    record Var(String path, String name) implements Expr {
        @Override
        public String kind() {
            return "var";
        }
    }

    /** Reads a parameter of the enclosing procedure. */
    record Param(String path, String name) implements Expr {
        @Override
        public String kind() {
            return "param";
        }
    }

    /** How many of an item are in the inventory or in known storage. */
    record ItemCount(String path, Expr item, Where where) implements Expr {
        @Override
        public String kind() {
            return "item_count";
        }
    }

    /** health, hunger, time_of_day, dimension, inventory_full. */
    record Sensor(String path, SensorType sensor) implements Expr {
        @Override
        public String kind() {
            return sensor.jsonName();
        }
    }

    /** The player's block position on one axis. */
    record Position(String path, Axis axis) implements Expr {
        @Override
        public String kind() {
            return "position";
        }
    }

    /** The block id at a position, e.g. {@code minecraft:stone}; {@code minecraft:air} when empty or unloaded. */
    record BlockAt(String path, Expr x, Expr y, Expr z) implements Expr {
        @Override
        public String kind() {
            return "block_at";
        }
    }

    /** A random whole number from min to max inclusive (seedable). */
    record RandomInt(String path, Expr min, Expr max) implements Expr {
        @Override
        public String kind() {
            return "random";
        }
    }

    record Binary(String path, BinaryOp op, Expr a, Expr b) implements Expr {
        @Override
        public String kind() {
            return op.jsonName();
        }
    }

    record Unary(String path, UnaryOp op, Expr a) implements Expr {
        @Override
        public String kind() {
            return op.jsonName();
        }
    }

    /** Joins the display form of every part into one string. */
    record Join(String path, List<Expr> parts) implements Expr {
        public Join {
            parts = List.copyOf(parts);
        }

        @Override
        public String kind() {
            return "join";
        }
    }
}
