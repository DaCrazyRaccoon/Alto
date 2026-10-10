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

import baritone.alto.script.lang.Limits;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;

/**
 * Strict JSON reading: no comments or other lenient syntax, no duplicate keys, finite numbers only, limited nesting.
 * Numbers become {@code double} primitives.
 */
public final class StrictJson {

    /** A syntax problem; {@code path} is where it was found (may be empty). */
    public static final class SyntaxError extends Exception {
        private final String path;

        SyntaxError(String path, String message) {
            super(message);
            this.path = path;
        }

        public String path() {
            return path;
        }
    }

    private StrictJson() {
    }

    public static JsonElement parse(String text) throws SyntaxError {
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setLenient(false);
        try {
            JsonElement root = read(reader, "", 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new SyntaxError("", "unexpected text after the end of the JSON document");
            }
            return root;
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            String message = String.valueOf(e.getMessage())
                    .replace("Use JsonReader.setLenient(true) to accept malformed JSON", "malformed JSON");
            throw new SyntaxError("", "invalid JSON: " + message);
        }
    }

    private static JsonElement read(JsonReader r, String path, int depth) throws IOException, SyntaxError {
        if (depth > Limits.MAX_JSON_DEPTH) {
            throw new SyntaxError(path, "the JSON is nested more than " + Limits.MAX_JSON_DEPTH + " levels deep");
        }
        switch (r.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                r.beginObject();
                while (r.hasNext()) {
                    String key = r.nextName();
                    String childPath = path.isEmpty() ? key : path + "." + key;
                    if (object.has(key)) {
                        throw new SyntaxError(childPath, "duplicate key '" + key + "'");
                    }
                    object.add(key, read(r, childPath, depth + 1));
                }
                r.endObject();
                return object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                r.beginArray();
                int i = 0;
                while (r.hasNext()) {
                    array.add(read(r, path + "[" + i++ + "]", depth + 1));
                }
                r.endArray();
                return array;
            }
            case STRING -> {
                return new JsonPrimitive(r.nextString());
            }
            case NUMBER -> {
                String raw = r.nextString();
                double value = Double.parseDouble(raw);
                if (!Double.isFinite(value)) {
                    throw new SyntaxError(path, "number out of range: " + raw);
                }
                return new JsonPrimitive(value);
            }
            case BOOLEAN -> {
                return new JsonPrimitive(r.nextBoolean());
            }
            case NULL -> {
                r.nextNull();
                return JsonNull.INSTANCE;
            }
            default -> throw new SyntaxError(path, "unexpected " + r.peek());
        }
    }
}
