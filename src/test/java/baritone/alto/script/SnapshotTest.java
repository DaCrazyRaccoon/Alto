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

import baritone.alto.script.lang.EventType;
import baritone.alto.script.lang.Script;
import baritone.alto.script.lang.Value;
import baritone.alto.script.runtime.RunOptions;
import baritone.alto.script.runtime.RunSnapshot;
import baritone.alto.script.runtime.RunStatus;
import baritone.alto.script.runtime.ScriptRun;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static baritone.alto.script.Scripts.handler;
import static baritone.alto.script.Scripts.parse;
import static baritone.alto.script.Scripts.script;
import static baritone.alto.script.Scripts.startHandler;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotTest {

    private static final String FARM = script("{'name':'rounds','initial':0},{'name':'label','initial':'farm'}", "",
            startHandler("{'block':'message','text':{'expr':'join','parts':['start ',{'expr':'var','name':'label'},' ',{'expr':'var','name':'rounds'}]}}"),
            handler("'event':'on_chat','trigger':'!farm'", "{'block':'repeat','times':100,'body':[{'block':'change_var','name':'rounds','delta':1},"
                    + "{'block':'wait','seconds':1}]}"),
            handler("'event':'every','seconds':600", "{'block':'message','text':'tick'}"));

    private final MockHost host = new MockHost();

    @Test
    void snapshotRecordsVariablesAndActiveHandlers() {
        ScriptRun run = new ScriptRun(parse(FARM), host, new RunOptions());
        host.tick(run);
        run.fire(EventType.ON_CHAT, "!farm");
        host.ticks(run, 50);
        RunSnapshot snapshot = run.snapshot();
        assertEquals(run.scriptHash(), snapshot.scriptHash());
        assertEquals(List.of("events[1]"), snapshot.activeHandlers());
        assertEquals(Value.of(3), snapshot.variables().get("rounds"));
        assertEquals(Value.of("farm"), snapshot.variables().get("label"));
        assertEquals(host.now, snapshot.savedAtMillis());
        assertEquals(snapshot, RunSnapshot.fromJson(snapshot.toJson()));
    }

    @Test
    void restoreRestartsOnStartAndActiveChatHandlersWithSavedVariables() {
        Script script = parse(FARM);
        ScriptRun first = new ScriptRun(script, host, new RunOptions());
        host.tick(first);
        first.fire(EventType.ON_CHAT, "!farm");
        host.ticks(first, 50);
        String saved = first.snapshot().toJson();
        first.stop();

        host.messages.clear();
        ScriptRun restored = ScriptRun.restore(parse(FARM), host, new RunOptions(), RunSnapshot.fromJson(saved));
        assertEquals(List.of("on_start", "on_chat \"!farm\""),
                restored.activations().stream().map(a -> a.handler()).toList());
        host.tick(restored);
        assertEquals(List.of("start farm 3"), host.messages, "on_start ran again with the saved variables");
        host.ticks(restored, 40);
        assertEquals(Value.of(6), restored.variables().get("rounds"), "the chat handler restarted from its beginning");
        assertEquals(RunStatus.RUNNING, restored.status());
    }

    @Test
    void restoreRefusesAChangedScript() {
        ScriptRun run = new ScriptRun(parse(FARM), host, new RunOptions());
        RunSnapshot snapshot = run.snapshot();
        Script changed = parse(FARM.replace("\"seconds\":600", "\"seconds\":300"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ScriptRun.restore(changed, host, new RunOptions(), snapshot));
        assertEquals("the saved run belongs to a different version of this script", e.getMessage());
    }

    @Test
    void restoreOnlyTakesVariablesThatStillFit() {
        Script script = parse(FARM);
        Map<String, Value> vars = new LinkedHashMap<>();
        vars.put("rounds", Value.of("seven"));
        vars.put("label", Value.of("restored"));
        vars.put("gone", Value.of(1));
        RunSnapshot snapshot = new RunSnapshot(new ScriptRun(script, host, new RunOptions()).scriptHash(), vars, List.of(), 0);
        ScriptRun run = ScriptRun.restore(script, host, new RunOptions(), snapshot);
        assertEquals(Value.of(0), run.variables().get("rounds"));
        assertEquals(Value.of("restored"), run.variables().get("label"));
        assertEquals(List.of("rounds", "label"), List.copyOf(run.variables().keySet()));
    }

    @Test
    void invalidSnapshotsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RunSnapshot.fromJson("{"));
        assertThrows(IllegalArgumentException.class, () -> RunSnapshot.fromJson("{\"format\":\"other\",\"version\":1}"));
        assertThrows(IllegalArgumentException.class, () -> RunSnapshot.fromJson(
                "{\"format\":\"alto-script-snapshot\",\"version\":1,\"scriptHash\":\"x\",\"savedAtMillis\":0,"
                        + "\"variables\":{\"a\":[1]},\"activeHandlers\":[]}"));
    }
}
