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

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/**
 * A value of the script language: a finite number (double), a string or a boolean. Immutable.
 */
public sealed interface Value permits Value.Num, Value.Str, Value.Bool {

    ValueType type();

    /** How the value is shown to users and joined into text: {@code 64}, {@code 2.5}, {@code true}, raw strings. */
    String display();

    static Num of(double value) {
        return new Num(value);
    }

    static Str of(String value) {
        return new Str(value);
    }

    static Bool of(boolean value) {
        return value ? Bool.TRUE : Bool.FALSE;
    }

    static boolean isWhole(double v) {
        return Double.isFinite(v) && v == Math.rint(v);
    }

    /** Display form of a number: whole numbers without a fraction, others rounded to 12 significant digits. */
    static String formatNumber(double v) {
        if (isWhole(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        if (!Double.isFinite(v) || Math.abs(v) >= 1e15 || Math.abs(v) < 1e-6) {
            return Double.toString(v);
        }
        return new BigDecimal(v).round(new MathContext(12)).stripTrailingZeros().toPlainString();
    }

    /** A number. Never NaN or infinite; negative zero is stored as zero. */
    record Num(double value) implements Value {
        public Num {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("numbers must be finite, got " + value);
            }
            if (value == 0) {
                value = 0.0;
            }
        }

        @Override
        public ValueType type() {
            return ValueType.NUMBER;
        }

        @Override
        public String display() {
            return formatNumber(value);
        }

        public boolean isWhole() {
            return Value.isWhole(value);
        }
    }

    record Str(String value) implements Value {
        public Str {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public ValueType type() {
            return ValueType.STRING;
        }

        @Override
        public String display() {
            return value;
        }
    }

    record Bool(boolean value) implements Value {
        public static final Bool TRUE = new Bool(true);
        public static final Bool FALSE = new Bool(false);

        @Override
        public ValueType type() {
            return ValueType.BOOLEAN;
        }

        @Override
        public String display() {
            return Boolean.toString(value);
        }
    }
}
