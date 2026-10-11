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
import baritone.alto.script.lang.DiagnosticCode;
import baritone.alto.script.lang.Limits;
import baritone.alto.script.lang.Script;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Reads Alto scripts from JSON text and writes them back in canonical form. Does no file access: the host loads the
 * text and passes a {@code String} or {@code Reader}.
 * <p>
 * Parsing only checks structure (see {@link ParseResult}); run {@code ScriptValidator} on the result for meaning
 * (types, names, ranges, loops). {@code baritone.alto.script.ScriptLoader} does both.
 */
public final class ScriptCodec {

    private ScriptCodec() {
    }

    public static ParseResult parse(String json) {
        if (json.length() > Limits.MAX_SCRIPT_CHARS) {
            return failed(DiagnosticCode.TOO_LARGE, "", "the script is larger than " + Limits.MAX_SCRIPT_CHARS + " characters");
        }
        JsonElement root;
        try {
            root = StrictJson.parse(json);
        } catch (StrictJson.SyntaxError e) {
            return failed(DiagnosticCode.JSON_SYNTAX, e.path(), e.getMessage());
        }
        AstReader reader = new AstReader();
        Script script = reader.read(root);
        return new ParseResult(reader.hasErrors() ? null : script, reader.diagnostics());
    }

    /** Reads at most {@link Limits#MAX_SCRIPT_CHARS} + 1 characters, then parses. */
    public static ParseResult parse(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[8192];
        int n;
        while ((n = reader.read(buffer)) > 0) {
            sb.append(buffer, 0, n);
            if (sb.length() > Limits.MAX_SCRIPT_CHARS) {
                break;
            }
        }
        return parse(sb.toString());
    }

    /** Parses or throws {@link ScriptFormatException} with all structural errors. */
    public static Script parseOrThrow(String json) {
        ParseResult result = parse(json);
        if (!result.ok()) {
            throw new ScriptFormatException(result.diagnostics());
        }
        return result.script();
    }

    /** Compact canonical JSON: stable key order and number format. Used for hashing. */
    public static String toCanonicalJson(Script script) {
        return AstWriter.write(script, false);
    }

    /** The canonical form, indented with two spaces and ending with a newline (for files and the editor). */
    public static String toPrettyJson(Script script) {
        return AstWriter.write(script, true);
    }

    /**
     * SHA-256 of the UTF-8 canonical JSON as 64 lower-case hex digits. Formatting, key order and {@code 64} versus
     * {@code 64.0} do not change it; any change to names, values or blocks does.
     */
    public static String hash(Script script) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(toCanonicalJson(script).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static ParseResult failed(DiagnosticCode code, String path, String message) {
        return new ParseResult(null, List.of(Diagnostic.of(code, path, message)));
    }
}
