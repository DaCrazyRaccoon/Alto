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
 * One-operand expressions ({@code {"expr": "abs", "a": -3}}).
 */
public enum UnaryOp implements JsonNamed {
    ABS("abs", ValueType.NUMBER),
    /** Rounds half up: {@code round(2.5) = 3}, {@code round(-2.5) = -2}. */
    ROUND("round", ValueType.NUMBER),
    NOT("not", ValueType.BOOLEAN);

    private final String jsonName;
    private final ValueType type;

    UnaryOp(String jsonName, ValueType type) {
        this.jsonName = jsonName;
        this.type = type;
    }

    @Override
    public String jsonName() {
        return jsonName;
    }

    /** Operand and result type (they are the same). */
    public ValueType type() {
        return type;
    }
}
