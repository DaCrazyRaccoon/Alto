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
import baritone.alto.script.lang.Face;
import baritone.alto.script.lang.StationType;
import baritone.alto.script.lang.Value;
import baritone.alto.script.lang.VehicleKind;
import baritone.alto.script.runtime.ActionCall;
import baritone.alto.script.runtime.ActionRequest;
import baritone.alto.script.runtime.ActivationView;
import baritone.alto.script.runtime.GotoTarget;
import baritone.alto.script.runtime.QueryCall;
import baritone.alto.script.runtime.RunOptions;
import baritone.alto.script.runtime.RunStatus;
import baritone.alto.script.runtime.ScriptRun;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static baritone.alto.script.Scripts.onStart;
import static baritone.alto.script.Scripts.parse;
import static baritone.alto.script.Scripts.script;
import static baritone.alto.script.Scripts.startHandler;
import static baritone.alto.script.Scripts.withVars;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterpreterTest {

    private static final String WAIT1 = "{'block':'wait','seconds':1}";
    private static final String COUNT_VAR = "{'name':'count','initial':0}";
    private static final String INC = "{'block':'change_var','name':'count','delta':1}";

    private final MockHost host = new MockHost();
    private final RunOptions options = new RunOptions();

    private ScriptRun run(String json) {
        return new ScriptRun(parse(json), host, options);
    }

    private ScriptRun runToEnd(String json) {
        ScriptRun run = run(json);
        host.runToEnd(run, 100_000);
        return run;
    }

    private static double num(ScriptRun run, String variable) {
        return ((Value.Num) run.variables().get(variable)).value();
    }

    // ------------------------------------------------------------------------------------------ sequencing

    @Test
    void actionsRunInOrderAndTakeAtLeastOneTick() {
        host.give("oak_log", 4);
        ScriptRun run = run(onStart("{'block':'craft','item':'oak_planks','count':8}", "{'block':'message','text':'done'}"));
        assertEquals(RunStatus.RUNNING, run.status());
        host.tick(run);
        assertEquals(List.of("start craft"), host.log);
        assertEquals("craft", run.currentBlock());
        assertEquals("events[0].body[0]", run.currentPath());
        assertEquals("task craft", run.current().waitingFor());
        host.tick(run);
        assertEquals(List.of(), host.messages);
        host.tick(run);
        assertEquals(List.of("start craft", "done craft"), host.log);
        assertEquals(List.of("done"), host.messages);
        assertEquals(RunStatus.FINISHED, run.status());
        assertEquals("finished", run.endReason());
        assertEquals(8, host.count("oak_planks"));
        assertEquals(2, host.count("oak_log"));
        assertEquals(1, run.actionsStarted());
    }

    @Test
    void actionCallsCarryTypedNormalizedArguments() {
        host.give("oak_log", 1);
        runToEnd(onStart("{'block':'craft','item':'oak_planks','count':{'expr':'add','a':2,'b':2}}",
                "{'block':'use_item_on_block','item':'bone_meal','x':1,'y':64,'z':-2,'face':'up'}",
                "{'block':'set_filter','x':5,'y':6,'z':7,'item':'create:shaft'}",
                "{'block':'goto','target':{'type':'coords','x':1,'y':2,'z':3,'dimension':'the_nether'}}",
                "{'block':'deposit','except':['bread','bread','torch']}",
                "{'block':'place_station','kind':'blast_furnace'}",
                "{'block':'board_vehicle','kind':'train_seat'}",
                "{'block':'fill','material':'stone','x1':0,'y1':1,'z1':2,'x2':3,'y2':4,'z2':5}"));
        List<ActionRequest> requests = new ArrayList<>();
        for (ActionCall c : host.started) {
            requests.add(c.request());
        }
        assertEquals(List.of(
                new ActionRequest.Craft("minecraft:oak_planks", 4),
                new ActionRequest.UseItemOnBlock("minecraft:bone_meal", 1, 64, -2, Face.UP),
                new ActionRequest.SetFilter(5, 6, 7, null, "create:shaft"),
                new ActionRequest.Goto(new GotoTarget.Coords(1, 2, 3, "minecraft:the_nether")),
                new ActionRequest.Deposit(List.of("minecraft:bread", "minecraft:torch"), List.of()),
                new ActionRequest.PlaceStation(StationType.BLAST_FURNACE),
                new ActionRequest.BoardVehicle(VehicleKind.TRAIN_SEAT),
                new ActionRequest.Fill("minecraft:stone", 0, 1, 2, 3, 4, 5)), requests);
        assertEquals("events[0].body[1]", host.started.get(1).path());
        assertEquals(ActionType.USE_ITEM_ON_BLOCK, host.started.get(1).type());
    }

    @Test
    void runFinishesWhenOnStartEndsAndThereAreNoOtherHandlers() {
        ScriptRun run = runToEnd(onStart());
        assertEquals(RunStatus.FINISHED, run.status());
        assertEquals(1, run.ticks());
    }

    @Test
    void invalidScriptsCannotRun() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run(onStart("{'block':'break'}")));
        assertTrue(e.getMessage().contains("break"), e.getMessage());
        options.stepBudget = 0;
        assertThrows(IllegalArgumentException.class, () -> run(onStart()));
    }

    // ------------------------------------------------------------------------------------------ control blocks

    @Test
    void repeatRunsItsBodyTheGivenNumberOfTimes() {
        ScriptRun run = runToEnd(withVars(COUNT_VAR, "{'block':'repeat','times':3,'body':[" + INC + ",{'block':'eat'}]}",
                "{'block':'repeat','times':0,'body':[" + INC + ",{'block':'eat'}]}"));
        assertEquals(3, num(run, "count"));
        assertEquals(3, host.started.size());
        assertEquals(RunStatus.FINISHED, run.status());
    }

    @Test
    void repeatUntilChecksBeforeEveryPass() {
        host.give("oak_log", 64);
        ScriptRun run = runToEnd(onStart("{'block':'repeat_until','condition':{'expr':'gte','a':{'expr':'item_count','item':'oak_planks'},'b':64},"
                + "'body':[{'block':'craft','item':'oak_planks','count':16}]}"));
        assertEquals(64, host.count("oak_planks"));
        assertEquals(4, host.started.size());
        host.started.clear();
        runToEnd(onStart("{'block':'repeat_until','condition':true,'body':[{'block':'eat'}]}"));
        assertEquals(0, host.started.size());
    }

    @Test
    void foreverRunsUntilBreak() {
        ScriptRun run = runToEnd(withVars(COUNT_VAR, "{'block':'forever','body':[" + INC + ",{'block':'eat'},"
                + "{'block':'if','condition':{'expr':'gte','a':{'expr':'var','name':'count'},'b':5},'then':[{'block':'break'}]}]}",
                "{'block':'message','text':'after'}"));
        assertEquals(5, num(run, "count"));
        assertEquals(List.of("after"), host.messages);
        assertEquals(RunStatus.FINISHED, run.status());
    }

    @Test
    void breakLeavesOnlyTheInnermostLoop() {
        ScriptRun run = runToEnd(withVars(COUNT_VAR, "{'block':'repeat','times':3,'body':[{'block':'eat'},"
                + "{'block':'forever','body':[" + INC + ",{'block':'eat'},{'block':'break'}]}]}"));
        assertEquals(3, num(run, "count"));
    }

    @Test
    void ifElseChoosesABranch() {
        host.health = 5;
        runToEnd(onStart("{'block':'if','condition':{'expr':'lt','a':{'expr':'health'},'b':10},"
                        + "'then':[{'block':'message','text':'low'}],'else':[{'block':'message','text':'ok'}]}",
                "{'block':'if','condition':{'expr':'gt','a':{'expr':'health'},'b':10},'then':[{'block':'message','text':'high'}]}",
                "{'block':'message','text':'end'}"));
        assertEquals(List.of("low", "end"), host.messages);
    }

    @Test
    void waitUsesTheClockAndFreezesNothingElse() {
        ScriptRun run = run(onStart("{'block':'wait','seconds':1}", "{'block':'message','text':'later'}"));
        host.tick(run);
        assertTrue(run.current().waitingFor().startsWith("wait"), run.current().waitingFor());
        host.ticks(run, 18);
        assertEquals(List.of(), host.messages);
        host.ticks(run, 2);
        assertEquals(List.of("later"), host.messages);
        assertEquals(RunStatus.FINISHED, run.status());
    }

    @Test
    void waitZeroYieldsForOneTick() {
        ScriptRun run = run(onStart("{'block':'wait','seconds':0}", "{'block':'message','text':'next tick'}"));
        host.tick(run);
        assertEquals(List.of(), host.messages);
        host.tick(run);
        assertEquals(List.of("next tick"), host.messages);
    }

    @Test
    void waitUntilWaitsForTheWorld() {
        host.hunger = 5;
        ScriptRun run = run(onStart("{'block':'wait_until','condition':{'expr':'gte','a':{'expr':'hunger'},'b':18},'timeout_seconds':60}",
                "{'block':'message','text':'fed'}"));
        host.ticks(run, 10);
        assertEquals("condition (gives up in 59.55 s)", run.current().waitingFor());
        host.hunger = 20;
        host.tick(run);
        assertEquals(List.of("fed"), host.messages);
        // already true: no waiting at all
        host.messages.clear();
        ScriptRun quick = run(onStart("{'block':'wait_until','condition':true}", "{'block':'message','text':'now'}"));
        host.tick(quick);
        assertEquals(List.of("now"), host.messages);
    }

    @Test
    void waitUntilTimeoutFailsTheRun() {
        ScriptRun run = run(onStart("{'block':'eat'}", "{'block':'wait_until','condition':{'expr':'inventory_full'},'timeout_seconds':2}"));
        host.runToEnd(run, 1000);
        assertEquals(RunStatus.FAILED, run.status());
        assertEquals("events[0].body[1]", run.lastError().path());
        assertTrue(run.lastError().message().contains("timed out after 2 s"), run.lastError().message());
    }

    // ------------------------------------------------------------------------------------------ variables, values, procedures

    @Test
    void variablesHoldNumbersStringsAndBooleans() {
        ScriptRun run = runToEnd(withVars("{'name':'n','initial':1.5},{'name':'s','initial':'a'},{'name':'b','initial':false}",
                "{'block':'change_var','name':'n','delta':{'expr':'mul','a':2,'b':0.25}}",
                "{'block':'set_var','name':'s','value':{'expr':'join','parts':[{'expr':'var','name':'s'},'-',{'expr':'var','name':'n'},'-',true]}}",
                "{'block':'set_var','name':'b','value':{'expr':'not','a':{'expr':'var','name':'b'}}}",
                "{'block':'message','text':{'expr':'var','name':'s'}}",
                "{'block':'message','text':{'expr':'div','a':10,'b':4}}",
                "{'block':'message','text':{'expr':'mod','a':-1,'b':5}}",
                "{'block':'message','text':{'expr':'round','a':-2.5}}",
                "{'block':'message','text':{'expr':'div','a':1,'b':3}}"));
        assertEquals(Value.of(2.0), run.variables().get("n"));
        assertEquals(Value.of("a-2-true"), run.variables().get("s"));
        assertEquals(Value.of(true), run.variables().get("b"));
        assertEquals(List.of("a-2-true", "2.5", "4", "-2", "0.333333333333"), host.messages);
    }

    @Test
    void procedureParametersAreTypedAndIdsNormalized() {
        String procs = "{'name':'restock','params':[{'name':'item','type':'item'},{'name':'amount','type':'number'}],'body':["
                + "{'block':'if','condition':{'expr':'lt','a':{'expr':'item_count','item':{'expr':'param','name':'item'}},'b':{'expr':'param','name':'amount'}},"
                + "'then':[{'block':'fetch','item':{'expr':'param','name':'item'},"
                + "'count':{'expr':'sub','a':{'expr':'param','name':'amount'},'b':{'expr':'item_count','item':{'expr':'param','name':'item'}}}}]},"
                + "{'block':'message','text':{'expr':'param','name':'item'}}]}";
        host.give("glass", 10).stock("glass", 100).stock("oak_planks", 100);
        ScriptRun run = run(script("", procs, startHandler(
                "{'block':'call','procedure':'restock','args':{'item':'glass','amount':32}}",
                "{'block':'call','procedure':'restock','args':{'item':'minecraft:oak_planks','amount':16}}")));
        host.tick(run);
        ActivationView view = run.current();
        assertEquals(List.of("restock (called at events[0].body[0])"), view.callStack());
        assertEquals("procedures[0].body[0].then[0]", view.path());
        host.runToEnd(run, 1000);
        assertEquals(RunStatus.FINISHED, run.status());
        assertEquals(List.of(new ActionRequest.Fetch("minecraft:glass", 22), new ActionRequest.Fetch("minecraft:oak_planks", 16)),
                host.started.stream().map(ActionCall::request).toList());
        assertEquals(List.of("minecraft:glass", "minecraft:oak_planks"), host.messages);
        assertEquals(32, host.count("glass"));
    }

    @Test
    void callDepthIsLimited() {
        options.maxCallDepth = 3;
        StringBuilder procs = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            procs.append(i == 0 ? "" : ",").append("{'name':'p").append(i).append("','params':[],'body':[")
                    .append(i < 4 ? "{'block':'call','procedure':'p" + (i + 1) + "'}" : "{'block':'eat'}").append("]}");
        }
        ScriptRun run = runToEnd(script("", procs.toString(), startHandler("{'block':'call','procedure':'p0'}")));
        assertEquals(RunStatus.FAILED, run.status());
        assertEquals("procedures[2].body[0]", run.lastError().path());
        assertTrue(run.lastError().message().contains("nested more than 3"), run.lastError().message());
    }

    @Test
    void randomIsDeterministicForASeed() {
        String json = withVars("{'name':'r','initial':''}", "{'block':'repeat','times':20,'body':[{'block':'set_var','name':'r',"
                + "'value':{'expr':'join','parts':[{'expr':'var','name':'r'},{'expr':'random','min':6,'max':1},',']}},{'block':'wait','seconds':0}]}");
        options.randomSeed = 42;
        String first = run(json) instanceof ScriptRun r ? result(r) : null;
        String second = result(run(json));
        assertEquals(first, second);
        for (String part : first.split(",")) {
            int v = Integer.parseInt(part);
            assertTrue(v >= 1 && v <= 6, first);
        }
        options.randomSeed = 7;
        assertFalse(first.equals(result(run(json))));
    }

    private String result(ScriptRun run) {
        host.runToEnd(run, 1000);
        return ((Value.Str) run.variables().get("r")).value();
    }

    // ------------------------------------------------------------------------------------------ failures

    @Test
    void aFailedActionFailsTheRunWithPathAndReason() {
        host.give("oak_log", 1);
        ScriptRun run = runToEnd(onStart("{'block':'eat'}", "{'block':'craft','item':'oak_planks','count':64}", "{'block':'message','text':'never'}"));
        assertEquals(RunStatus.FAILED, run.status());
        assertEquals("events[0].body[1]", run.lastError().path());
        assertEquals("craft failed: not enough minecraft:oak_log (have 1, need 16)", run.lastError().message());
        assertEquals("events[0].body[1]: craft failed: not enough minecraft:oak_log (have 1, need 16)", run.endReason());
        assertEquals(List.of(), host.messages);
        assertFalse(host.log.contains("cancel craft"), "a failed action is not cancelled again");
    }

    @Test
    void runtimeErrorsHaveExpressionPaths() {
        ScriptRun div = runToEnd(withVars(COUNT_VAR, "{'block':'set_var','name':'count','value':{'expr':'div','a':1,'b':{'expr':'var','name':'count'}}}"));
        assertEquals(RunStatus.FAILED, div.status());
        assertEquals("events[0].body[0].value", div.lastError().path());
        assertEquals("div by zero", div.lastError().message());

        ScriptRun range = runToEnd(withVars(COUNT_VAR, "{'block':'craft','item':'oak_planks','count':{'expr':'var','name':'count'}}"));
        assertEquals("events[0].body[0].count", range.lastError().path());
        assertTrue(range.lastError().message().contains("between 1 and 100000, got 0"), range.lastError().message());

        ScriptRun id = runToEnd(withVars("{'name':'item','initial':'Not An Id'}", "{'block':'craft','item':{'expr':'var','name':'item'},'count':1}"));
        assertEquals("events[0].body[0].item", id.lastError().path());

        ScriptRun whole = runToEnd(onStart("{'block':'repeat','times':{'expr':'div','a':5,'b':2},'body':[{'block':'eat'}]}"));
        assertTrue(whole.lastError().message().contains("whole number"), whole.lastError().message());
    }

    @Test
    void wrongAnswersFromTheHostFailTheRun() {
        host.queryOverride = q -> q instanceof QueryCall.Health ? Value.of("full") : null;
        ScriptRun run = runToEnd(onStart("{'block':'message','text':{'expr':'health'}}"));
        assertEquals(RunStatus.FAILED, run.status());
        assertEquals("events[0].body[0].text", run.lastError().path());
        assertTrue(run.lastError().message().contains("a string for health instead of a number"), run.lastError().message());

        host.queryOverride = q -> {
            throw new IllegalStateException("world not loaded");
        };
        ScriptRun crash = runToEnd(onStart("{'block':'message','text':{'expr':'hunger'}}"));
        assertEquals(RunStatus.FAILED, crash.status());
        assertTrue(crash.lastError().message().contains("world not loaded"), crash.lastError().message());
    }

    // ------------------------------------------------------------------------------------------ budget and loop guard

    @Test
    void stepBudgetSpreadsLongStraightCodeOverTicks() {
        String[] incs = new String[250];
        java.util.Arrays.fill(incs, INC);
        ScriptRun run = run(withVars(COUNT_VAR, incs));
        host.tick(run);
        // change_var costs 2 steps (the block and its delta expression)
        assertEquals(200, run.stepsLastTick());
        assertEquals(100, num(run, "count"));
        host.tick(run);
        assertEquals(200, num(run, "count"));
        assertEquals(RunStatus.RUNNING, run.status(), "no loop, so the loop guard does not apply");
        host.tick(run);
        assertEquals(250, num(run, "count"));
        assertEquals(RunStatus.FINISHED, run.status());
    }

    @Test
    void expressionsCountAsSteps() {
        options.stepBudget = 10;
        ScriptRun run = run(withVars(COUNT_VAR, "{'block':'set_var','name':'count','value':{'expr':'add','a':1,'b':{'expr':'add','a':2,'b':3}}}", INC));
        host.tick(run);
        // set_var (1) + add (1) + 1 (1) + add (1) + 2 (1) + 3 (1) = 6, then change_var (1) + delta (1) = 8
        assertEquals(8, run.stepsLastTick());
        assertEquals(RunStatus.FINISHED, run.status());
    }

    @Test
    void runtimeLoopGuardStopsBusyLoopsTheValidatorCannotSee() {
        ScriptRun run = run(withVars(COUNT_VAR, "{'block':'forever','body':[" + INC + ",{'block':'if','condition':false,'then':[" + WAIT1 + "]}]}"));
        host.tick(run);
        assertEquals(RunStatus.RUNNING, run.status());
        host.tick(run);
        assertEquals(RunStatus.FAILED, run.status());
        assertEquals("events[0].body[0]", run.lastError().path());
        assertTrue(run.lastError().message().startsWith("runtime loop guard"), run.lastError().message());

        ScriptRun spin = runToEnd(onStart("{'block':'repeat_until','condition':false,'body':[{'block':'wait_until','condition':true}]}"));
        assertEquals(RunStatus.FAILED, spin.status());
        assertEquals(2, spin.ticks());
    }

    @Test
    void loopsThatWaitNeverTripTheGuard() {
        options.stepBudget = 5;
        ScriptRun run = run(withVars(COUNT_VAR, "{'block':'repeat','times':200,'body':[" + INC + "," + INC + "," + INC + ",{'block':'wait','seconds':0}]}"));
        host.runToEnd(run, 10_000);
        assertEquals(RunStatus.FINISHED, run.status());
        assertEquals(600, num(run, "count"));
    }

    // ------------------------------------------------------------------------------------------ safety settings

    @Test
    void foreverNeedsSafetyStops() {
        host.safetyStops = false;
        ScriptRun run = runToEnd(onStart("{'block':'message','text':'before'}", "{'block':'forever','body':[" + WAIT1 + "]}"));
        assertEquals(RunStatus.FAILED, run.status());
        assertEquals("events[0].body[1]", run.lastError().path());
        assertTrue(run.lastError().message().startsWith("forever refused"), run.lastError().message());
        assertEquals(List.of("before"), host.messages);
    }

    @Test
    void sendChatIsRefusedUnlessAllowed() {
        ScriptRun refused = runToEnd(onStart("{'block':'send_chat','text':'hello'}"));
        assertEquals(RunStatus.FAILED, refused.status());
        assertTrue(refused.lastError().message().startsWith("send_chat refused"), refused.lastError().message());
        assertEquals(List.of(), host.started);

        host.chatAllowed = true;
        host.ticksFor.put(ActionType.SEND_CHAT, 1);
        ScriptRun allowed = run(onStart("{'block':'send_chat','text':'one'}", "{'block':'send_chat','text':'two'}"));
        host.tick(allowed);
        long firstAt = host.now;
        while (host.started.size() < 2) {
            host.tick(allowed);
        }
        assertTrue(host.now - firstAt >= 1000, "second chat waited for the cooldown");
        host.runToEnd(allowed, 100);
        assertEquals(List.of("one", "two"), host.chatSent);
    }

    @Test
    void commandsMustBeOnTheAllowList() {
        ScriptRun refused = runToEnd(onStart("{'block':'command','command':'mine diamond_ore'}"));
        assertEquals(RunStatus.FAILED, refused.status());
        assertTrue(refused.lastError().message().contains("'mine diamond_ore' is not on the allowed commands list"),
                refused.lastError().message());
        assertEquals(List.of(), host.started);

        host.allowedCommands.add("mine diamond_ore");
        ScriptRun allowed = runToEnd(onStart("{'block':'command','command':'  #mine diamond_ore '}"));
        assertEquals(RunStatus.FINISHED, allowed.status());
        assertEquals(List.of("mine diamond_ore"), host.commandsRun);
    }

    // ------------------------------------------------------------------------------------------ pause, resume, stop, limits

    @Test
    void pauseCancelsTheActionAndResumeRestartsIt() {
        host.ticksFor.put(ActionType.MINE, 5);
        ScriptRun run = run(onStart("{'block':'mine','block_or_item':'cobblestone','count':10}", "{'block':'message','text':'done'}"));
        host.ticks(run, 2);
        run.pause();
        assertEquals(RunStatus.PAUSED, run.status());
        assertEquals(List.of("start mine", "cancel mine"), host.log);
        long ticksBefore = run.ticks();
        host.ticks(run, 50);
        assertEquals(ticksBefore, run.ticks(), "nothing advances while paused");
        assertFalse(run.fire(EventType.ON_CHAT, "!go"));
        run.resume();
        host.runToEnd(run, 100);
        assertEquals(List.of("start mine", "cancel mine", "start mine", "done mine"), host.log);
        assertEquals(List.of("done"), host.messages);
        assertEquals(10, host.count("cobblestone"));
    }

    @Test
    void pauseFreezesWaitTimers() {
        ScriptRun run = run(onStart("{'block':'wait','seconds':2}", "{'block':'message','text':'done'}"));
        host.ticks(run, 20);
        run.pause();
        host.now += 600_000;
        run.resume();
        host.ticks(run, 15);
        assertEquals(List.of(), host.messages);
        host.ticks(run, 10);
        assertEquals(List.of("done"), host.messages);
    }

    @Test
    void stopCancelsAndEnds() {
        ScriptRun run = run(onStart("{'block':'goto','target':{'type':'home','name':'base'}}", "{'block':'message','text':'never'}"));
        host.tick(run);
        run.stop();
        assertEquals(RunStatus.STOPPED, run.status());
        assertEquals("stopped", run.endReason());
        assertEquals(List.of("start goto", "cancel goto"), host.log);
        host.ticks(run, 10);
        assertEquals(List.of(), host.messages);
        assertNull(run.lastError());
        assertEquals(List.of(), run.activations());
    }

    @Test
    void stopBlockFinishesTheScriptAndCancelsOtherHandlers() {
        host.ticksFor.put(ActionType.FARM, 100);
        ScriptRun run = run(script("", "", startHandler("{'block':'farm'}"),
                "{'event':'on_chat','trigger':'!stop','body':[{'block':'message','text':'bye'},{'block':'stop'},{'block':'eat'}]}"));
        host.ticks(run, 3);
        assertTrue(run.fire(EventType.ON_CHAT, "!stop"));
        host.tick(run);
        assertEquals(RunStatus.FINISHED, run.status());
        assertTrue(run.endReason().contains("events[1].body[1]"), run.endReason());
        assertEquals(List.of("start farm", "cancel farm"), host.log);
        assertEquals(List.of("bye"), host.messages);
    }

    @Test
    void limitsStopTheRun() {
        options.maxTotalActions = 3;
        ScriptRun actions = runToEnd(onStart("{'block':'repeat','times':10,'body':[{'block':'eat'}]}"));
        assertEquals(RunStatus.STOPPED, actions.status());
        assertEquals(3, host.started.size());
        assertTrue(actions.endReason().contains("maximum of 3 actions"), actions.endReason());

        options.maxTotalActions = 0;
        options.maxRuntimeMillis = 5000;
        ScriptRun time = runToEnd(onStart("{'block':'wait','seconds':60}"));
        assertEquals(RunStatus.STOPPED, time.status());
        assertTrue(time.endReason().contains("maximum run time of 5 s"), time.endReason());
        assertTrue(time.runMillis() >= 5000 && time.runMillis() < 5200, "stopped at " + time.runMillis());
    }

    @Test
    void hostExceptionsFailTheRunInsteadOfCrashing() {
        host.startFailure = new IllegalStateException("no player");
        ScriptRun run = runToEnd(onStart("{'block':'eat'}"));
        assertEquals(RunStatus.FAILED, run.status());
        assertEquals("Alto could not start eat: no player", run.lastError().message());
    }

    @Test
    void statusCountersAndViewsAreExposed() {
        ScriptRun run = run(onStart("{'block':'eat'}"));
        host.tick(run);
        ActivationView view = run.activations().get(0);
        assertEquals("on_start", view.handler());
        assertEquals("events[0]", view.handlerPath());
        assertEquals("eat", view.block());
        assertInstanceOf(Long.class, run.totalSteps());
        assertTrue(run.totalSteps() >= 1);
        assertEquals(64, run.scriptHash().length());
        assertThrows(IllegalArgumentException.class, () -> run.fire(EventType.ON_START, null));
        assertThrows(IllegalArgumentException.class, () -> run.fire(EventType.EVERY, null));
    }
}
