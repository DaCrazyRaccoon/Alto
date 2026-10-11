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

package baritone.alto.script.codec;

import baritone.alto.script.lang.Diagnostic;

import java.util.List;

/**
 * Thrown by {@link ScriptCodec#parseOrThrow} when a script cannot be parsed. The message lists the first errors.
 */
public final class ScriptFormatException extends IllegalArgumentException {

    private final List<Diagnostic> diagnostics;

    public ScriptFormatException(List<Diagnostic> diagnostics) {
        super(summary(diagnostics));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    private static String summary(List<Diagnostic> diagnostics) {
        StringBuilder sb = new StringBuilder("invalid Alto script");
        int shown = 0;
        for (Diagnostic d : diagnostics) {
            if (shown++ == 5) {
                sb.append("; ... (").append(diagnostics.size() - 5).append(" more)");
                break;
            }
            sb.append("; ").append(d);
        }
        return sb.toString();
    }
}
