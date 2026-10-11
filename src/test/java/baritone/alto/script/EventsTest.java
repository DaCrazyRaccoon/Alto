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

import baritone.alto.script.lang.ActionType;
import baritone.alto.script.lang.EventType;
import baritone.alto.script.lang.Value;
import baritone.alto.script.runtime.ActivationView;
import baritone.alto.script.runtime.RunOptions;
import baritone.alto.script.runtime.RunStatus;
import baritone.alto.script.runtime.ScriptRun;
import org.junit.jupiter.api.Test;

import java.util.List;

import static baritone.alto.script.Scripts.handler;
import static baritone.alto.script.Scripts.parse;
import static baritone.alto.script.Scripts.script;
import static baritone.alto.script.Scripts.startHandler;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventsTest {

    private static final String COUNT_VAR = "{'name':'count','initial':0}";
    private static final String INC = "{'block':'change_var','name':'count','delta':1}";

    private final MockHost host = new MockHost();
    private final RunOptions options = new RunOptions();

    private ScriptRun run(String json) {
        return new ScriptRun(parse(json), host, options);
    }

    private static double count(ScriptRun run) {
        return ((Value.Num) run.variables().get("count")).value();
    }

    @Test
    void chatTriggersStartTheirHandlerOnlyWhenIdle() {
        ScriptRun run = run(script("", "", handler("'event':'on_chat','trigger':'!go'", "{'block':'message','text':'going'}", "{'block':'eat'}")));
        host.ticks(run, 3);
        assertTrue(run.isIdle());
        assertEquals(RunStatus.RUNNING, run.status(), "a script with listeners waits for events");
        assertFalse(run.fire(EventType.ON_CHAT, "go"));
        assertFalse(run.fire(EventType.ON_CHAT, null));
        assertTrue(run.fire(EventType.ON_CHAT, "  !GO "));
        assertFalse(run.fire(EventType.ON_CHAT, "!go"), "already running: ignored, not queued");
        assertEquals("on_chat \"!go\"", run.activations().get(0).handler());
        host.runUntilIdle(run, 100);
        assertTrue(run.fire(EventType.ON_CHAT, "!go"));
        host.runUntilIdle(run, 100);
        assertEquals(List.of("going", "going"), host.messages);
        assertEquals(2, host.started.size());
        assertEquals(RunStatus.RUNNING, run.status());
        run.stop();
        assertEquals(RunStatus.STOPPED, run.status());
        assertFalse(run.fire(EventType.ON_CHAT, "!go"));
    }

    @Test
    void everyFiresOnTheFakeClock() {
        ScriptRun run = run(script(COUNT_VAR, "", handler("'event':'every','seconds':10", INC)));
        host.ticks(run, 199);
        assertEquals(0, count(run));
        host.tick(run);
        assertEquals(1, count(run));
        host.ticks(run, 200);
        assertEquals(2, count(run));
        assertEquals(RunStatus.RUNNING, run.status());
    }

    @Test
    void everySkipsTicksWhileItsHandlerStillRuns() {
        ScriptRun run = run(script(COUNT_VAR, "", handler("'event':'every','seconds':1", INC, "{'block':'wait','seconds':2.5}")));
        host.ticks(run, 20);
        assertEquals(1, count(run));
        host.ticks(run, 59);
        assertEquals(1, count(run), "the 2 s and 3 s timer ticks were skipped");
        host.tick(run);
        assertEquals(2, count(run));
    }

    @Test
    void inventoryFullIsEdgeTriggered() {
        ScriptRun run = run(script(COUNT_VAR, "", handler("'event':'on_inventory_full'", INC, "{'block':'message','text':'full'}")));
        host.ticks(run, 30);
        assertEquals(0, count(run));
        host.inventoryFull = true;
        host.ticks(run, 20);
        assertEquals(1, count(run));
        host.ticks(run, 100);
        assertEquals(1, count(run), "still full: not again");
        host.inventoryFull = false;
        host.ticks(run, 20);
        host.inventoryFull = true;
        host.ticks(run, 20);
        assertEquals(2, count(run));
    }

    @Test
    void conditionChecksArePeriodicAndCanBeForced() {
        ScriptRun run = run(script("", "", handler("'event':'on_health_below','health':10", "{'block':'message','text':'ouch'}")));
        host.tick(run);
        int queriesAfterFirstCheck = host.queries;
        host.ticks(run, 10);
        assertEquals(queriesAfterFirstCheck, host.queries, "checked once per second, not every tick");
        host.health = 4;
        assertTrue(run.fire(EventType.ON_HEALTH_BELOW, null));
        assertEquals(1, run.activations().size());
        host.tick(run);
        assertEquals(List.of("ouch"), host.messages);
    }

    @Test
    void healthAndItemEventsRunTheirHandlers() {
        host.give("bread", 10).stock("bread", 64);
        ScriptRun run = run(script("", "", handler("'event':'on_health_below','health':10", "{'block':'eat'}"),
                handler("'event':'on_item_below','item':'bread','count':4", "{'block':'fetch','item':'bread','count':16}")));
        host.ticks(run, 40);
        assertEquals(List.of(), host.started);
        host.health = 6;
        host.ticks(run, 25);
        assertEquals(List.of(ActionType.EAT), host.startedTypes());
        assertEquals(20, host.health);
        host.inventory.put("minecraft:bread", 2);
        host.ticks(run, 25);
        assertEquals(List.of(ActionType.EAT, ActionType.FETCH), host.startedTypes());
        assertEquals(18, host.count("bread"));
    }

    @Test
    void conditionThatHoldsAgainWhileItsHandlerRunsStartsAfterIt() {
        ScriptRun run = run(script(COUNT_VAR, "", handler("'event':'on_inventory_full'", INC, "{'block':'wait','seconds':3}")));
        host.inventoryFull = true;
        host.tick(run);
        assertEquals(1, count(run));
        host.ticks(run, 20);
        host.inventoryFull = false;
        host.ticks(run, 20);
        host.inventoryFull = true;
        host.ticks(run, 21);
        assertEquals(1, count(run), "handler still running");
        host.ticks(run, 20);
        assertEquals(2, count(run), "started again once the first run ended, because it is full again");
    }

    @Test
    void handlersTakeTurnsForTheOneBody() {
        host.ticksFor.put(ActionType.MINE, 6);
        ScriptRun run = run(script("", "", startHandler("{'block':'mine','block_or_item':'stone','count':8}",
                        "{'block':'craft','item':'stick','count':4}"),
                handler("'event':'on_chat','trigger':'!eat'", "{'block':'eat'}", "{'block':'message','text':'ate'}")));
        host.give("oak_planks", 2);
        host.ticks(run, 2);
        assertTrue(run.fire(EventType.ON_CHAT, "!eat"));
        host.tick(run);
        ActivationView chat = run.activations().get(1);
        assertEquals("its turn (another handler's action is running)", chat.waitingFor());
        assertEquals("events[0].body[0]", run.currentPath(), "the runner shows the handler whose action runs");
        host.runUntilIdle(run, 200);
        assertEquals(List.of("start mine", "done mine", "start eat", "done eat", "start craft", "done craft"), host.log);
        assertEquals(List.of("ate"), host.messages);
        assertEquals(RunStatus.RUNNING, run.status());
    }

    @Test
    void eventsAreIgnoredWhilePaused() {
        ScriptRun run = run(script(COUNT_VAR, "", handler("'event':'every','seconds':1", INC)));
        host.ticks(run, 10);
        run.pause();
        host.ticks(run, 100);
        assertFalse(run.fire(EventType.ON_INVENTORY_FULL, null));
        run.resume();
        host.ticks(run, 9);
        assertEquals(0, count(run), "the timer did not run while paused");
        host.tick(run);
        assertEquals(1, count(run));
    }
}
