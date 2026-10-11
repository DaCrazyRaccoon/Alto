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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A minimal JSON Schema (draft 2020-12) checker for the keywords alto-script.schema.json uses. Unknown keywords
 * throw, so the test notices when the schema starts using something this checker does not understand.
 */
final class MiniSchema {

    private static final Set<String> ANNOTATIONS = Set.of("$schema", "$id", "title", "description", "$comment",
            "examples", "$defs");

    private final JsonObject root;

    MiniSchema(JsonObject root) {
        this.root = root;
    }

    /** Errors as {@code $.events[0].body[1]: message}; empty when valid. */
    List<String> validate(JsonElement instance) {
        List<String> errors = new ArrayList<>();
        check(root, instance, "$", errors);
        return errors;
    }

    JsonObject resolve(JsonElement schema) {
        JsonObject s = schema.getAsJsonObject();
        while (s.has("$ref")) {
            String ref = s.get("$ref").getAsString();
            if (!ref.startsWith("#/$defs/")) {
                throw new IllegalArgumentException("unsupported $ref " + ref);
            }
            s = root.getAsJsonObject("$defs").getAsJsonObject(ref.substring("#/$defs/".length()));
        }
        return s;
    }

    private boolean valid(JsonElement schema, JsonElement value, String path) {
        List<String> errors = new ArrayList<>();
        check(schema.getAsJsonObject(), value, path, errors);
        return errors.isEmpty();
    }

    private void check(JsonObject schema, JsonElement value, String path, List<String> errors) {
        for (Map.Entry<String, JsonElement> keyword : schema.entrySet()) {
            String k = keyword.getKey();
            JsonElement arg = keyword.getValue();
            if (ANNOTATIONS.contains(k)) {
                continue;
            }
            switch (k) {
                case "$ref" -> check(resolve(schema), value, path, errors);
                case "type" -> {
                    boolean ok = false;
                    if (arg.isJsonArray()) {
                        for (JsonElement t : arg.getAsJsonArray()) {
                            ok |= hasType(value, t.getAsString());
                        }
                    } else {
                        ok = hasType(value, arg.getAsString());
                    }
                    if (!ok) {
                        errors.add(path + ": expected type " + arg);
                    }
                }
                case "const" -> {
                    if (!arg.equals(value)) {
                        errors.add(path + ": expected " + arg);
                    }
                }
                case "enum" -> {
                    if (!arg.getAsJsonArray().contains(value)) {
                        errors.add(path + ": expected one of " + arg);
                    }
                }
                case "properties" -> {
                    if (value.isJsonObject()) {
                        for (Map.Entry<String, JsonElement> p : arg.getAsJsonObject().entrySet()) {
                            JsonElement v = value.getAsJsonObject().get(p.getKey());
                            if (v != null) {
                                check(p.getValue().getAsJsonObject(), v, path + "." + p.getKey(), errors);
                            }
                        }
                    }
                }
                case "required" -> {
                    if (value.isJsonObject()) {
                        for (JsonElement r : arg.getAsJsonArray()) {
                            if (!value.getAsJsonObject().has(r.getAsString())) {
                                errors.add(path + ": missing " + r.getAsString());
                            }
                        }
                    }
                }
                case "additionalProperties" -> {
                    if (value.isJsonObject()) {
                        JsonObject props = schema.has("properties") ? schema.getAsJsonObject("properties") : new JsonObject();
                        for (String key : value.getAsJsonObject().keySet()) {
                            if (props.has(key)) {
                                continue;
                            }
                            if (arg.isJsonPrimitive() && !arg.getAsBoolean()) {
                                errors.add(path + ": unexpected property " + key);
                            } else if (arg.isJsonObject()) {
                                check(arg.getAsJsonObject(), value.getAsJsonObject().get(key), path + "." + key, errors);
                            }
                        }
                    }
                }
                case "propertyNames" -> {
                    if (value.isJsonObject()) {
                        for (String key : value.getAsJsonObject().keySet()) {
                            check(arg.getAsJsonObject(), new JsonPrimitive(key), path + "{" + key + "}", errors);
                        }
                    }
                }
                case "items" -> {
                    if (value.isJsonArray()) {
                        JsonArray array = value.getAsJsonArray();
                        for (int i = 0; i < array.size(); i++) {
                            check(arg.getAsJsonObject(), array.get(i), path + "[" + i + "]", errors);
                        }
                    }
                }
                case "minItems" -> {
                    if (value.isJsonArray() && value.getAsJsonArray().size() < arg.getAsInt()) {
                        errors.add(path + ": fewer than " + arg + " items");
                    }
                }
                case "oneOf", "anyOf" -> {
                    int matches = 0;
                    for (JsonElement option : arg.getAsJsonArray()) {
                        if (valid(option, value, path)) {
                            matches++;
                        }
                    }
                    if (k.equals("oneOf") ? matches != 1 : matches == 0) {
                        errors.add(path + ": " + matches + " of the " + k + " options match");
                    }
                }
                case "minimum" -> {
                    if (isNumber(value) && value.getAsDouble() < arg.getAsDouble()) {
                        errors.add(path + ": below " + arg);
                    }
                }
                case "maximum" -> {
                    if (isNumber(value) && value.getAsDouble() > arg.getAsDouble()) {
                        errors.add(path + ": above " + arg);
                    }
                }
                case "minLength" -> {
                    if (isString(value) && value.getAsString().codePointCount(0, value.getAsString().length()) < arg.getAsInt()) {
                        errors.add(path + ": shorter than " + arg);
                    }
                }
                case "maxLength" -> {
                    if (isString(value) && value.getAsString().codePointCount(0, value.getAsString().length()) > arg.getAsInt()) {
                        errors.add(path + ": longer than " + arg);
                    }
                }
                case "pattern" -> {
                    if (isString(value) && !Pattern.compile(arg.getAsString()).matcher(value.getAsString()).find()) {
                        errors.add(path + ": does not match " + arg.getAsString());
                    }
                }
                default -> throw new IllegalArgumentException("MiniSchema does not support the keyword " + k);
            }
        }
    }

    private static boolean isNumber(JsonElement v) {
        return v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber();
    }

    private static boolean isString(JsonElement v) {
        return v.isJsonPrimitive() && v.getAsJsonPrimitive().isString();
    }

    private static boolean hasType(JsonElement v, String type) {
        return switch (type) {
            case "object" -> v.isJsonObject();
            case "array" -> v.isJsonArray();
            case "string" -> isString(v);
            case "number" -> isNumber(v);
            case "integer" -> isNumber(v) && v.getAsDouble() == Math.rint(v.getAsDouble());
            case "boolean" -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean();
            case "null" -> v.isJsonNull();
            default -> throw new IllegalArgumentException("unknown type " + type);
        };
    }
}
