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

package baritone.alto.script.runtime;

import baritone.alto.script.codec.StrictJson;
import baritone.alto.script.lang.Value;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What is saved so a run can be offered again after a game restart. Deliberately small: it does <b>not</b> store the
 * position inside a handler, the running action, timers or the call stack.
 * <p>
 * {@link ScriptRun#restore} restores exactly: the variable values (those still declared with the same type), then
 * starts {@code on_start} from the beginning and restarts {@code on_chat} handlers that were active, from their
 * beginning. {@code every} timers start counting again from the restore; condition events are checked again.
 * The host must ask the user before restoring; it must never resume a run automatically.
 *
 * @param scriptHash     {@code ScriptCodec.hash} of the script; restoring into a changed script is refused
 * @param variables      variable values in declaration order
 * @param activeHandlers paths ({@code events[2]}) of the handlers that were running
 * @param savedAtMillis  {@link ScriptHost#nowMillis()} when the snapshot was taken
 */
public record RunSnapshot(String scriptHash, Map<String, Value> variables, List<String> activeHandlers,
                          long savedAtMillis) {

    /** Value of the {@code format} field of the JSON form. */
    public static final String FORMAT = "alto-script-snapshot";

    public RunSnapshot {
        variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        activeHandlers = List.copyOf(activeHandlers);
    }

    public String toJson() {
        StringWriter out = new StringWriter();
        try (JsonWriter w = new JsonWriter(out)) {
            w.setIndent("  ");
            w.setHtmlSafe(false);
            w.beginObject();
            w.name("format").value(FORMAT);
            w.name("version").value(1);
            w.name("scriptHash").value(scriptHash);
            w.name("savedAtMillis").value(savedAtMillis);
            w.name("variables").beginObject();
            for (Map.Entry<String, Value> e : variables.entrySet()) {
                w.name(e.getKey());
                switch (e.getValue()) {
                    case Value.Num n -> {
                        if (n.isWhole() && Math.abs(n.value()) < 9.007199254740992E15) {
                            w.value((long) n.value());
                        } else {
                            w.value(n.value());
                        }
                    }
                    case Value.Str s -> w.value(s.value());
                    case Value.Bool b -> w.value(b.value());
                }
            }
            w.endObject();
            w.name("activeHandlers").beginArray();
            for (String path : activeHandlers) {
                w.value(path);
            }
            w.endArray();
            w.endObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out + "\n";
    }

    /** Parses {@link #toJson()} output; throws {@link IllegalArgumentException} for anything else. */
    public static RunSnapshot fromJson(String json) {
        JsonElement root;
        try {
            root = StrictJson.parse(json);
        } catch (StrictJson.SyntaxError e) {
            throw new IllegalArgumentException("invalid snapshot: " + e.getMessage(), e);
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("invalid snapshot: not a JSON object");
        }
        JsonObject o = root.getAsJsonObject();
        if (!FORMAT.equals(string(o, "format")) || number(o, "version") != 1) {
            throw new IllegalArgumentException("invalid snapshot: not an " + FORMAT + " version 1");
        }
        String hash = string(o, "scriptHash");
        long savedAt = (long) number(o, "savedAtMillis");
        Map<String, Value> variables = new LinkedHashMap<>();
        if (!(o.get("variables") instanceof JsonObject vars)) {
            throw new IllegalArgumentException("invalid snapshot: variables must be an object");
        }
        for (Map.Entry<String, JsonElement> e : vars.entrySet()) {
            if (!(e.getValue() instanceof JsonPrimitive p)) {
                throw new IllegalArgumentException("invalid snapshot: variable " + e.getKey() + " is not a value");
            }
            variables.put(e.getKey(), p.isBoolean() ? Value.of(p.getAsBoolean())
                    : p.isNumber() ? Value.of(p.getAsDouble()) : Value.of(p.getAsString()));
        }
        List<String> active = new ArrayList<>();
        if (!(o.get("activeHandlers") instanceof com.google.gson.JsonArray handlers)) {
            throw new IllegalArgumentException("invalid snapshot: activeHandlers must be an array");
        }
        for (JsonElement h : handlers) {
            if (!(h instanceof JsonPrimitive p) || !p.isString()) {
                throw new IllegalArgumentException("invalid snapshot: activeHandlers must hold strings");
            }
            active.add(p.getAsString());
        }
        return new RunSnapshot(hash, variables, active, savedAt);
    }

    private static String string(JsonObject o, String key) {
        if (o.get(key) instanceof JsonPrimitive p && p.isString()) {
            return p.getAsString();
        }
        throw new IllegalArgumentException("invalid snapshot: " + key + " must be a string");
    }

    private static double number(JsonObject o, String key) {
        if (o.get(key) instanceof JsonPrimitive p && p.isNumber()) {
            return p.getAsDouble();
        }
        throw new IllegalArgumentException("invalid snapshot: " + key + " must be a number");
    }
}
