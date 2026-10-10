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

import baritone.alto.script.codec.ScriptCodec;
import baritone.alto.script.lang.Diagnostic;
import baritone.alto.script.lang.DiagnosticCode;
import baritone.alto.script.lang.Script;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compact script JSON for tests: single quotes stand for double quotes (test texts avoid apostrophes).
 */
public final class Scripts {

    private Scripts() {
    }

    /** {@code {'a':1}} to {@code {"a":1}}. */
    public static String j(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }

    /** A whole script; every argument is single-quoted JSON. */
    public static String script(String variables, String procedures, String... events) {
        return j("{'format':'alto-script','version':1,'name':'test','variables':[" + variables + "],'procedures':["
                + procedures + "],'events':[" + String.join(",", events) + "]}");
    }

    /** A script with only an on_start handler running {@code statements}. */
    public static String onStart(String... statements) {
        return script("", "", startHandler(statements));
    }

    /** A script with variables and an on_start handler. */
    public static String withVars(String variables, String... statements) {
        return script(variables, "", startHandler(statements));
    }

    public static String startHandler(String... statements) {
        return "{'event':'on_start','body':[" + String.join(",", statements) + "]}";
    }

    public static String handler(String eventFields, String... statements) {
        return "{" + eventFields + ",'body':[" + String.join(",", statements) + "]}";
    }

    public static Script parse(String json) {
        return ScriptCodec.parseOrThrow(json);
    }

    /** Parse + validate diagnostics. */
    public static List<Diagnostic> check(String json) {
        return ScriptLoader.load(json).diagnostics();
    }

    public static List<DiagnosticCode> codes(String json) {
        List<DiagnosticCode> codes = new ArrayList<>();
        for (Diagnostic d : check(json)) {
            codes.add(d.code());
        }
        return codes;
    }

    /** Asserts that a diagnostic with {@code code} exists at {@code path} and returns it. */
    public static Diagnostic expect(String json, DiagnosticCode code, String path) {
        List<Diagnostic> diagnostics = check(json);
        for (Diagnostic d : diagnostics) {
            if (d.code() == code && d.path().equals(path)) {
                return d;
            }
        }
        fail("expected " + code + " at " + path + " but got " + diagnostics);
        return null;
    }

    /** Asserts the script parses and validates without any error (warnings allowed). */
    public static Script valid(String json) {
        ScriptReport report = ScriptLoader.load(json);
        assertTrue(report.runnable(), () -> "unexpected errors: " + report.errors());
        return report.script();
    }
}
