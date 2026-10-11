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

/**
 * Evaluated {@code goto} target.
 */
public sealed interface GotoTarget {

    /** {@code dimension} is a normalized id ({@code minecraft:the_nether}) or {@code null} for "where I am". */
    record Coords(int x, int y, int z, String dimension) implements GotoTarget {
    }

    /** A home set in Alto; {@code null} name means the default home. */
    record Home(String name) implements GotoTarget {
    }

    /** A named zone of Alto's storage index. */
    record StorageZone(String name) implements GotoTarget {
    }

    /** The nearest known portal (walk next to it; {@code take_portal} goes through). */
    record Portal() implements GotoTarget {
    }
}
