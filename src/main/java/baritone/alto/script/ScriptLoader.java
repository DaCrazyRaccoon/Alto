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
import baritone.alto.script.check.ScriptValidator;
import baritone.alto.script.codec.ParseResult;
import baritone.alto.script.codec.ScriptCodec;
import baritone.alto.script.lang.Script;

/**
 * Entry point for hosts and tools: parse, validate, hash and summarize a script in one call. Pure: no file access
 * (the host reads the file and passes its text).
 * <p>
 * Typical binding flow: {@code ScriptReport r = ScriptLoader.load(text)}; show {@code r.errors()} if not
 * {@link ScriptReport#runnable()}; if {@code r.hash()} is new, show the confirmation screen with
 * {@code r.capabilities().sentences()} and {@code r.warnings()}; then
 * {@code new ScriptRun(r.script(), host, options)} and call {@code tick()} every client tick.
 */
public final class ScriptLoader {

    private ScriptLoader() {
    }

    public static ScriptReport load(String json) {
        ParseResult parsed = ScriptCodec.parse(json);
        if (!parsed.ok()) {
            return new ScriptReport(null, parsed.diagnostics(), null, null);
        }
        Script script = parsed.script();
        return new ScriptReport(script, ScriptValidator.validate(script), ScriptCodec.hash(script),
                CapabilitySummary.of(script));
    }
}
