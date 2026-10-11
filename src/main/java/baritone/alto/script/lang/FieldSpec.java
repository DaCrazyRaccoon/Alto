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
 * Describes one field of a block: its JSON name, what it holds and its allowed range. The codec, the validator
 * (literal values) and the interpreter (computed values) all check fields through this one description.
 *
 * @param name     JSON field name
 * @param kind     what the field holds
 * @param required whether the field must be present
 * @param min      smallest allowed number (number kinds); for {@link FieldKind#STRING} 1 means "must not be blank"
 * @param max      largest allowed number (number kinds) or the maximum text length (text kinds)
 * @param choices  allowed values for {@link FieldKind#CHOICE}
 */
public record FieldSpec(String name, FieldKind kind, boolean required, double min, double max, List<String> choices) {

    /** A value that does not fit the field, with the code to report it under. */
    public record Problem(DiagnosticCode code, String message) {
    }

    public FieldSpec {
        choices = List.copyOf(choices);
    }

    public static FieldSpec number(String name, double min, double max) {
        return new FieldSpec(name, FieldKind.NUMBER, true, min, max, List.of());
    }

    public static FieldSpec integer(String name, double min, double max) {
        return new FieldSpec(name, FieldKind.INTEGER, true, min, max, List.of());
    }

    /** An item count, 1 to {@link Limits#MAX_COUNT}. */
    public static FieldSpec count(String name) {
        return integer(name, 1, Limits.MAX_COUNT);
    }

    /** A block coordinate within the world border. */
    public static FieldSpec coordinate(String name) {
        return integer(name, -Limits.MAX_COORDINATE, Limits.MAX_COORDINATE);
    }

    public static FieldSpec string(String name, int maxLength) {
        return new FieldSpec(name, FieldKind.STRING, true, 0, maxLength, List.of());
    }

    /** A string expression that must not be blank (names of stations and the like). */
    public static FieldSpec name(String name, int maxLength) {
        return new FieldSpec(name, FieldKind.STRING, true, 1, maxLength, List.of());
    }

    public static FieldSpec any(String name) {
        return new FieldSpec(name, FieldKind.ANY, true, 0, Limits.MAX_TEXT_LENGTH, List.of());
    }

    public static FieldSpec id(String name) {
        return new FieldSpec(name, FieldKind.ID, true, 0, 200, List.of());
    }

    public static FieldSpec fileName(String name) {
        return new FieldSpec(name, FieldKind.FILE_NAME, true, 0, 128, List.of());
    }

    public static FieldSpec literalText(String name, int maxLength) {
        return new FieldSpec(name, FieldKind.TEXT, true, 0, maxLength, List.of());
    }

    public static FieldSpec choice(String name, JsonNamed[] values) {
        return new FieldSpec(name, FieldKind.CHOICE, true, 0, 0, JsonNamed.names(values));
    }

    public static FieldSpec idList(String name) {
        return new FieldSpec(name, FieldKind.ID_LIST, true, 0, 200, List.of());
    }

    public static FieldSpec target(String name) {
        return new FieldSpec(name, FieldKind.TARGET, true, 0, 0, List.of());
    }

    public FieldSpec optional() {
        return new FieldSpec(name, kind, false, min, max, choices);
    }

    /** Static type an expression in this field must have; {@code null} for {@link FieldKind#ANY} and non-expressions. */
    public ValueType valueType() {
        return switch (kind) {
            case NUMBER, INTEGER -> ValueType.NUMBER;
            case STRING, ID, FILE_NAME, ID_LIST -> ValueType.STRING;
            default -> null;
        };
    }

    /** Checks a number for this field; {@code null} when it fits. */
    public Problem checkNumber(double v) {
        if (kind == FieldKind.INTEGER && !Value.isWhole(v)) {
            return new Problem(DiagnosticCode.NOT_WHOLE_NUMBER,
                    name + " must be a whole number, got " + Value.formatNumber(v));
        }
        if (v < min || v > max) {
            return new Problem(DiagnosticCode.OUT_OF_RANGE, name + " must be between " + Value.formatNumber(min)
                    + " and " + Value.formatNumber(max) + ", got " + Value.formatNumber(v));
        }
        return null;
    }

    /** Checks a text for this field (length, id format, file name); {@code null} when it fits. */
    public Problem checkText(String s) {
        switch (kind) {
            case ID, ID_LIST -> {
                if (!Ids.isValidId(s)) {
                    return new Problem(DiagnosticCode.INVALID_ID,
                            name + " must be an item or block id like minecraft:oak_planks, got '" + s + "'");
                }
            }
            case FILE_NAME -> {
                if (!Ids.isValidFileName(s)) {
                    return new Problem(DiagnosticCode.INVALID_FILE_NAME, name + " must be a plain file name such as"
                            + " house.schem (letters, digits, spaces and _ . ( ) + -, no folders), got '" + s + "'");
                }
            }
            case TEXT -> {
                if (s.isBlank()) {
                    return new Problem(DiagnosticCode.INVALID_TEXT, name + " must not be empty");
                }
            }
            case STRING -> {
                if (min >= 1 && s.isBlank()) {
                    return new Problem(DiagnosticCode.INVALID_TEXT, name + " must not be empty");
                }
            }
            default -> {
            }
        }
        if (s.length() > max) {
            return new Problem(DiagnosticCode.OUT_OF_RANGE,
                    name + " must be at most " + (long) max + " characters, got " + s.length());
        }
        return null;
    }
}
