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

import baritone.alto.script.codec.ParseResult;
import baritone.alto.script.codec.ScriptCodec;
import baritone.alto.script.codec.ScriptFormatException;
import baritone.alto.script.lang.ActionArg;
import baritone.alto.script.lang.ActionType;
import baritone.alto.script.lang.Diagnostic;
import baritone.alto.script.lang.DiagnosticCode;
import baritone.alto.script.lang.Expr;
import baritone.alto.script.lang.Limits;
import baritone.alto.script.lang.Script;
import baritone.alto.script.lang.Stmt;
import baritone.alto.script.lang.Value;
import baritone.alto.script.lang.Where;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static baritone.alto.script.Scripts.expect;
import static baritone.alto.script.Scripts.j;
import static baritone.alto.script.Scripts.onStart;
import static baritone.alto.script.Scripts.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodecTest {

    // ------------------------------------------------------------------------------------------ round trip

    @Test
    void everyBlockSurvivesARoundTrip() {
        Script first = parse(Fixtures.EVERYTHING);
        String canonical = ScriptCodec.toCanonicalJson(first);
        Script second = parse(canonical);
        assertEquals(first, second);
        assertEquals(canonical, ScriptCodec.toCanonicalJson(second));
        assertEquals(first, parse(ScriptCodec.toPrettyJson(first)));
        assertEquals(6, first.handlers().size());
        assertEquals(33, first.handlers().get(0).body().size());
    }

    @Test
    void canonicalFormIsCompactWithFixedKeyOrder() {
        String json = j("{'events':[{'body':[{'count':16.0,'item':'oak_planks','block':'craft'}],'event':'on_start'}],"
                + "'name':'Planks','version':1,'format':'alto-script'}");
        assertEquals(j("{'format':'alto-script','version':1,'name':'Planks','description':'','variables':[],"
                        + "'procedures':[],'events':[{'event':'on_start','body':[{'block':'craft','item':'oak_planks','count':16}]}]}"),
                ScriptCodec.toCanonicalJson(parse(json)));
    }

    @Test
    void hashIgnoresFormattingKeyOrderNumberSpellingAndDefaults() {
        String a = onStart("{'block':'message','text':{'expr':'item_count','item':'oak_log'}}",
                "{'block':'wait','seconds':2}");
        String b = j("{ \n 'events' : [ { 'event':'on_start', 'body': [ {'text':{'where':'inventory','item':'oak_log','expr':'item_count'},"
                + "'block':'message'}, {'seconds':2.0,'block':'wait'} ] } ],\n 'procedures':[], 'variables':[],"
                + " 'name':'test', 'version':1.0, 'format':'alto-script' }");
        assertEquals(ScriptCodec.hash(parse(a)), ScriptCodec.hash(parse(b)));
        String elseEmpty = onStart("{'block':'if','condition':true,'then':[{'block':'wait','seconds':1}],'else':[]}");
        String elseMissing = onStart("{'block':'if','condition':true,'then':[{'block':'wait','seconds':1}]}");
        assertEquals(ScriptCodec.hash(parse(elseEmpty)), ScriptCodec.hash(parse(elseMissing)));
    }

    @Test
    void hashChangesWithContent() {
        String a = ScriptCodec.hash(parse(onStart("{'block':'wait','seconds':2}")));
        String b = ScriptCodec.hash(parse(onStart("{'block':'wait','seconds':3}")));
        String c = ScriptCodec.hash(parse(onStart("{'block':'wait','seconds':2}").replace("\"test\"", "\"other\"")));
        assertNotEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(64, a.length());
        assertTrue(a.matches("[0-9a-f]{64}"));
    }

    @Test
    void hashOfAFixedScriptIsPinned() {
        // Changing the canonical form changes every saved script's identity: update docs and this value on purpose.
        Script s = parse(onStart("{'block':'craft','item':'minecraft:oak_planks','count':16}"));
        assertEquals(j("{'format':'alto-script','version':1,'name':'test','description':'','variables':[],'procedures':[],"
                + "'events':[{'event':'on_start','body':[{'block':'craft','item':'minecraft:oak_planks','count':16}]}]}"),
                ScriptCodec.toCanonicalJson(s));
        assertEquals(ScriptCodec.hash(s), ScriptCodec.hash(parse(ScriptCodec.toPrettyJson(s))));
    }

    @Test
    void nonWholeNumbersAndUnicodeAreWrittenExactly() {
        Script s = parse(onStart("{'block':'wait','seconds':0.25}", "{'block':'message','text':'café \\u2603 \\\"q\\\"'}"));
        String canonical = ScriptCodec.toCanonicalJson(s);
        assertTrue(canonical.contains("\"seconds\":0.25"), canonical);
        assertTrue(canonical.contains("café ☃ \\\"q\\\""), canonical);
        assertEquals(s, parse(canonical));
    }

    @Test
    void parsesFieldsIntoTypedNodes() {
        Script s = parse(onStart("{'block':'craft','item':{'expr':'var','name':'x'},'count':{'expr':'add','a':1,'b':2}}"));
        Stmt.Action craft = assertInstanceOf(Stmt.Action.class, s.handlers().get(0).body().get(0));
        assertEquals(ActionType.CRAFT, craft.type());
        assertEquals("events[0].body[0]", craft.path());
        Expr.Binary count = assertInstanceOf(Expr.Binary.class, craft.expr("count"));
        assertEquals("events[0].body[0].count", count.path());
        assertEquals("events[0].body[0].count.b", count.b().path());
        assertEquals(new Expr.Literal("events[0].body[0].count.a", Value.of(1)), count.a());
        Expr.ItemCount ic = assertInstanceOf(Expr.ItemCount.class, parse(onStart(
                "{'block':'message','text':{'expr':'item_count','item':'x'}}")).handlers().get(0).body().get(0) instanceof Stmt.Action a
                ? a.expr("text") : null);
        assertEquals(Where.INVENTORY, ic.where());
    }

    @Test
    void optionalFieldsMayBeLeftOut() {
        Script s = parse(onStart("{'block':'farm'}", "{'block':'deposit'}", "{'block':'set_filter','x':1,'y':2,'z':3,'item':'x'}",
                "{'block':'goto','target':{'type':'home'}}", "{'block':'wait_until','condition':true}"));
        Stmt.Action farm = (Stmt.Action) s.handlers().get(0).body().get(0);
        assertTrue(farm.args().isEmpty());
        Stmt.Action filter = (Stmt.Action) s.handlers().get(0).body().get(2);
        assertFalse(filter.args().containsKey("face"));
        Stmt.WaitUntil wait = (Stmt.WaitUntil) s.handlers().get(0).body().get(4);
        assertNull(wait.timeoutSeconds());
        assertInstanceOf(ActionArg.TargetArg.class, ((Stmt.Action) s.handlers().get(0).body().get(3)).args().get("target"));
    }

    @Test
    void readerInputWorks() throws IOException {
        ParseResult r = ScriptCodec.parse(new StringReader(onStart("{'block':'eat'}")));
        assertTrue(r.ok());
    }

    // ------------------------------------------------------------------------------------------ structural errors

    @Test
    void syntaxErrorsAreReported() {
        Diagnostic d = expect("{\"format\": ", DiagnosticCode.JSON_SYNTAX, "");
        assertTrue(d.isError());
        expect(onStart("{'block':'eat'}") + " trailing", DiagnosticCode.JSON_SYNTAX, "");
        expect(onStart("{'block':'eat'} /* comment */"), DiagnosticCode.JSON_SYNTAX, "");
    }

    @Test
    void duplicateKeysAreRejected() {
        expect(onStart("{'block':'eat','block':'eat'}"), DiagnosticCode.JSON_SYNTAX, "events[0].body[0].block");
    }

    @Test
    void wrongFormatAndVersionAreRejected() {
        expect(j("{'format':'other','version':1,'name':'x','events':[]}"), DiagnosticCode.UNSUPPORTED_FORMAT, "format");
        Diagnostic d = expect(j("{'format':'alto-script','version':2,'name':'x','events':[]}"),
                DiagnosticCode.UNSUPPORTED_VERSION, "version");
        assertTrue(d.message().contains("version 2"), d.message());
        expect("[1,2]", DiagnosticCode.WRONG_JSON_TYPE, "");
    }

    @Test
    void unknownBlocksSuggestTheClosestName() {
        Diagnostic d = expect(onStart("{'block':'eat'}", "{'block':'crafts','item':'x','count':1}"),
                DiagnosticCode.UNKNOWN_BLOCK, "events[0].body[1]");
        assertTrue(d.message().contains("did you mean 'craft'"), d.message());
        expect(onStart("{'block':'message','text':{'expr':'healt'}}"), DiagnosticCode.UNKNOWN_EXPR, "events[0].body[0].text");
        expect(Scripts.script("", "", "{'event':'on_begin','body':[]}"), DiagnosticCode.UNKNOWN_EVENT, "events[0]");
    }

    @Test
    void missingAndUnknownFieldsHavePreciseLocations() {
        String json = Scripts.script("", "", "{'event':'on_start','body':[{'block':'eat'},{'block':'eat'},"
                + "{'block':'repeat','body':[{'block':'wait','seconds':1}]}]}");
        expect(json, DiagnosticCode.MISSING_FIELD, "events[0].body[2].times");
        Diagnostic d = expect(onStart("{'block':'craft','item':'x','cout':3}"), DiagnosticCode.UNKNOWN_FIELD, "events[0].body[0].cout");
        assertTrue(d.message().contains("did you mean 'count'"), d.message());
        expect(onStart("{'block':'craft','item':'x','cout':3}"), DiagnosticCode.MISSING_FIELD, "events[0].body[0].count");
        expect(j("{'format':'alto-script','version':1,'events':[]}"), DiagnosticCode.MISSING_FIELD, "name");
        expect(j("{'format':'alto-script','version':1,'name':'x','events':[],'extra':1}"), DiagnosticCode.UNKNOWN_FIELD, "extra");
    }

    @Test
    void wrongJsonTypesAreReported() {
        expect(onStart("{'block':'repeat','times':[1],'body':[]}"), DiagnosticCode.WRONG_JSON_TYPE, "events[0].body[0].times");
        expect(onStart("{'block':'repeat','times':1,'body':{}}"), DiagnosticCode.WRONG_JSON_TYPE, "events[0].body[0].body");
        expect(onStart("{'block':'command','command':{'expr':'var','name':'x'}}"), DiagnosticCode.WRONG_JSON_TYPE,
                "events[0].body[0].command");
        expect(onStart("{'block':'deposit','except':'bread'}"), DiagnosticCode.WRONG_JSON_TYPE, "events[0].body[0].except");
        expect(onStart("{'block':'wait','seconds':null}"), DiagnosticCode.WRONG_JSON_TYPE, "events[0].body[0].seconds");
        expect(Scripts.withVars("{'name':'v','initial':{'expr':'health'}}", "{'block':'eat'}"), DiagnosticCode.WRONG_JSON_TYPE,
                "variables[0].initial");
        expect(Scripts.script("", "", "{'event':'every','seconds':'5','body':[]}"), DiagnosticCode.WRONG_JSON_TYPE, "events[0].seconds");
    }

    @Test
    void invalidChoicesAreReported() {
        expect(onStart("{'block':'place_station','kind':'anvil'}"), DiagnosticCode.INVALID_CHOICE, "events[0].body[0].kind");
        expect(onStart("{'block':'message','text':{'expr':'item_count','item':'x','where':'chest'}}"), DiagnosticCode.INVALID_CHOICE,
                "events[0].body[0].text.where");
        expect(onStart("{'block':'goto','target':{'type':'moon'}}"), DiagnosticCode.INVALID_CHOICE, "events[0].body[0].target.type");
        expect(Scripts.script("", "{'name':'p','params':[{'name':'a','type':'list'}],'body':[]}", Scripts.startHandler()),
                DiagnosticCode.INVALID_CHOICE, "procedures[0].params[0].type");
    }

    @Test
    void allStructuralErrorsAreCollectedInOnePass() {
        ParseResult r = ScriptCodec.parse(onStart("{'block':'nope'}", "{'block':'craft'}", "{'block':'wait','seconds':1,'x':2}"));
        assertFalse(r.ok());
        assertNull(r.script());
        List<DiagnosticCode> codes = r.diagnostics().stream().map(Diagnostic::code).toList();
        assertEquals(List.of(DiagnosticCode.UNKNOWN_BLOCK, DiagnosticCode.MISSING_FIELD, DiagnosticCode.MISSING_FIELD,
                DiagnosticCode.UNKNOWN_FIELD), codes);
        ScriptFormatException e = assertThrows(ScriptFormatException.class, () -> ScriptCodec.parseOrThrow(onStart("{'block':'nope'}")));
        assertEquals(1, e.diagnostics().size());
    }

    @Test
    void oversizedScriptsAreRejected() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < Limits.MAX_JSON_DEPTH + 5; i++) {
            deep.append("{'expr':'not','a':");
        }
        deep.append("true");
        deep.append("}".repeat(Limits.MAX_JSON_DEPTH + 5));
        List<Diagnostic> deepResult = Scripts.check(onStart("{'block':'message','text':" + deep + "}"));
        assertEquals(DiagnosticCode.JSON_SYNTAX, deepResult.get(0).code());
        assertTrue(deepResult.get(0).message().contains("nested"), deepResult.get(0).message());
        assertTrue(deepResult.get(0).path().startsWith("events[0].body[0].text.a.a"), deepResult.get(0).path());

        String huge = " ".repeat(Limits.MAX_SCRIPT_CHARS + 1);
        expect(huge, DiagnosticCode.TOO_LARGE, "");

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < Limits.MAX_NODES + 1; i++) {
            many.append(i == 0 ? "" : ",").append("{'block':'eat'}");
        }
        assertTrue(Scripts.codes(onStart(many.toString())).contains(DiagnosticCode.TOO_LARGE));
    }
}
