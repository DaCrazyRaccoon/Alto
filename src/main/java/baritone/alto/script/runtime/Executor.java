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

import baritone.alto.script.lang.ActionType;
import baritone.alto.script.lang.Expr;
import baritone.alto.script.lang.Fields;
import baritone.alto.script.lang.Ids;
import baritone.alto.script.lang.Limits;
import baritone.alto.script.lang.Script;
import baritone.alto.script.lang.Stmt;
import baritone.alto.script.lang.Value;
import baritone.alto.script.runtime.Activation.Blocker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal: the meaning of every block. {@link #step} executes one statement of an activation (or ends a frame);
 * {@link #tryUnblock} decides whether a waiting activation may continue. Errors throw {@link ScriptRuntimeException}.
 */
final class Executor {

    private final ScriptRun run;
    private final ScriptHost host;
    private final RunOptions options;
    private final Map<String, Script.Procedure> procedures;
    private final Map<String, Value> variables;
    private final Evaluator evaluator;
    private final ActionResolver resolver;
    private final ActionSlot slot;
    private long lastChatAt = Long.MIN_VALUE;
    private int actionsStarted;

    Executor(ScriptRun run, ScriptHost host, RunOptions options, Map<String, Script.Procedure> procedures,
             Map<String, Value> variables, Evaluator evaluator, ActionSlot slot) {
        this.run = run;
        this.host = host;
        this.options = options;
        this.procedures = procedures;
        this.variables = variables;
        this.evaluator = evaluator;
        this.resolver = new ActionResolver(evaluator);
        this.slot = slot;
    }

    int actionsStarted() {
        return actionsStarted;
    }

    /** Whether a blocked activation may continue now; clears its blocker when it may. */
    boolean tryUnblock(Activation a, long now) {
        switch (a.blocker) {
            case Blocker.Task ignored -> {
                return false; // the run polls the action
            }
            case Blocker.AwaitTurn ignored -> {
                if (!slot.isNext(a)) {
                    return false;
                }
                a.blocker = null; // the action block runs again and takes the slot
                return true;
            }
            case Blocker.Sleep s -> {
                if (now < s.untilMillis()) {
                    return false;
                }
                a.blocker = null;
                a.advance();
                return true;
            }
            case Blocker.Retry r -> {
                if (now < r.atMillis()) {
                    return false;
                }
                a.blocker = null;
                return true;
            }
            case Blocker.Condition c -> {
                run.countStep();
                if (evaluator.bool(c.stmt().condition(), a.params(), "condition")) {
                    a.blocker = null;
                    a.advance();
                    return true;
                }
                if (c.deadlineMillis() >= 0 && now >= c.deadlineMillis()) {
                    throw new ScriptRuntimeException(c.stmt().path(), "wait_until timed out after "
                            + Value.formatNumber(c.timeoutSeconds()) + " s (the condition never became true)");
                }
                return false;
            }
        }
    }

    /** Executes the current statement of {@code a}, or ends its top frame when it has no statement left. */
    void step(Activation a, long now) {
        Frame f = a.frames.peek();
        if (f.index >= f.body.size()) {
            endOfFrame(a, f);
            return;
        }
        Stmt s = f.body.get(f.index);
        run.countStep();
        Map<String, Value> params = a.params();
        switch (s) {
            case Stmt.Action act -> action(a, act, params, now);
            case Stmt.Repeat r -> {
                int times = evaluator.whole(r.times(), Fields.REPEAT_TIMES, params);
                f.index++;
                if (times > 0) {
                    a.frames.push(Frame.loop(Frame.Kind.REPEAT, r, r.body(), times));
                }
            }
            case Stmt.RepeatUntil r -> {
                boolean done = evaluator.bool(r.condition(), params, "condition");
                f.index++;
                if (!done) {
                    a.frames.push(Frame.loop(Frame.Kind.REPEAT_UNTIL, r, r.body(), 0));
                }
            }
            case Stmt.Forever fe -> {
                if (!ScriptRun.hostCall(fe.path(), "read the safety stops setting", host::safetyStopsEnabled)) {
                    throw new ScriptRuntimeException(fe.path(), "forever refused: safety stops are off; turn on"
                            + " safety stops in Alto's settings to run scripts with forever loops");
                }
                f.index++;
                a.frames.push(Frame.loop(Frame.Kind.FOREVER, fe, fe.body(), 0));
            }
            case Stmt.If i -> {
                boolean condition = evaluator.bool(i.condition(), params, "condition");
                f.index++;
                List<Stmt> branch = condition ? i.then() : i.otherwise();
                if (!branch.isEmpty()) {
                    a.frames.push(Frame.block(i, branch));
                }
            }
            case Stmt.WaitUntil w -> waitUntil(a, w, params, now);
            case Stmt.Break b -> breakLoop(a, b);
            case Stmt.Call c -> call(a, f, c, params);
            case Stmt.SetVar sv -> {
                setVar(sv, evaluator.eval(sv.value(), params));
                f.index++;
            }
            case Stmt.ChangeVar cv -> {
                double delta = evaluator.number(cv.delta(), params, "delta");
                if (!(variables.get(cv.name()) instanceof Value.Num old)) {
                    throw new ScriptRuntimeException(cv.path(), "change_var needs a number variable called '" + cv.name() + "'");
                }
                double next = old.value() + delta;
                if (!Double.isFinite(next)) {
                    throw new ScriptRuntimeException(cv.path(), "variable '" + cv.name() + "' became too large");
                }
                variables.put(cv.name(), Value.of(next));
                f.index++;
            }
        }
    }

    // ------------------------------------------------------------------------------------------ control

    private void endOfFrame(Activation a, Frame f) {
        switch (f.kind) {
            case HANDLER -> a.done = true;
            case BLOCK, CALL -> a.frames.pop();
            case REPEAT -> {
                if (--f.remaining > 0) {
                    nextPass(a, f);
                } else {
                    a.frames.pop();
                }
            }
            case REPEAT_UNTIL -> {
                if (evaluator.bool(((Stmt.RepeatUntil) f.owner).condition(), a.params(), "condition")) {
                    a.frames.pop();
                } else {
                    nextPass(a, f);
                }
            }
            case FOREVER -> nextPass(a, f);
        }
    }

    private void nextPass(Activation a, Frame f) {
        run.countStep();
        f.index = 0;
        a.loopPassesSinceYield++;
        a.lastLoopPath = f.owner.path();
    }

    private void waitUntil(Activation a, Stmt.WaitUntil w, Map<String, Value> params, long now) {
        if (evaluator.bool(w.condition(), params, "condition")) {
            a.advance();
            return;
        }
        long deadline = -1;
        double seconds = 0;
        if (w.timeoutSeconds() != null) {
            seconds = evaluator.number(w.timeoutSeconds(), Fields.TIMEOUT_SECONDS, params);
            deadline = now + Math.round(seconds * 1000);
        }
        a.block(new Blocker.Condition(w, deadline, seconds));
    }

    private static void breakLoop(Activation a, Stmt.Break b) {
        while (true) {
            Frame top = a.frames.peek();
            if (top == null || top.kind == Frame.Kind.HANDLER || top.kind == Frame.Kind.CALL) {
                throw new ScriptRuntimeException(b.path(), "break is not inside a loop");
            }
            a.frames.pop();
            if (top.isLoop()) {
                return;
            }
        }
    }

    private void call(Activation a, Frame f, Stmt.Call c, Map<String, Value> params) {
        Script.Procedure p = procedures.get(c.procedure());
        if (p == null) {
            throw new ScriptRuntimeException(c.path(), "there is no procedure called '" + c.procedure() + "'");
        }
        if (a.callDepth() >= options.maxCallDepth) {
            throw new ScriptRuntimeException(c.path(), "procedure calls are nested more than "
                    + options.maxCallDepth + " deep");
        }
        Map<String, Value> args = new LinkedHashMap<>();
        for (Script.ParamDecl param : p.params()) {
            Expr e = c.args().get(param.name());
            if (e == null) {
                throw new ScriptRuntimeException(c.path(), "missing value for parameter '" + param.name() + "'");
            }
            Value v = evaluator.eval(e, params);
            if (v.type() != param.type().valueType()) {
                throw new ScriptRuntimeException(e.path(), "parameter '" + param.name() + "' needs "
                        + param.type().valueType().withArticle() + ", got " + v.type().withArticle());
            }
            if (param.type().isId()) {
                String id = ((Value.Str) v).value();
                if (!Ids.isValidId(id)) {
                    throw new ScriptRuntimeException(e.path(), "parameter '" + param.name()
                            + "' needs an item or block id, got '" + id + "'");
                }
                v = Value.of(Ids.normalize(id));
            }
            args.put(param.name(), v);
        }
        f.index++;
        a.frames.push(Frame.call(c, p, args));
    }

    private void setVar(Stmt.SetVar sv, Value value) {
        Value old = variables.get(sv.name());
        if (old == null || old.type() != value.type()) {
            throw new ScriptRuntimeException(sv.path(), "cannot store " + value.type().withArticle()
                    + " in variable '" + sv.name() + "'");
        }
        if (value instanceof Value.Str str && str.value().length() > Limits.MAX_STRING_VALUE) {
            throw new ScriptRuntimeException(sv.path(), "text is longer than " + Limits.MAX_STRING_VALUE + " characters");
        }
        variables.put(sv.name(), value);
    }

    // ------------------------------------------------------------------------------------------ actions

    private void action(Activation a, Stmt.Action act, Map<String, Value> params, long now) {
        switch (act.type()) {
            case WAIT -> {
                double seconds = evaluator.number(act.expr("seconds"), act.type().field("seconds"), params);
                a.block(new Blocker.Sleep(now + Math.round(seconds * 1000)));
            }
            case MESSAGE -> {
                String text = evaluator.eval(act.expr("text"), params).display();
                String shown = text.length() > Limits.MAX_TEXT_LENGTH ? text.substring(0, Limits.MAX_TEXT_LENGTH) + "..." : text;
                ScriptRun.hostCall(act.path(), "show a message", () -> {
                    host.showMessage(shown);
                    return null;
                });
                a.advance();
            }
            case STOP -> run.end(RunStatus.FINISHED, "the script stopped itself (stop at " + act.path() + ")");
            default -> hostTask(a, act, params, now);
        }
    }

    private void hostTask(Activation a, Stmt.Action act, Map<String, Value> params, long now) {
        if (act.type() == ActionType.SEND_CHAT) {
            if (!ScriptRun.hostCall(act.path(), "read the chat setting", host::chatAllowed)) {
                throw new ScriptRuntimeException(act.path(), "send_chat refused: scripts are not allowed to send chat"
                        + " messages; turn on the script chat setting in Alto's settings if you really want this");
            }
            if (lastChatAt != Long.MIN_VALUE && now < lastChatAt + options.minChatIntervalMillis) {
                a.block(new Blocker.Retry(lastChatAt + options.minChatIntervalMillis));
                return;
            }
        }
        if (!slot.acquire(a)) {
            a.block(new Blocker.AwaitTurn());
            return;
        }
        ActionRequest request = resolver.resolve(act, params);
        if (request instanceof ActionRequest.Command command && !ScriptRun.hostCall(act.path(),
                "read the allowed commands", () -> host.commandAllowed(command.command()))) {
            throw new ScriptRuntimeException(act.path(), "command refused: '" + command.command()
                    + "' is not on the allowed commands list in Alto's settings");
        }
        if (options.maxTotalActions > 0 && actionsStarted >= options.maxTotalActions) {
            run.end(RunStatus.STOPPED, "the maximum of " + options.maxTotalActions + " actions was reached");
            return;
        }
        ActionCall call = new ActionCall(act.path(), request);
        ActionHandle handle = ScriptRun.hostCall(act.path(), "start " + act.type().jsonName(), () -> host.start(call));
        if (handle == null) {
            throw new ScriptRuntimeException(act.path(), "Alto could not start " + act.type().jsonName());
        }
        actionsStarted++;
        if (act.type() == ActionType.SEND_CHAT) {
            lastChatAt = now;
        }
        slot.started(handle, call);
        a.block(new Blocker.Task(call));
    }
}
