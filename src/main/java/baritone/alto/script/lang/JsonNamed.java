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
 * A constant with a fixed lower-case name used in script JSON ({@code "inventory"}, {@code "north"}, {@code "add"}).
 */
public interface JsonNamed {

    String jsonName();

    /** The constant whose {@link #jsonName()} equals {@code name}, or {@code null}. */
    static <E extends JsonNamed> E find(E[] values, String name) {
        for (E value : values) {
            if (value.jsonName().equals(name)) {
                return value;
            }
        }
        return null;
    }

    /** All JSON names in declaration order (messages, schema checks). */
    static List<String> names(JsonNamed[] values) {
        List<String> names = new ArrayList<>(values.length);
        for (JsonNamed value : values) {
            names.add(value.jsonName());
        }
        return names;
    }
}
