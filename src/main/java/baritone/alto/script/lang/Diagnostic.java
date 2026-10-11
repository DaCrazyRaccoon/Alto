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
 * One problem found in a script.
 *
 * @param severity error or warning (always {@code code.severity()})
 * @param code     stable code
 * @param path     location such as {@code events[0].body[2].times}; empty for the whole script
 * @param message  human-readable explanation
 */
public record Diagnostic(Severity severity, DiagnosticCode code, String path, String message) {

    public static Diagnostic of(DiagnosticCode code, String path, String message) {
        return new Diagnostic(code.severity(), code, path, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /** {@code error at events[0].body[2].times: repeat times must be a number} */
    @Override
    public String toString() {
        return (isError() ? "error" : "warning") + (path.isEmpty() ? "" : " at " + path) + ": " + message;
    }
}
