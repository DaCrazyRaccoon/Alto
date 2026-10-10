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

import baritone.alto.script.lang.Diagnostic;
import baritone.alto.script.lang.DiagnosticCode;
import baritone.alto.script.lang.Severity;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static baritone.alto.script.Scripts.check;
import static baritone.alto.script.Scripts.codes;
import static baritone.alto.script.Scripts.expect;
import static baritone.alto.script.Scripts.handler;
import static baritone.alto.script.Scripts.j;
import static baritone.alto.script.Scripts.onStart;
import static baritone.alto.script.Scripts.script;
import static baritone.alto.script.Scripts.startHandler;
import static baritone.alto.script.Scripts.withVars;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorTest {

    private static final String WAIT = "{'block':'wait','seconds':1}";
    private static final String COUNT_VAR = "{'name':'count','initial':0}";

    /** One script per diagnostic code that produces it. */
    private static final Map<DiagnosticCode, String> TRIGGERS = new EnumMap<>(DiagnosticCode.class);

    static {
        TRIGGERS.put(DiagnosticCode.JSON_SYNTAX, "{");
        TRIGGERS.put(DiagnosticCode.TOO_LARGE, " ".repeat(1_000_001));
        TRIGGERS.put(DiagnosticCode.UNSUPPORTED_FORMAT, j("{'format':'scratch','version':1}"));
        TRIGGERS.put(DiagnosticCode.UNSUPPORTED_VERSION, j("{'format':'alto-script','version':9}"));
        TRIGGERS.put(DiagnosticCode.MISSING_FIELD, onStart("{'block':'craft','item':'x'}"));
        TRIGGERS.put(DiagnosticCode.UNKNOWN_FIELD, onStart("{'block':'eat','fast':true}"));
        TRIGGERS.put(DiagnosticCode.WRONG_JSON_TYPE, onStart("{'block':'wait','seconds':[1]}"));
        TRIGGERS.put(DiagnosticCode.UNKNOWN_BLOCK, onStart("{'block':'jump'}"));
        TRIGGERS.put(DiagnosticCode.UNKNOWN_EXPR, onStart("{'block':'message','text':{'expr':'sqrt','a':4}}"));
        TRIGGERS.put(DiagnosticCode.UNKNOWN_EVENT, script("", "", "{'event':'on_death','body':[]}"));
        TRIGGERS.put(DiagnosticCode.INVALID_CHOICE, onStart("{'block':'board_vehicle','kind':'horse'}"));
        TRIGGERS.put(DiagnosticCode.INVALID_NAME, withVars("{'name':'2fast','initial':0}", "{'block':'eat'}"));
        TRIGGERS.put(DiagnosticCode.DUPLICATE_NAME, withVars(COUNT_VAR + "," + COUNT_VAR, "{'block':'change_var','name':'count','delta':1}"));
        TRIGGERS.put(DiagnosticCode.DUPLICATE_EVENT, script("", "", startHandler(), startHandler()));
        TRIGGERS.put(DiagnosticCode.NO_EVENTS, script("", ""));
        TRIGGERS.put(DiagnosticCode.UNKNOWN_VARIABLE, onStart("{'block':'set_var','name':'cout','value':1}"));
        TRIGGERS.put(DiagnosticCode.UNKNOWN_PROCEDURE, onStart("{'block':'call','procedure':'nowhere'}"));
        TRIGGERS.put(DiagnosticCode.UNKNOWN_PARAM, script("", "{'name':'p','params':[],'body':[{'block':'message','text':{'expr':'param','name':'q'}}]}",
                startHandler("{'block':'call','procedure':'p'}")));
        TRIGGERS.put(DiagnosticCode.PARAM_OUTSIDE_PROCEDURE, onStart("{'block':'message','text':{'expr':'param','name':'item'}}"));
        TRIGGERS.put(DiagnosticCode.MISSING_ARGUMENT, script("", "{'name':'p','params':[{'name':'n','type':'number'}],'body':[]}",
                startHandler("{'block':'call','procedure':'p','args':{}}")));
        TRIGGERS.put(DiagnosticCode.UNKNOWN_ARGUMENT, script("", "{'name':'p','params':[],'body':[]}",
                startHandler("{'block':'call','procedure':'p','args':{'n':1}}")));
        TRIGGERS.put(DiagnosticCode.TYPE_MISMATCH, onStart("{'block':'craft','item':'x','count':'many'}"));
        TRIGGERS.put(DiagnosticCode.NOT_WHOLE_NUMBER, onStart("{'block':'craft','item':'x','count':1.5}"));
        TRIGGERS.put(DiagnosticCode.OUT_OF_RANGE, onStart("{'block':'craft','item':'x','count':0}"));
        TRIGGERS.put(DiagnosticCode.INVALID_ID, onStart("{'block':'craft','item':'Oak Planks','count':1}"));
        TRIGGERS.put(DiagnosticCode.INVALID_FILE_NAME, onStart("{'block':'build','schematic':'../secret.schem','x':0,'y':0,'z':0}"));
        TRIGGERS.put(DiagnosticCode.INVALID_TEXT, onStart("{'block':'command','command':'  '}"));
        TRIGGERS.put(DiagnosticCode.RECURSION, script("", "{'name':'p','params':[],'body':[{'block':'call','procedure':'p'}]}",
                startHandler("{'block':'call','procedure':'p'}")));
        TRIGGERS.put(DiagnosticCode.BREAK_OUTSIDE_LOOP, onStart("{'block':'break'}"));
        TRIGGERS.put(DiagnosticCode.LOOP_WITHOUT_WAIT, withVars(COUNT_VAR,
                "{'block':'repeat','times':3,'body':[{'block':'change_var','name':'count','delta':1}]}"));
        TRIGGERS.put(DiagnosticCode.FOREVER_LOOP, onStart("{'block':'forever','body':[" + WAIT + "]}"));
        TRIGGERS.put(DiagnosticCode.SENDS_CHAT, onStart("{'block':'send_chat','text':'hi'}"));
        TRIGGERS.put(DiagnosticCode.RUNS_COMMAND, onStart("{'block':'command','command':'mine diamond_ore'}"));
        TRIGGERS.put(DiagnosticCode.LARGE_COUNT, onStart("{'block':'mine','block_or_item':'stone','count':20000}"));
        TRIGGERS.put(DiagnosticCode.UNREACHABLE_CODE, onStart("{'block':'stop'}", "{'block':'eat'}"));
        TRIGGERS.put(DiagnosticCode.UNUSED_PROCEDURE, script("", "{'name':'p','params':[],'body':[]}", startHandler()));
        TRIGGERS.put(DiagnosticCode.UNUSED_VARIABLE, withVars(COUNT_VAR, "{'block':'eat'}"));
    }

    @Test
    void everyDiagnosticCodeHasATriggeringScript() {
        assertEquals(EnumSet.allOf(DiagnosticCode.class), TRIGGERS.keySet());
        TRIGGERS.forEach((code, json) -> assertTrue(codes(json).contains(code), () -> code + " not reported, got " + check(json)));
    }

    @Test
    void severitiesMatchTheCodes() {
        for (DiagnosticCode code : DiagnosticCode.values()) {
            for (Diagnostic d : check(TRIGGERS.get(code))) {
                assertEquals(d.code().severity(), d.severity());
            }
        }
        assertEquals(Severity.WARNING, DiagnosticCode.FOREVER_LOOP.severity());
        assertEquals(Severity.ERROR, DiagnosticCode.LOOP_WITHOUT_WAIT.severity());
        assertEquals("loop_without_wait", DiagnosticCode.LOOP_WITHOUT_WAIT.jsonName());
    }

    @Test
    void cleanScriptHasNoDiagnostics() {
        assertEquals(List.of(), check(withVars(COUNT_VAR,
                "{'block':'repeat','times':3,'body':[{'block':'craft','item':'oak_planks','count':4},"
                        + "{'block':'change_var','name':'count','delta':1}]}",
                "{'block':'message','text':{'expr':'join','parts':['crafted ',{'expr':'var','name':'count'},' times']}}")));
    }

    @Test
    void everythingFixtureHasOnlyWarnings() {
        List<Diagnostic> diagnostics = check(Fixtures.EVERYTHING);
        for (Diagnostic d : diagnostics) {
            assertEquals(Severity.WARNING, d.severity(), d::toString);
        }
        Set<DiagnosticCode> codes = EnumSet.noneOf(DiagnosticCode.class);
        diagnostics.forEach(d -> codes.add(d.code()));
        assertEquals(EnumSet.of(DiagnosticCode.FOREVER_LOOP, DiagnosticCode.SENDS_CHAT, DiagnosticCode.RUNS_COMMAND), codes);
    }

    // ------------------------------------------------------------------------------------------ names

    @Test
    void namesMustBeIdentifiers() {
        expect(withVars("{'name':'my var','initial':0}", "{'block':'eat'}"), DiagnosticCode.INVALID_NAME, "variables[0].name");
        expect(script("", "{'name':'do-it','params':[{'name':'x y','type':'number'}],'body':[]}", startHandler()),
                DiagnosticCode.INVALID_NAME, "procedures[0].name");
        expect(script("", "{'name':'p','params':[{'name':'x y','type':'number'}],'body':[]}", startHandler()),
                DiagnosticCode.INVALID_NAME, "procedures[0].params[0].name");
    }

    @Test
    void duplicatesAreRejected() {
        expect(withVars(COUNT_VAR + "," + COUNT_VAR, "{'block':'change_var','name':'count','delta':1}"),
                DiagnosticCode.DUPLICATE_NAME, "variables[1].name");
        expect(script("", "{'name':'p','params':[],'body':[]},{'name':'p','params':[],'body':[]}", startHandler("{'block':'call','procedure':'p'}")),
                DiagnosticCode.DUPLICATE_NAME, "procedures[1].name");
        expect(script("", "{'name':'p','params':[{'name':'a','type':'number'},{'name':'a','type':'item'}],'body':[]}", startHandler()),
                DiagnosticCode.DUPLICATE_NAME, "procedures[0].params[1].name");
        Diagnostic d = expect(script("", "", handler("'event':'on_chat','trigger':'!go'"), handler("'event':'on_chat','trigger':' !GO '")),
                DiagnosticCode.DUPLICATE_EVENT, "events[1]");
        assertTrue(d.message().contains("events[0]"), d.message());
        expect(script("", "", handler("'event':'every','seconds':60"), handler("'event':'every','seconds':60.0")),
                DiagnosticCode.DUPLICATE_EVENT, "events[1]");
        assertFalse(codes(script("", "", handler("'event':'every','seconds':60"), handler("'event':'every','seconds':61")))
                .contains(DiagnosticCode.DUPLICATE_EVENT));
    }

    @Test
    void scriptNeedsHandlersAndAName() {
        expect(script("", ""), DiagnosticCode.NO_EVENTS, "events");
        expect(onStart().replace("\"name\":\"test\"", "\"name\":\" \""), DiagnosticCode.INVALID_TEXT, "name");
    }

    // ------------------------------------------------------------------------------------------ references

    @Test
    void unknownReferencesAreReportedWithHints() {
        Diagnostic d = expect(withVars(COUNT_VAR, "{'block':'change_var','name':'cout','delta':1}"),
                DiagnosticCode.UNKNOWN_VARIABLE, "events[0].body[0].name");
        assertTrue(d.message().contains("did you mean 'count'"), d.message());
        expect(withVars(COUNT_VAR, "{'block':'message','text':{'expr':'var','name':'nope'}}", "{'block':'change_var','name':'count','delta':1}"),
                DiagnosticCode.UNKNOWN_VARIABLE, "events[0].body[0].text");
        d = expect(script("", "{'name':'restock','params':[],'body':[]}", startHandler("{'block':'call','procedure':'restok'}")),
                DiagnosticCode.UNKNOWN_PROCEDURE, "events[0].body[0].procedure");
        assertTrue(d.message().contains("did you mean 'restock'"), d.message());
    }

    @Test
    void paramsOnlyInsideTheirProcedure() {
        String procs = "{'name':'p','params':[{'name':'item','type':'item'}],'body':[{'block':'craft','item':{'expr':'param','name':'itme'},'count':1}]}";
        Diagnostic d = expect(script("", procs, startHandler("{'block':'call','procedure':'p','args':{'item':'x'}}")),
                DiagnosticCode.UNKNOWN_PARAM, "procedures[0].body[0].item");
        assertTrue(d.message().contains("did you mean 'item'"), d.message());
        expect(onStart("{'block':'craft','item':{'expr':'param','name':'item'},'count':1}"),
                DiagnosticCode.PARAM_OUTSIDE_PROCEDURE, "events[0].body[0].item");
    }

    @Test
    void callArgumentsMustMatchTheParameters() {
        String procs = "{'name':'p','params':[{'name':'item','type':'item'},{'name':'n','type':'number'}],'body':[]}";
        expect(script("", procs, startHandler("{'block':'call','procedure':'p','args':{'item':'oak_log'}}")),
                DiagnosticCode.MISSING_ARGUMENT, "events[0].body[0].args");
        expect(script("", procs, startHandler("{'block':'call','procedure':'p','args':{'item':'oak_log','n':1,'m':2}}")),
                DiagnosticCode.UNKNOWN_ARGUMENT, "events[0].body[0].args.m");
        expect(script("", procs, startHandler("{'block':'call','procedure':'p','args':{'item':5,'n':1}}")),
                DiagnosticCode.TYPE_MISMATCH, "events[0].body[0].args.item");
        expect(script("", procs, startHandler("{'block':'call','procedure':'p','args':{'item':'Oak Log','n':1}}")),
                DiagnosticCode.INVALID_ID, "events[0].body[0].args.item");
        assertEquals(List.of(), check(script("", procs.replace("'body':[]", "'body':[{'block':'craft','item':{'expr':'param','name':'item'},'count':{'expr':'param','name':'n'}}]"),
                startHandler("{'block':'call','procedure':'p','args':{'n':2,'item':'oak_planks'}}"))));
    }

    // ------------------------------------------------------------------------------------------ types and ranges

    @Test
    void typesAreCheckedStatically() {
        expect(onStart("{'block':'craft','item':'x','count':'many'}"), DiagnosticCode.TYPE_MISMATCH, "events[0].body[0].count");
        expect(onStart("{'block':'if','condition':1,'then':[]}"), DiagnosticCode.TYPE_MISMATCH, "events[0].body[0].condition");
        expect(withVars(COUNT_VAR, "{'block':'set_var','name':'count','value':'ten'}"), DiagnosticCode.TYPE_MISMATCH,
                "events[0].body[0].value");
        expect(withVars("{'name':'label','initial':'a'}", "{'block':'change_var','name':'label','delta':1}"),
                DiagnosticCode.TYPE_MISMATCH, "events[0].body[0].name");
        expect(onStart("{'block':'message','text':{'expr':'eq','a':1,'b':'1'}}"), DiagnosticCode.TYPE_MISMATCH, "events[0].body[0].text");
        expect(onStart("{'block':'message','text':{'expr':'add','a':1,'b':true}}"), DiagnosticCode.TYPE_MISMATCH, "events[0].body[0].text.b");
        expect(onStart("{'block':'message','text':{'expr':'not','a':0}}"), DiagnosticCode.TYPE_MISMATCH, "events[0].body[0].text.a");
        expect(onStart("{'block':'wait_until','condition':{'expr':'health'}}"), DiagnosticCode.TYPE_MISMATCH, "events[0].body[0].condition");
        expect(onStart("{'block':'goto','target':{'type':'coords','x':'a','y':1,'z':1}}"), DiagnosticCode.TYPE_MISMATCH,
                "events[0].body[0].target.x");
        expect(onStart("{'block':'craft','item':{'expr':'health'},'count':1}"), DiagnosticCode.TYPE_MISMATCH, "events[0].body[0].item");
        // variables keep the type of their initial value
        assertEquals(List.of(), check(withVars("{'name':'item','initial':'oak_log'},{'name':'n','initial':2},{'name':'on','initial':true}",
                "{'block':'if','condition':{'expr':'var','name':'on'},'then':[{'block':'craft','item':{'expr':'var','name':'item'},"
                        + "'count':{'expr':'mul','a':{'expr':'var','name':'n'},'b':4}}]}")));
    }

    @Test
    void literalRangesAreChecked() {
        expect(onStart("{'block':'craft','item':'x','count':100001}"), DiagnosticCode.OUT_OF_RANGE, "events[0].body[0].count");
        expect(onStart("{'block':'wait','seconds':-1}"), DiagnosticCode.OUT_OF_RANGE, "events[0].body[0].seconds");
        expect(onStart("{'block':'wait','seconds':86401}"), DiagnosticCode.OUT_OF_RANGE, "events[0].body[0].seconds");
        expect(onStart("{'block':'repeat','times':100001,'body':[" + WAIT + "]}"), DiagnosticCode.OUT_OF_RANGE, "events[0].body[0].times");
        expect(onStart("{'block':'repeat','times':2.5,'body':[" + WAIT + "]}"), DiagnosticCode.NOT_WHOLE_NUMBER, "events[0].body[0].times");
        expect(onStart("{'block':'goto','target':{'type':'coords','x':0.5,'y':1,'z':1}}"), DiagnosticCode.NOT_WHOLE_NUMBER,
                "events[0].body[0].target.x");
        expect(onStart("{'block':'goto','target':{'type':'coords','x':40000000,'y':1,'z':1}}"), DiagnosticCode.OUT_OF_RANGE,
                "events[0].body[0].target.x");
        expect(onStart("{'block':'wait_until','condition':true,'timeout_seconds':90000}"), DiagnosticCode.OUT_OF_RANGE,
                "events[0].body[0].timeout_seconds");
        expect(onStart("{'block':'message','text':{'expr':'random','min':0,'max':5000000000}}"), DiagnosticCode.OUT_OF_RANGE,
                "events[0].body[0].text.max");
        expect(onStart("{'block':'farm','range':0}"), DiagnosticCode.OUT_OF_RANGE, "events[0].body[0].range");
        expect(onStart("{'block':'deposit','only':[]}"), DiagnosticCode.OUT_OF_RANGE, "events[0].body[0].only");
        expect(onStart("{'block':'send_chat','text':'" + "x".repeat(257) + "'}"), DiagnosticCode.OUT_OF_RANGE, "events[0].body[0].text");
        expect(script("", "", handler("'event':'every','seconds':0.5")), DiagnosticCode.OUT_OF_RANGE, "events[0].seconds");
        expect(script("", "", handler("'event':'on_health_below','health':0")), DiagnosticCode.OUT_OF_RANGE, "events[0].health");
        expect(script("", "", handler("'event':'on_item_below','item':'bread','count':2.5")), DiagnosticCode.NOT_WHOLE_NUMBER,
                "events[0].count");
        assertFalse(codes(onStart("{'block':'wait','seconds':0}", "{'block':'wait','seconds':86400}")).contains(DiagnosticCode.OUT_OF_RANGE));
    }

    @Test
    void idsFileNamesAndTextsAreChecked() {
        expect(onStart("{'block':'craft','item':'Oak Planks','count':1}"), DiagnosticCode.INVALID_ID, "events[0].body[0].item");
        expect(onStart("{'block':'deposit','except':['bread','Bad Id']}"), DiagnosticCode.INVALID_ID, "events[0].body[0].except[1]");
        expect(onStart("{'block':'message','text':{'expr':'item_count','item':'a b'}}"), DiagnosticCode.INVALID_ID,
                "events[0].body[0].text.item");
        expect(script("", "", handler("'event':'on_item_below','item':'BREAD','count':2")), DiagnosticCode.INVALID_ID, "events[0].item");
        expect(onStart("{'block':'goto','target':{'type':'coords','x':1,'y':1,'z':1,'dimension':'The Nether'}}"),
                DiagnosticCode.INVALID_ID, "events[0].body[0].target.dimension");
        for (String bad : List.of("../x.schem", "a/b.schem", "C:\\\\x.schem", ".hidden", "")) {
            expect(onStart("{'block':'build','schematic':'" + bad + "','x':0,'y':0,'z':0}"), DiagnosticCode.INVALID_FILE_NAME,
                    "events[0].body[0].schematic");
        }
        assertFalse(codes(onStart("{'block':'build','schematic':'My House (v2).schem','x':0,'y':0,'z':0}"))
                .contains(DiagnosticCode.INVALID_FILE_NAME));
        expect(onStart("{'block':'command','command':'/give @s diamond'}"), DiagnosticCode.INVALID_TEXT, "events[0].body[0].command");
        expect(script("", "", handler("'event':'on_chat','trigger':'/go'")), DiagnosticCode.INVALID_TEXT, "events[0].trigger");
        expect(script("", "", handler("'event':'on_chat','trigger':''")), DiagnosticCode.INVALID_TEXT, "events[0].trigger");
        expect(onStart("{'block':'goto','target':{'type':'storage_zone','name':''}}"), DiagnosticCode.INVALID_TEXT,
                "events[0].body[0].target.name");
    }

    // ------------------------------------------------------------------------------------------ control flow

    @Test
    void recursionIsForbiddenDirectlyAndIndirectly() {
        Diagnostic d = expect(script("", "{'name':'a','params':[],'body':[{'block':'call','procedure':'b'}]},"
                        + "{'name':'b','params':[],'body':[" + WAIT + ",{'block':'call','procedure':'a'}]}",
                startHandler("{'block':'call','procedure':'a'}")), DiagnosticCode.RECURSION, "procedures[1].body[1]");
        assertTrue(d.message().contains("a -> b -> a"), d.message());
        assertEquals(1, codes(script("", "{'name':'a','params':[],'body':[{'block':'call','procedure':'a'}]}",
                startHandler("{'block':'call','procedure':'a'}"))).stream().filter(c -> c == DiagnosticCode.RECURSION).count());
        // calling the same procedure twice in a row is fine
        assertFalse(codes(script("", "{'name':'a','params':[],'body':[" + WAIT + "]},{'name':'b','params':[],'body':[{'block':'call','procedure':'a'},{'block':'call','procedure':'a'}]}",
                startHandler("{'block':'call','procedure':'b'}"))).contains(DiagnosticCode.RECURSION));
    }

    @Test
    void breakMustBeInsideALoopOfTheSameBody() {
        expect(onStart("{'block':'if','condition':true,'then':[{'block':'break'}]}"), DiagnosticCode.BREAK_OUTSIDE_LOOP,
                "events[0].body[0].then[0]");
        Diagnostic d = expect(script("", "{'name':'p','params':[],'body':[{'block':'break'}]}",
                        startHandler("{'block':'forever','body':[" + WAIT + ",{'block':'call','procedure':'p'}]}")),
                DiagnosticCode.BREAK_OUTSIDE_LOOP, "procedures[0].body[0]");
        assertTrue(d.message().contains("caller"), d.message());
        assertFalse(codes(onStart("{'block':'repeat','times':2,'body':[" + WAIT + ",{'block':'if','condition':true,'then':[{'block':'break'}]}]}"))
                .contains(DiagnosticCode.BREAK_OUTSIDE_LOOP));
    }

    @Test
    void loopGuardNeedsAWaitOrTaskStep() {
        Diagnostic d = expect(withVars(COUNT_VAR, "{'block':'repeat_until','condition':false,'body':[{'block':'change_var','name':'count','delta':1}]}"),
                DiagnosticCode.LOOP_WITHOUT_WAIT, "events[0].body[0]");
        assertTrue(d.message().startsWith("loop without a wait or task step"), d.message());
        expect(onStart("{'block':'forever','body':[{'block':'message','text':'spam'}]}"), DiagnosticCode.LOOP_WITHOUT_WAIT, "events[0].body[0]");
        expect(onStart("{'block':'repeat','times':5,'body':[]}"), DiagnosticCode.LOOP_WITHOUT_WAIT, "events[0].body[0]");
        expect(script("", "{'name':'p','params':[],'body':[{'block':'message','text':'x'}]}",
                startHandler("{'block':'repeat','times':5,'body':[{'block':'call','procedure':'p'}]}")), DiagnosticCode.LOOP_WITHOUT_WAIT,
                "events[0].body[0]");
        // inner loops are checked on their own: the outer loop waits, the inner one does not
        expect(withVars(COUNT_VAR, "{'block':'forever','body':[" + WAIT + ",{'block':'repeat','times':3,'body':[{'block':'change_var','name':'count','delta':1}]}]}"),
                DiagnosticCode.LOOP_WITHOUT_WAIT, "events[0].body[0].body[1]");
        // yielding blocks: wait, wait_until, any host action, also through procedures and branches
        for (String body : List.of(WAIT, "{'block':'wait_until','condition':true}", "{'block':'eat'}", "{'block':'send_chat','text':'x'}",
                "{'block':'if','condition':true,'then':[],'else':[" + WAIT + "]}", "{'block':'call','procedure':'w'}")) {
            String json = script("", "{'name':'w','params':[],'body':[{'block':'goto','target':{'type':'home'}}]}",
                    startHandler("{'block':'repeat','times':2,'body':[" + body + "]}", "{'block':'call','procedure':'w'}"));
            assertFalse(codes(json).contains(DiagnosticCode.LOOP_WITHOUT_WAIT), body);
        }
    }

    // ------------------------------------------------------------------------------------------ warnings

    @Test
    void warningsExplainSettingsAndRisks() {
        Diagnostic forever = expect(onStart("{'block':'forever','body':[" + WAIT + "]}"), DiagnosticCode.FOREVER_LOOP, "events[0].body[0]");
        assertTrue(forever.message().contains("safety stops"), forever.message());
        Diagnostic chat = expect(onStart("{'block':'send_chat','text':'/spawn'}"), DiagnosticCode.SENDS_CHAT, "events[0].body[0]");
        assertTrue(chat.message().contains("server runs it as a command"), chat.message());
        Diagnostic command = expect(onStart("{'block':'command','command':'#mine diamond_ore'}"), DiagnosticCode.RUNS_COMMAND, "events[0].body[0]");
        assertTrue(command.message().contains("'mine diamond_ore'") && command.message().contains("allowed commands"), command.message());
        expect(onStart("{'block':'repeat','times':50000,'body':[" + WAIT + "]}"), DiagnosticCode.LARGE_COUNT, "events[0].body[0].times");
        expect(onStart("{'block':'fill','material':'stone','x1':0,'y1':0,'z1':0,'x2':99,'y2':99,'z2':99}"), DiagnosticCode.LARGE_COUNT,
                "events[0].body[0]");
        assertFalse(codes(onStart("{'block':'craft','item':'x','count':64}")).contains(DiagnosticCode.LARGE_COUNT));
    }

    @Test
    void unreachableCodeIsReportedOncePerList() {
        List<Diagnostic> d = check(onStart("{'block':'stop'}", "{'block':'eat'}", "{'block':'eat'}"));
        assertEquals(1, d.stream().filter(x -> x.code() == DiagnosticCode.UNREACHABLE_CODE).count(), d::toString);
        expect(onStart("{'block':'stop'}", "{'block':'eat'}"), DiagnosticCode.UNREACHABLE_CODE, "events[0].body[1]");
        expect(onStart("{'block':'repeat','times':2,'body':[" + WAIT + ",{'block':'break'}," + WAIT + "]}"), DiagnosticCode.UNREACHABLE_CODE,
                "events[0].body[0].body[2]");
        expect(onStart("{'block':'forever','body':[" + WAIT + "]}", "{'block':'eat'}"), DiagnosticCode.UNREACHABLE_CODE, "events[0].body[1]");
        assertFalse(codes(onStart("{'block':'forever','body':[" + WAIT + ",{'block':'if','condition':true,'then':[{'block':'break'}]}]}",
                "{'block':'eat'}")).contains(DiagnosticCode.UNREACHABLE_CODE));
    }

    @Test
    void unusedDeclarationsAreWarnings() {
        expect(script("", "{'name':'helper','params':[],'body':[]}", startHandler()), DiagnosticCode.UNUSED_PROCEDURE, "procedures[0]");
        expect(withVars(COUNT_VAR, "{'block':'eat'}"), DiagnosticCode.UNUSED_VARIABLE, "variables[0]");
        assertTrue(ScriptLoader.load(withVars(COUNT_VAR, "{'block':'eat'}")).runnable());
    }
}
