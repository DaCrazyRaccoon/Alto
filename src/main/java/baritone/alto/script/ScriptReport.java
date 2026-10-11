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

package baritone.alto.script;

import baritone.alto.script.check.CapabilitySummary;
import baritone.alto.script.lang.Diagnostic;
import baritone.alto.script.lang.Script;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the host needs to decide whether and how to offer a script: the AST, all diagnostics, the hash that
 * identifies this exact version and the capability summary for the confirmation screen.
 *
 * @param script       the AST, {@code null} when the JSON could not be parsed
 * @param diagnostics  structural errors (when {@code script} is null) or validation errors and warnings
 * @param hash         {@code ScriptCodec.hash(script)}, {@code null} when not parsed
 * @param capabilities what the script may do, {@code null} when not parsed
 */
public record ScriptReport(Script script, List<Diagnostic> diagnostics, String hash, CapabilitySummary capabilities) {

    public ScriptReport {
        diagnostics = List.copyOf(diagnostics);
    }

    /** Parsed and free of errors (warnings allowed): it may be offered to the user to run. */
    public boolean runnable() {
        return script != null && errors().isEmpty();
    }

    public List<Diagnostic> errors() {
        List<Diagnostic> out = new ArrayList<>();
        for (Diagnostic d : diagnostics) {
            if (d.isError()) {
                out.add(d);
            }
        }
        return out;
    }

    public List<Diagnostic> warnings() {
        List<Diagnostic> out = new ArrayList<>();
        for (Diagnostic d : diagnostics) {
            if (!d.isError()) {
                out.add(d);
            }
        }
        return out;
    }
}
