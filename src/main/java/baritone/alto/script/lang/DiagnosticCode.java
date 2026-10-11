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

import java.util.Locale;

/**
 * Stable machine-readable diagnostic codes (the editor and tests match on them; the messages may change).
 * The first group is reported by the codec (structure), the second by the validator (meaning), the last are warnings.
 */
public enum DiagnosticCode {
    // ---- structure (codec)
    JSON_SYNTAX(Severity.ERROR),
    TOO_LARGE(Severity.ERROR),
    UNSUPPORTED_FORMAT(Severity.ERROR),
    UNSUPPORTED_VERSION(Severity.ERROR),
    MISSING_FIELD(Severity.ERROR),
    UNKNOWN_FIELD(Severity.ERROR),
    WRONG_JSON_TYPE(Severity.ERROR),
    UNKNOWN_BLOCK(Severity.ERROR),
    UNKNOWN_EXPR(Severity.ERROR),
    UNKNOWN_EVENT(Severity.ERROR),
    INVALID_CHOICE(Severity.ERROR),
    // ---- meaning (validator)
    INVALID_NAME(Severity.ERROR),
    DUPLICATE_NAME(Severity.ERROR),
    DUPLICATE_EVENT(Severity.ERROR),
    NO_EVENTS(Severity.ERROR),
    UNKNOWN_VARIABLE(Severity.ERROR),
    UNKNOWN_PROCEDURE(Severity.ERROR),
    UNKNOWN_PARAM(Severity.ERROR),
    PARAM_OUTSIDE_PROCEDURE(Severity.ERROR),
    MISSING_ARGUMENT(Severity.ERROR),
    UNKNOWN_ARGUMENT(Severity.ERROR),
    TYPE_MISMATCH(Severity.ERROR),
    NOT_WHOLE_NUMBER(Severity.ERROR),
    OUT_OF_RANGE(Severity.ERROR),
    INVALID_ID(Severity.ERROR),
    INVALID_FILE_NAME(Severity.ERROR),
    INVALID_TEXT(Severity.ERROR),
    RECURSION(Severity.ERROR),
    BREAK_OUTSIDE_LOOP(Severity.ERROR),
    LOOP_WITHOUT_WAIT(Severity.ERROR),
    // ---- warnings
    FOREVER_LOOP(Severity.WARNING),
    SENDS_CHAT(Severity.WARNING),
    RUNS_COMMAND(Severity.WARNING),
    LARGE_COUNT(Severity.WARNING),
    UNREACHABLE_CODE(Severity.WARNING),
    UNUSED_PROCEDURE(Severity.WARNING),
    UNUSED_VARIABLE(Severity.WARNING);

    private final Severity severity;

    DiagnosticCode(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }

    /** {@code loop_without_wait} */
    public String jsonName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
