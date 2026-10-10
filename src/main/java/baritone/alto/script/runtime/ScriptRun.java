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

import baritone.alto.script.check.ScriptValidator;
import baritone.alto.script.codec.ScriptCodec;
import baritone.alto.script.lang.ActionType;
import baritone.alto.script.lang.Diagnostic;
import baritone.alto.script.lang.Event;
import baritone.alto.script.lang.EventType;
import baritone.alto.script.lang.Expr;
import baritone.alto.script.lang.Fields;
import baritone.alto.script.lang.Ids;
import baritone.alto.script.lang.Limits;
import baritone.alto.script.lang.Script;
import baritone.alto.script.lang.Stmt;
import baritone.alto.script.lang.Value;
import baritone.alto.script.runtime.Activation.Blocker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One run of a validated script: a deterministic, tick-driven interpreter without threads.
 * <p>
 * <b>Ticks.</b> The host calls {@link #tick()} once per game tick. A tick first polls the running host action, then
 * starts due event handlers ({@code every} timers and condition checks), then executes statements of every active
 * handler, oldest first, until each one waits (for an action, a {@code wait} timer or a false {@code wait_until})
 * or the tick's step budget ({@link RunOptions#stepBudget}) is used up. Actions are polled the tick after they
 * start, so every host action takes at least one tick.
 * <p>
 * <b>Handlers.</b> {@code on_start} starts when the run is created. Other handlers start when their event happens
 * and they are not already running (no re-entrancy, nothing is queued): a chat trigger typed while its handler runs
 * is ignored ({@link #fire} returns false); an {@code every} tick that comes while its handler runs is skipped;
 * a condition event ({@code on_inventory_full}, {@code on_health_below}, {@code on_item_below}) is edge-triggered
 * (it starts when its condition becomes true, and again only after the condition was false in between), and if its
 * handler is still running when the condition becomes true it starts as soon as the handler ends, if the condition
 * still holds then. Several handlers can be active at once; they share variables.
 * <p>
 * <b>One body.</b> Only one host action runs at a time. A handler that wants to start an action while another
 * handler's action runs waits for its turn; turns are handed out first come, first served. Actions are never
 * interrupted by other handlers.
 * <p>
 * <b>Ending.</b> A run without handlers other than {@code on_start} finishes when {@code on_start} ends; otherwise it
 * stays {@link RunStatus#RUNNING} (idle between events) until a {@code stop} block, {@link #stop()} or a limit. Any
 * runtime error or failed action fails the whole run ({@link #lastError()}). The runtime loop guard fails a handler
 * that loops through the whole step budget in {@link RunOptions#loopGuardTicks} ticks in a row without waiting once.
 * <p>
 * Not thread-safe: call every method on the client thread.
 */
public final class ScriptRun {

    /** Per handler: its activation (if running), condition arming and timer. */
    private static final class HandlerState {
        final int index;
        final Script.EventHandler handler;
        Activation running;
        boolean armed = true;
        long nextFireAt;

        HandlerState(int index, Script.EventHandler handler) {
            this.index = index;
            this.handler = handler;
        }
    }

    private final Script script;
    private final ScriptHost host;
    private final RunOptions options;
    private final String scriptHash;
    private final Map<String, Script.Procedure> procedures = new HashMap<>();
    private final LinkedHashMap<String, Value> variables = new LinkedHashMap<>();
    private final Evaluator evaluator;
    private final ActionResolver resolver;
    private final List<HandlerState> handlers = new ArrayList<>();
    private final boolean listening;
    private final boolean hasConditionHandlers;
    private final List<Activation> activations = new ArrayList<>();
    private final ArrayDeque<Activation> turnQueue = new ArrayDeque<>();
    private final long startMillis;

    private RunStatus status = RunStatus.RUNNING;
    private ScriptError lastError;
    private String endReason = "";
    private long pausedAtMillis;
    private long pausedTotalMillis;
    private int nextActivationId;
    private long nextConditionCheck;

    private Activation slotOwner;
    private ActionHandle slotHandle;
    private ActionCall slotCall;
    private long lastChatAt = Long.MIN_VALUE;

    private long ticks;
    private long totalSteps;
    private int stepsThisTick;
    private int stepsLastTick;
    private int actionsStarted;

    /**
     * Creates a run and starts {@code on_start} (it executes on the first {@link #tick()}).
     *
     * @throws IllegalArgumentException when the script has validation errors or the options are invalid
     */
    public ScriptRun(Script script, ScriptHost host, RunOptions options) {
        this(script, host, options, null);
    }

    private ScriptRun(Script script, ScriptHost host, RunOptions options, RunSnapshot snapshot) {
        List<Diagnostic> diagnostics = ScriptValidator.validate(script);
        for (Diagnostic d : diagnostics) {
            if (d.isError()) {
                throw new IllegalArgumentException("the script has errors and cannot run: " + d);
            }
        }
        this.script = script;
        this.host = Objects.requireNonNull(host, "host");
        this.options = options.copy();
        this.options.check();
        this.scriptHash = ScriptCodec.hash(script);
        for (Script.Procedure p : script.procedures()) {
            procedures.put(p.name(), p);
        }
        for (Script.VarDecl v : script.variables()) {
            variables.put(v.name(), v.initial());
        }
        this.evaluator = new Evaluator(this, this.options.randomSeed);
        this.resolver = new ActionResolver(evaluator);
        this.startMillis = host.nowMillis();
        boolean listens = false;
        boolean conditions = false;
        for (int i = 0; i < script.handlers().size(); i++) {
            HandlerState h = new HandlerState(i, script.handlers().get(i));
            Event event = h.handler.event();
            listens |= event.type() != EventType.ON_START;
            conditions |= isCondition(event.type());
            if (event instanceof Event.Every every) {
                h.nextFireAt = periodMillis(every);
            }
            handlers.add(h);
        }
        this.listening = listens;
        this.hasConditionHandlers = conditions;
        if (snapshot != null) {
            snapshot.variables().forEach((name, value) -> {
                Value declared = variables.get(name);
                if (declared != null && declared.type() == value.type()) {
                    variables.put(name, value);
                }
            });
        }
        for (HandlerState h : handlers) {
            boolean wasActiveChat = snapshot != null && h.handler.event().type() == EventType.ON_CHAT
                    && snapshot.activeHandlers().contains(h.handler.path());
            if (h.handler.event().type() == EventType.ON_START || wasActiveChat) {
                startHandler(h);
            }
        }
    }

    /**
     * Offers a saved run again (see {@link RunSnapshot} for exactly what is restored). The host must have asked the
     * user first.
     *
     * @throws IllegalArgumentException when the snapshot belongs to a different script version, or on script errors
     */
    public static ScriptRun restore(Script script, ScriptHost host, RunOptions options, RunSnapshot snapshot) {
        String hash = ScriptCodec.hash(script);
        if (!hash.equals(snapshot.scriptHash())) {
            throw new IllegalArgumentException("the saved run belongs to a different version of this script");
        }
        return new ScriptRun(script, host, options, snapshot);
    }

    // ------------------------------------------------------------------------------------------ control

    /** Advances the run by one game tick. Does nothing unless {@link RunStatus#RUNNING}. */
    public void tick() {
        if (status != RunStatus.RUNNING) {
            return;
        }
        ticks++;
        stepsThisTick = 0;
        try {
            long now = clock();
            if (options.maxRuntimeMillis > 0 && now >= options.maxRuntimeMillis) {
                end(RunStatus.STOPPED, "the maximum run time of " + seconds(options.maxRuntimeMillis) + " was reached");
                return;
            }
            pollTask();
            if (status == RunStatus.RUNNING) {
                startDueHandlers(now);
            }
            if (status == RunStatus.RUNNING) {
                runActivations(now);
            }
            if (status == RunStatus.RUNNING && activations.isEmpty() && !listening) {
                end(RunStatus.FINISHED, "finished");
            }
        } catch (ScriptRuntimeException e) {
            fail(e.path(), e.getMessage());
        } finally {
            stepsLastTick = stepsThisTick;
        }
    }

    /**
     * An event from the host. {@link EventType#ON_CHAT}: {@code payload} is text the local user typed (the host must
     * never pass text received from the server); it starts the handler whose trigger equals the trimmed text,
     * ignoring case. {@link EventType#ON_INVENTORY_FULL}, {@link EventType#ON_HEALTH_BELOW},
     * {@link EventType#ON_ITEM_BELOW}: check those conditions now instead of at the next periodic check
     * ({@code payload} is ignored).
     *
     * @return whether a handler started
     * @throws IllegalArgumentException for {@code on_start} and {@code every}, which the interpreter starts itself
     */
    public boolean fire(EventType type, String payload) {
        Objects.requireNonNull(type, "type");
        if (type == EventType.ON_START || type == EventType.EVERY) {
            throw new IllegalArgumentException(type.jsonName() + " is started by the interpreter itself");
        }
        if (status != RunStatus.RUNNING) {
            return false;
        }
        try {
            if (type == EventType.ON_CHAT) {
                return chat(payload);
            }
            return checkConditions();
        } catch (ScriptRuntimeException e) {
            fail(e.path(), e.getMessage());
            return false;
        }
    }

    /**
     * Freezes the run: cancels the running action (its block runs again from the start on {@link #resume()}),
     * freezes {@code wait}, {@code wait_until} timeouts and {@code every} timers, ignores events.
     */
    public void pause() {
        if (status != RunStatus.RUNNING) {
            return;
        }
        status = RunStatus.PAUSED;
        pausedAtMillis = host.nowMillis();
        if (slotHandle != null) {
            cancelTask();
            slotOwner.blocker = null; // keeps its turn and re-runs the action block when resumed
        }
    }

    public void resume() {
        if (status != RunStatus.PAUSED) {
            return;
        }
        pausedTotalMillis += Math.max(0, host.nowMillis() - pausedAtMillis);
        status = RunStatus.RUNNING;
    }

    /** Ends the run ({@link RunStatus#STOPPED}) and cancels the running action. */
    public void stop() {
        if (!status.isEnded()) {
            end(RunStatus.STOPPED, "stopped");
        }
    }

    /** What to save to offer this run again after a restart. */
    public RunSnapshot snapshot() {
        List<String> active = new ArrayList<>();
        for (Activation a : activations) {
            if (!a.done) {
                active.add(a.handler.path());
            }
        }
        return new RunSnapshot(scriptHash, variables, active, host.nowMillis());
    }

    // ------------------------------------------------------------------------------------------ state

    public RunStatus status() {
        return status;
    }

    /** Why the run failed, or {@code null}. */
    public ScriptError lastError() {
        return lastError;
    }

    /** Why the run ended ({@code finished}, {@code stopped}, a limit, or the error); empty while running. */
    public String endReason() {
        return endReason;
    }

    public Script script() {
        return script;
    }

    public String scriptHash() {
        return scriptHash;
    }

    /** Current variable values in declaration order (a copy). */
    public Map<String, Value> variables() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    /** Active handlers, oldest first. */
    public List<ActivationView> activations() {
        List<ActivationView> views = new ArrayList<>();
        for (Activation a : activations) {
            if (!a.done) {
                views.add(view(a));
            }
        }
        return views;
    }

    /**
     * The handler shown in the runner's main line: the one whose action is running, else the oldest active one;
     * {@code null} when idle.
     */
    public ActivationView current() {
        Activation a = slotOwner != null ? slotOwner : activations.isEmpty() ? null : activations.get(0);
        return a == null ? null : view(a);
    }

    /** Path of {@link #current()}'s block, or an empty string when idle. */
    public String currentPath() {
        ActivationView v = current();
        return v == null ? "" : v.path();
    }

    /** Block type label of {@link #current()}, or an empty string when idle. */
    public String currentBlock() {
        ActivationView v = current();
        return v == null ? "" : v.block();
    }

    /** Running with no active handler (waiting for events). */
    public boolean isIdle() {
        return status == RunStatus.RUNNING && activations.isEmpty();
    }

    public long ticks() {
        return ticks;
    }

    public long totalSteps() {
        return totalSteps;
    }

    public int stepsLastTick() {
        return stepsLastTick;
    }

    public int actionsStarted() {
        return actionsStarted;
    }

    /** Milliseconds the run has been running, paused time excluded. */
    public long runMillis() {
        return status == RunStatus.PAUSED ? pausedAtMillis - startMillis - pausedTotalMillis : clock();
    }

    // ------------------------------------------------------------------------------------------ package API

    ScriptHost host() {
        return host;
    }

    Value variableValue(String name) {
        return variables.get(name);
    }

    void countStep() {
        stepsThisTick++;
        totalSteps++;
    }

    /** Calls the host; a host exception becomes a script error at {@code path}. */
    <T> T hostCall(String path, String what, Supplier<T> call) {
        try {
            return call.get();
        } catch (ScriptRuntimeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ScriptRuntimeException(path, "Alto could not " + what + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------ events

    private static boolean isCondition(EventType type) {
        return type == EventType.ON_INVENTORY_FULL || type == EventType.ON_HEALTH_BELOW || type == EventType.ON_ITEM_BELOW;
    }

    private static long periodMillis(Event.Every every) {
        return Math.max(1, Math.round(every.seconds() * 1000));
    }

    private void startHandler(HandlerState h) {
        Activation a = new Activation(nextActivationId++, h.index, h.handler);
        activations.add(a);
        h.running = a;
    }

    private boolean chat(String text) {
        if (text == null) {
            return false;
        }
        String typed = text.trim();
        for (HandlerState h : handlers) {
            if (h.handler.event() instanceof Event.OnChat c && c.trigger().trim().equalsIgnoreCase(typed)) {
                if (h.running != null) {
                    return false;
                }
                startHandler(h);
                return true;
            }
        }
        return false;
    }

    private void startDueHandlers(long now) {
        for (HandlerState h : handlers) {
            if (h.handler.event() instanceof Event.Every every && now >= h.nextFireAt) {
                if (h.running == null) {
                    startHandler(h);
                }
                long period = periodMillis(every);
                h.nextFireAt += ((now - h.nextFireAt) / period + 1) * period; // skip missed periods, no bursts
            }
        }
        if (hasConditionHandlers && now >= nextConditionCheck) {
            nextConditionCheck = now + options.conditionCheckMillis;
            checkConditions();
        }
    }

    /** Edge-triggered condition events; see the class comment. */
    private boolean checkConditions() {
        boolean started = false;
        for (HandlerState h : handlers) {
            String path = h.handler.path();
            Boolean holds = switch (h.handler.event()) {
                case Event.OnInventoryFull ignored ->
                        ((Value.Bool) evaluator.query(new QueryCall.InventoryFull(), path)).value();
                case Event.OnHealthBelow e -> number(evaluator.query(new QueryCall.Health(), path)) < e.health();
                case Event.OnItemBelow e -> number(evaluator.query(
                        new QueryCall.ItemCount(Ids.normalize(e.item()), e.where()), path)) < e.count();
                default -> null;
            };
            if (holds == null) {
                continue;
            }
            if (!holds) {
                h.armed = true;
            } else if (h.armed && h.running == null) {
                h.armed = false;
                startHandler(h);
                started = true;
            }
        }
        return started;
    }

    private static double number(Value v) {
        return ((Value.Num) v).value();
    }

    // ------------------------------------------------------------------------------------------ execution

    private void pollTask() {
        if (slotHandle == null) {
            return;
        }
        ActionCall call = slotCall;
        ActionHandle.Status s = hostCall(call.path(), "check " + call.type().jsonName(), slotHandle::poll);
        if (s == null || s == ActionHandle.Status.RUNNING) {
            return;
        }
        if (s == ActionHandle.Status.SUCCEEDED) {
            Activation owner = slotOwner;
            releaseSlot();
            owner.blocker = null;
            owner.advance();
            return;
        }
        String reason = hostCall(call.path(), "read why " + call.type().jsonName() + " failed", slotHandle::failureReason);
        releaseSlot(); // a failed action has ended; do not cancel it
        throw new ScriptRuntimeException(call.path(), call.type().jsonName() + " failed: "
                + (reason == null || reason.isBlank() ? "no reason given" : reason));
    }

    private void runActivations(long now) {
        Activation exhausted = null;
        for (Activation a : new ArrayList<>(activations)) {
            if (status != RunStatus.RUNNING) {
                return;
            }
            if (a.done || (a.blocker != null && !tryUnblock(a, now))) {
                continue;
            }
            while (status == RunStatus.RUNNING && !a.done && a.blocker == null && exhausted == null) {
                if (stepsThisTick >= options.stepBudget) {
                    exhausted = a;
                } else {
                    step(a, now);
                }
            }
            if (exhausted != null) {
                break;
            }
        }
        for (Iterator<Activation> it = activations.iterator(); it.hasNext(); ) {
            Activation a = it.next();
            if (a.done) {
                it.remove();
                turnQueue.remove(a);
                HandlerState h = handlers.get(a.handlerIndex);
                if (h.running == a) {
                    h.running = null;
                }
            }
        }
        if (exhausted != null && !exhausted.done && exhausted.loopPassesSinceYield > 0
                && ++exhausted.guardStrikes >= options.loopGuardTicks) {
            throw new ScriptRuntimeException(exhausted.lastLoopPath, "runtime loop guard: this loop used the whole step"
                    + " budget (" + options.stepBudget + " steps) in " + exhausted.guardStrikes + " ticks in a row"
                    + " without waiting; add a wait block or an action inside the loop");
        }
    }

    private boolean tryUnblock(Activation a, long now) {
        switch (a.blocker) {
            case Blocker.Task ignored -> {
                return false;
            }
            case Blocker.AwaitTurn ignored -> {
                if (slotOwner == null && turnQueue.peekFirst() == a) {
                    a.blocker = null;
                    return true;
                }
                return false;
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
                countStep();
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

    private void step(Activation a, long now) {
        Frame f = a.frames.peek();
        if (f.index >= f.body.size()) {
            endOfFrame(a, f);
            return;
        }
        Stmt s = f.body.get(f.index);
        countStep();
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
                if (!hostCall(fe.path(), "read the safety stops setting", host::safetyStopsEnabled)) {
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
            case Stmt.WaitUntil w -> {
                if (evaluator.bool(w.condition(), params, "condition")) {
                    f.index++;
                } else {
                    long deadline = -1;
                    double seconds = 0;
                    if (w.timeoutSeconds() != null) {
                        seconds = evaluator.number(w.timeoutSeconds(), Fields.TIMEOUT_SECONDS, params);
                        deadline = now + Math.round(seconds * 1000);
                    }
                    a.block(new Blocker.Condition(w, deadline, seconds));
                }
            }
            case Stmt.Break b -> breakLoop(a, b);
            case Stmt.Call c -> call(a, f, c, params);
            case Stmt.SetVar sv -> {
                Value value = evaluator.eval(sv.value(), params);
                Value old = variables.get(sv.name());
                if (old == null || old.type() != value.type()) {
                    throw new ScriptRuntimeException(sv.path(), "cannot store " + value.type().withArticle()
                            + " in variable '" + sv.name() + "'");
                }
                if (value instanceof Value.Str str && str.value().length() > Limits.MAX_STRING_VALUE) {
                    throw new ScriptRuntimeException(sv.path(), "text is longer than " + Limits.MAX_STRING_VALUE + " characters");
                }
                variables.put(sv.name(), value);
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
        countStep();
        f.index = 0;
        a.loopPassesSinceYield++;
        a.lastLoopPath = f.owner.path();
    }

    private void breakLoop(Activation a, Stmt.Break b) {
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

    private void action(Activation a, Stmt.Action act, Map<String, Value> params, long now) {
        switch (act.type()) {
            case WAIT -> {
                double seconds = evaluator.number(act.expr("seconds"), act.type().field("seconds"), params);
                a.block(new Blocker.Sleep(now + Math.round(seconds * 1000)));
            }
            case MESSAGE -> {
                String text = evaluator.eval(act.expr("text"), params).display();
                String shown = text.length() > Limits.MAX_TEXT_LENGTH ? text.substring(0, Limits.MAX_TEXT_LENGTH) + "..." : text;
                hostCall(act.path(), "show a message", () -> {
                    host.showMessage(shown);
                    return null;
                });
                a.advance();
            }
            case STOP -> end(RunStatus.FINISHED, "the script stopped itself (stop at " + act.path() + ")");
            default -> hostTask(a, act, params, now);
        }
    }

    private void hostTask(Activation a, Stmt.Action act, Map<String, Value> params, long now) {
        if (act.type() == ActionType.SEND_CHAT) {
            if (!hostCall(act.path(), "read the chat setting", host::chatAllowed)) {
                throw new ScriptRuntimeException(act.path(), "send_chat refused: scripts are not allowed to send chat"
                        + " messages; turn on the script chat setting in Alto's settings if you really want this");
            }
            if (lastChatAt != Long.MIN_VALUE && now < lastChatAt + options.minChatIntervalMillis) {
                a.block(new Blocker.Retry(lastChatAt + options.minChatIntervalMillis));
                return;
            }
        }
        if (!acquireTurn(a)) {
            a.block(new Blocker.AwaitTurn());
            return;
        }
        ActionRequest request = resolver.resolve(act, params);
        if (request instanceof ActionRequest.Command command
                && !hostCall(act.path(), "read the allowed commands", () -> host.commandAllowed(command.command()))) {
            throw new ScriptRuntimeException(act.path(), "command refused: '" + command.command()
                    + "' is not on the allowed commands list in Alto's settings");
        }
        if (options.maxTotalActions > 0 && actionsStarted >= options.maxTotalActions) {
            end(RunStatus.STOPPED, "the maximum of " + options.maxTotalActions + " actions was reached");
            return;
        }
        ActionCall call = new ActionCall(act.path(), request);
        ActionHandle handle = hostCall(act.path(), "start " + act.type().jsonName(), () -> host.start(call));
        if (handle == null) {
            throw new ScriptRuntimeException(act.path(), "Alto could not start " + act.type().jsonName());
        }
        actionsStarted++;
        if (act.type() == ActionType.SEND_CHAT) {
            lastChatAt = now;
        }
        slotHandle = handle;
        slotCall = call;
        a.block(new Blocker.Task(call));
    }

    /** First come, first served; an owner whose action was cancelled by pause keeps its turn. */
    private boolean acquireTurn(Activation a) {
        if (slotOwner == a) {
            return true;
        }
        if (slotOwner == null && (turnQueue.isEmpty() || turnQueue.peekFirst() == a)) {
            turnQueue.remove(a);
            slotOwner = a;
            return true;
        }
        if (!turnQueue.contains(a)) {
            turnQueue.addLast(a);
        }
        return false;
    }

    private void releaseSlot() {
        slotOwner = null;
        slotHandle = null;
        slotCall = null;
    }

    // ------------------------------------------------------------------------------------------ ending

    private void cancelTask() {
        ActionHandle handle = slotHandle;
        slotHandle = null;
        slotCall = null;
        if (handle != null) {
            try {
                handle.cancel();
            } catch (RuntimeException ignored) {
                // the run is ending or pausing anyway; a broken cancel must not crash the tick
            }
        }
    }

    private void end(RunStatus newStatus, String reason) {
        cancelTask();
        status = newStatus;
        endReason = reason;
        activations.clear();
        turnQueue.clear();
        slotOwner = null;
        for (HandlerState h : handlers) {
            h.running = null;
        }
    }

    private void fail(String path, String message) {
        lastError = new ScriptError(path, message);
        end(RunStatus.FAILED, lastError.toString());
    }

    private long clock() {
        return host.nowMillis() - startMillis - pausedTotalMillis;
    }

    private static String seconds(long millis) {
        return Value.formatNumber(millis / 1000.0) + " s";
    }

    // ------------------------------------------------------------------------------------------ views

    private ActivationView view(Activation a) {
        Stmt current = a.currentStatement();
        List<String> stack = new ArrayList<>();
        for (Iterator<Frame> it = a.frames.descendingIterator(); it.hasNext(); ) {
            Frame f = it.next();
            if (f.kind == Frame.Kind.CALL) {
                stack.add(f.procedure.name() + " (called at " + f.owner.path() + ")");
            }
        }
        return new ActivationView(a.id, a.handler.event().label(), a.handler.path(),
                current == null ? a.handler.path() : current.path(), current == null ? "" : current.block(),
                stack, waitingFor(a));
    }

    private String waitingFor(Activation a) {
        if (a.blocker == null) {
            return "";
        }
        long now = status == RunStatus.PAUSED ? pausedAtMillis - startMillis - pausedTotalMillis : clock();
        return switch (a.blocker) {
            case Blocker.Task t -> "task " + t.call().type().jsonName();
            case Blocker.AwaitTurn ignored -> "its turn (another handler's action is running)";
            case Blocker.Sleep s -> "wait " + seconds(Math.max(0, s.untilMillis() - now));
            case Blocker.Retry ignored -> "chat cooldown";
            case Blocker.Condition c -> "condition" + (c.deadlineMillis() < 0 ? ""
                    : " (gives up in " + seconds(Math.max(0, c.deadlineMillis() - now)) + ")");
        };
    }
}
