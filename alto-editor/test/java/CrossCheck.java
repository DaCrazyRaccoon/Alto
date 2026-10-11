import baritone.alto.script.ScriptLoader;
import baritone.alto.script.ScriptReport;
import baritone.alto.script.codec.ScriptCodec;
import baritone.alto.script.lang.Diagnostic;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Test helper for the editor (not part of Alto): runs the real Java loader on a list of scripts so the editor's
 * JavaScript validator, canonical writer, hash and capability summary can be compared with it.
 * <p>
 * Usage: {@code java CrossCheck in.json out.json}; in.json is {@code [{"name": ..., "json": "<script text>"}]}.
 */
public final class CrossCheck {

    public static void main(String[] args) throws Exception {
        JsonArray cases = JsonParser.parseString(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8)).getAsJsonArray();
        JsonArray out = new JsonArray();
        for (JsonElement e : cases) {
            JsonObject c = e.getAsJsonObject();
            ScriptReport r = ScriptLoader.load(c.get("json").getAsString());
            JsonObject o = new JsonObject();
            o.addProperty("name", c.get("name").getAsString());
            JsonArray diagnostics = new JsonArray();
            for (Diagnostic d : r.diagnostics()) {
                JsonObject x = new JsonObject();
                x.addProperty("severity", d.severity().name().toLowerCase(Locale.ROOT));
                x.addProperty("code", d.code().jsonName());
                x.addProperty("path", d.path());
                x.addProperty("message", d.message());
                diagnostics.add(x);
            }
            o.add("diagnostics", diagnostics);
            o.addProperty("runnable", r.runnable());
            if (r.script() != null) {
                o.addProperty("canonical", ScriptCodec.toCanonicalJson(r.script()));
                o.addProperty("pretty", ScriptCodec.toPrettyJson(r.script()));
                o.addProperty("hash", r.hash());
                JsonArray sentences = new JsonArray();
                r.capabilities().sentences().forEach(sentences::add);
                o.add("sentences", sentences);
            }
            out.add(o);
        }
        Files.writeString(Path.of(args[1]), new GsonBuilder().disableHtmlEscaping().create().toJson(out), StandardCharsets.UTF_8);
    }
}
