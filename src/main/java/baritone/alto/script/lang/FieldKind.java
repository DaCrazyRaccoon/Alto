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
 * What an action field holds. The first six kinds are expressions (a literal or an {@code {"expr": ...}} object),
 * the others are fixed JSON shapes that are known before the script runs.
 */
public enum FieldKind {
    /** Number expression within [min, max]. */
    NUMBER,
    /** Whole-number expression within [min, max]. */
    INTEGER,
    /** String expression of at most max characters. */
    STRING,
    /** Expression of any type, shown as text (message). */
    ANY,
    /** String expression holding an item or block id. */
    ID,
    /** String expression holding a plain file name (schematics). */
    FILE_NAME,
    /** A literal JSON string (never computed), e.g. Baritone commands, so the confirmation screen can list them. */
    TEXT,
    /** A literal JSON string from a fixed list. */
    CHOICE,
    /** A JSON array of id expressions (at least one entry). */
    ID_LIST,
    /** A goto target object. */
    TARGET;

    public boolean isExpression() {
        return switch (this) {
            case NUMBER, INTEGER, STRING, ANY, ID, FILE_NAME -> true;
            default -> false;
        };
    }
}
