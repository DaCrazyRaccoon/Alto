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
 * Two-operand expressions ({@code {"expr": "add", "a": 1, "b": 2}}).
 * <p>
 * Math operators take and return numbers; comparisons return booleans ({@code eq}/{@code neq} compare two values of
 * the same type, the others compare numbers); {@code and}/{@code or} take booleans and short-circuit.
 */
public enum BinaryOp implements JsonNamed {
    ADD("add", Category.MATH),
    SUB("sub", Category.MATH),
    MUL("mul", Category.MATH),
    DIV("div", Category.MATH),
    /** Floored modulo: the result has the sign of {@code b} ({@code mod(-1, 5) = 4}). */
    MOD("mod", Category.MATH),
    MIN("min", Category.MATH),
    MAX("max", Category.MATH),
    EQ("eq", Category.EQUALITY),
    NEQ("neq", Category.EQUALITY),
    LT("lt", Category.ORDER),
    LTE("lte", Category.ORDER),
    GT("gt", Category.ORDER),
    GTE("gte", Category.ORDER),
    AND("and", Category.LOGIC),
    OR("or", Category.LOGIC);

    public enum Category {
        /** number, number to number */
        MATH,
        /** two values of the same type to boolean */
        EQUALITY,
        /** number, number to boolean */
        ORDER,
        /** boolean, boolean to boolean */
        LOGIC
    }

    private final String jsonName;
    private final Category category;

    BinaryOp(String jsonName, Category category) {
        this.jsonName = jsonName;
        this.category = category;
    }

    @Override
    public String jsonName() {
        return jsonName;
    }

    public Category category() {
        return category;
    }

    /** Operand type, or {@code null} for {@link Category#EQUALITY} (any type, both the same). */
    public ValueType operandType() {
        return switch (category) {
            case MATH, ORDER -> ValueType.NUMBER;
            case LOGIC -> ValueType.BOOLEAN;
            case EQUALITY -> null;
        };
    }

    public ValueType resultType() {
        return category == Category.MATH ? ValueType.NUMBER : ValueType.BOOLEAN;
    }
}
