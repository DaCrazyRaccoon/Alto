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

import baritone.alto.script.codec.StrictJson;
import baritone.alto.script.lang.ActionType;
import baritone.alto.script.lang.EventType;
import baritone.alto.script.lang.Expr;
import baritone.alto.script.lang.FieldSpec;
import baritone.alto.script.lang.Fields;
import baritone.alto.script.lang.JsonNamed;
import baritone.alto.script.lang.Limits;
import baritone.alto.script.lang.Stmt;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static baritone.alto.script.ExamplesTest.examples;
import static baritone.alto.script.ExamplesTest.projectFile;
import static baritone.alto.script.ExamplesTest.read;
import static baritone.alto.script.Scripts.onStart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps docs/alto/alto-script.schema.json (used by the browser editor) in step with the Java catalog.
 */
class SchemaTest {

    private static final JsonObject SCHEMA = JsonParser.parseString(read(projectFile("docs/alto/alto-script.schema.json"))).getAsJsonObject();
    private static final JsonObject DEFS = SCHEMA.getAsJsonObject("$defs");
    private static final MiniSchema CHECKER = new MiniSchema(SCHEMA);

    private static JsonObject def(String name) {
        JsonObject d = DEFS.getAsJsonObject(name);
        assertNotNull(d, "missing $defs/" + name);
        return d;
    }

    private static Set<String> keys(JsonObject o) {
        return new TreeSet<>(o.keySet());
    }

    private static Set<String> strings(JsonArray array) {
        Set<String> out = new TreeSet<>();
        array.forEach(e -> out.add(e.getAsString()));
        return out;
    }

    private static Set<String> refs(JsonObject oneOf) {
        Set<String> out = new TreeSet<>();
        oneOf.getAsJsonArray("oneOf").forEach(e -> out.add(e.getAsJsonObject().get("$ref").getAsString()));
        return out;
    }

    private static List<String> validate(String json) {
        try {
            return CHECKER.validate(StrictJson.parse(json));
        } catch (StrictJson.SyntaxError e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void schemaIsDraft2020() {
        assertEquals("https://json-schema.org/draft/2020-12/schema", SCHEMA.get("$schema").getAsString());
        assertEquals("alto-script", SCHEMA.getAsJsonObject("properties").getAsJsonObject("format").get("const").getAsString());
    }

    @Test
    void everyActionBlockHasTheCatalogFields() {
        for (ActionType type : ActionType.values()) {
            JsonObject block = def("block_" + type.jsonName());
            JsonObject props = block.getAsJsonObject("properties");
            Set<String> expected = new TreeSet<>(Set.of("block"));
            Set<String> required = new TreeSet<>(Set.of("block"));
            for (FieldSpec f : type.fields()) {
                expected.add(f.name());
                if (f.required()) {
                    required.add(f.name());
                }
                checkField(type.jsonName() + "." + f.name(), f, props.get(f.name()));
            }
            assertEquals(expected, keys(props), type.jsonName());
            assertEquals(required, strings(block.getAsJsonArray("required")), type.jsonName());
            assertFalse(block.get("additionalProperties").getAsBoolean());
        }
    }

    /** The schema's literal rules for one field equal the FieldSpec. */
    private static void checkField(String what, FieldSpec f, JsonElement property) {
        JsonObject resolved = CHECKER.resolve(property);
        JsonObject literal = resolved.has("anyOf") ? CHECKER.resolve(resolved.getAsJsonArray("anyOf").get(0)) : resolved;
        switch (f.kind()) {
            case NUMBER, INTEGER -> {
                assertEquals(f.kind() == baritone.alto.script.lang.FieldKind.INTEGER ? "integer" : "number",
                        literal.get("type").getAsString(), what);
                assertEquals(f.min(), literal.get("minimum").getAsDouble(), what);
                assertEquals(f.max(), literal.get("maximum").getAsDouble(), what);
            }
            case STRING, TEXT -> assertEquals((int) f.max(), literal.get("maxLength").getAsInt(), what);
            case ANY -> assertEquals(def("anyValue"), resolved, what);
            case ID -> assertEquals(def("id"), literal, what);
            case FILE_NAME -> assertEquals(def("fileName"), literal, what);
            case ID_LIST -> assertEquals(def("idList"), resolved, what);
            case TARGET -> assertEquals(def("target"), resolved, what);
            case CHOICE -> assertEquals(new TreeSet<>(f.choices()), strings(resolved.getAsJsonArray("enum")), what);
        }
    }

    @Test
    void statementsExpressionsAndEventsAreAllListed() {
        Set<String> blocks = new TreeSet<>();
        for (String name : JsonNamed.names(ActionType.values())) {
            blocks.add("#/$defs/block_" + name);
        }
        for (String name : Stmt.CONTROL_BLOCKS) {
            def("block_" + name);
            blocks.add("#/$defs/block_" + name);
        }
        assertEquals(blocks, refs(def("statement")));

        Set<String> exprs = new TreeSet<>();
        for (String kind : Expr.kinds()) {
            def("expr_" + kind);
            exprs.add("#/$defs/expr_" + kind);
        }
        assertEquals(exprs, refs(def("exprObject")));

        Set<String> events = new TreeSet<>();
        for (EventType type : EventType.values()) {
            def("event_" + type.jsonName());
            events.add("#/$defs/event_" + type.jsonName());
        }
        assertEquals(events, refs(def("handler")));
    }

    @Test
    void discriminatorKeysAreNeverFieldNames() {
        Set<String> seen = new HashSet<>();
        for (ActionType type : ActionType.values()) {
            for (FieldSpec f : type.fields()) {
                assertNotEquals("block", f.name(), type.jsonName());
                seen.add(f.name());
            }
        }
        for (String kind : Expr.kinds()) {
            Set<String> fields = keys(def("expr_" + kind).getAsJsonObject("properties"));
            fields.remove("expr");
            assertFalse(fields.contains("expr"), kind);
        }
        assertTrue(seen.contains("material"));
    }

    @Test
    void limitsMatchTheJavaConstants() {
        JsonObject count = CHECKER.resolve(def("count").getAsJsonArray("anyOf").get(0));
        assertEquals(Limits.MAX_COUNT, count.get("maximum").getAsInt());
        JsonObject repeat = def("block_repeat").getAsJsonObject("properties").getAsJsonObject("times").getAsJsonArray("anyOf").get(0).getAsJsonObject();
        assertEquals(Fields.REPEAT_TIMES.max(), repeat.get("maximum").getAsDouble());
        JsonObject every = def("event_every").getAsJsonObject("properties").getAsJsonObject("seconds");
        assertEquals(Limits.MIN_EVERY_SECONDS, every.get("minimum").getAsDouble());
        assertEquals(Limits.MAX_WAIT_SECONDS, every.get("maximum").getAsDouble());
        JsonObject trigger = def("event_on_chat").getAsJsonObject("properties").getAsJsonObject("trigger");
        assertEquals(Limits.MAX_TRIGGER_LENGTH, trigger.get("maxLength").getAsInt());
        JsonObject health = def("event_on_health_below").getAsJsonObject("properties").getAsJsonObject("health");
        assertEquals(Fields.EVENT_HEALTH.min(), health.get("minimum").getAsDouble());
        assertEquals(Limits.MAX_NAME_LENGTH, def("name").get("maxLength").getAsInt());
    }

    @Test
    void examplesAndFixturesSatisfyTheSchema() {
        List<String> checked = new ArrayList<>();
        for (Path file : examples()) {
            assertEquals(List.of(), validate(read(file)), file::toString);
            checked.add(file.getFileName().toString());
        }
        assertEquals(List.of(), validate(Fixtures.EVERYTHING));
        assertTrue(checked.size() >= 5);
    }

    @Test
    void schemaRejectsWhatTheCodecRejects() {
        List<String> bad = List.of(
                onStart("{'block':'jump'}"),
                onStart("{'block':'craft','item':'oak_planks'}"),
                onStart("{'block':'craft','item':'oak_planks','count':0}"),
                onStart("{'block':'craft','item':'oak_planks','count':1.5}"),
                onStart("{'block':'craft','item':'Oak Planks','count':1}"),
                onStart("{'block':'craft','item':'oak_planks','count':1,'extra':true}"),
                onStart("{'block':'command','command':'/give @s diamond'}"),
                onStart("{'block':'build','schematic':'../x.schem','x':0,'y':0,'z':0}"),
                onStart("{'block':'place_station','kind':'anvil'}"),
                onStart("{'block':'message','text':{'expr':'sqrt','a':4}}"),
                onStart("{'block':'wait','seconds':86401}"),
                Scripts.script("", ""),
                Scripts.script("", "", "{'event':'on_chat','trigger':'/go','body':[]}"),
                Scripts.j("{'format':'alto-script','version':2,'name':'x','events':[{'event':'on_start','body':[]}]}"));
        for (String json : bad) {
            assertFalse(validate(json).isEmpty(), json);
            assertFalse(ScriptLoader.load(json).runnable(), json);
        }
    }
}
