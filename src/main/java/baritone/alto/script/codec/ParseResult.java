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
import baritone.alto.script.lang.Script;

import java.util.List;

/**
 * Result of {@link ScriptCodec#parse}: the AST, or {@code null} with the structural errors that prevented building it.
 *
 * @param script      the parsed script, {@code null} when {@code diagnostics} contains errors
 * @param diagnostics structural errors in document order (empty when parsing succeeded)
 */
public record ParseResult(Script script, List<Diagnostic> diagnostics) {

    public ParseResult {
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean ok() {
        return script != null;
    }
}
