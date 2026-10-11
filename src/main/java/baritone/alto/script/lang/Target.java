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

import java.util.List;

/**
 * Where {@code goto} walks to ({@code {"type": "coords", "x": 1, "y": 64, "z": 2}}).
 */
public sealed interface Target {

    /** JSON {@code type}. */
    String type();

    static List<String> types() {
        return List.of("coords", "home", "storage_zone", "portal");
    }

    /** A block position; {@code dimension} is an id such as {@code minecraft:the_nether} or {@code null} (current). */
    record Coords(Expr x, Expr y, Expr z, String dimension) implements Target {
        @Override
        public String type() {
            return "coords";
        }
    }

    /** A home set in Alto ({@code null} name = the default home). */
    record Home(String name) implements Target {
        @Override
        public String type() {
            return "home";
        }
    }

    /** A named storage zone of Alto's storage index. */
    record StorageZone(String name) implements Target {
        @Override
        public String type() {
            return "storage_zone";
        }
    }

    /** The nearest known portal (walks to it, does not go through; see {@code take_portal}). */
    record Portal() implements Target {
        @Override
        public String type() {
            return "portal";
        }
    }
}
