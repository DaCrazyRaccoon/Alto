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
import baritone.alto.script.lang.Diagnostic;
import baritone.alto.script.lang.EventType;
import baritone.alto.script.lang.Script;
import baritone.alto.script.lang.Stmt;
import baritone.alto.script.lang.Value;
import baritone.alto.script.runtime.Activation.Blocker;

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

    private final Script script;
    private final ScriptHost host;
    private final RunOptions options;
    private final String scriptHash;
    private final LinkedHashMap<String, Value> variables = new LinkedHashMap<>();
    private final Evaluator evaluator;
    private final ActionSlot slot = new ActionSlot();
    private final Executor executor;
    private final EventScheduler events;
    private final List<Activation> activations = new ArrayList<>();
    private final long startMillis;

    private RunStatus status = RunStatus.RUNNING;
    private ScriptError lastError;
    private String endReason = "";
    private long pausedAtMillis;
    private long pausedTotalMillis;
    private int nextActivationId;

    private long ticks;
    private long totalSteps;
    private int stepsThisTick;
    private int stepsLastTick;

    /**
     * Creates a run and starts {@code on_start} (it executes on the first {@link #tick()}).
     *
     * @throws IllegalArgumentException when the script has validation errors or the options are invalid
     */
    public ScriptRun(Script script, ScriptHost host, RunOptions options) {
        this(script, host, options, null);
    }

    private ScriptRun(Script script, ScriptHost host, RunOptions options, RunSnapshot snapshot) {
        for (Diagnostic d : ScriptValidator.validate(script)) {
            if (d.isError()) {
                throw new IllegalArgumentException("the script has errors and cannot run: " + d);
            }
        }
        this.script = script;
        this.host = Objects.requireNonNull(host, "host");
        this.options = options.copy();
        this.options.check();
        this.scriptHash = ScriptCodec.hash(script);
        Map<String, Script.Procedure> procedures = new HashMap<>();
        for (Script.Procedure p : script.procedures()) {
            procedures.put(p.name(), p);
        }
        for (Script.VarDecl v : script.variables()) {
            variables.put(v.name(), v.initial());
        }
        if (snapshot != null) {
            snapshot.variables().forEach((name, value) -> {
                Value declared = variables.get(name);
                if (declared != null && declared.type() == value.type()) {
                    variables.put(name, value);
                }
            });
        }
        this.evaluator = new Evaluator(this, this.options.randomSeed);
        this.executor = new Executor(this, host, this.options, procedures, variables, evaluator, slot);
        this.events = new EventScheduler(script, evaluator, this::startActivation, this.options.conditionCheckMillis);
        this.startMillis = host.nowMillis();
        events.startInitial(snapshot);
    }

    /**
     * Offers a saved run again (see {@link RunSnapshot} for exactly what is restored). The host must have asked the
     * user first.
     *
     * @throws IllegalArgumentException when the snapshot belongs to a different script version, or on script errors
     */
    public static ScriptRun restore(Script script, ScriptHost host, RunOptions options, RunSnapshot snapshot) {
        if (!ScriptCodec.hash(script).equals(snapshot.scriptHash())) {
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
            pollAction();
            if (status == RunStatus.RUNNING) {
                events.startDue(now);
            }
            if (status == RunStatus.RUNNING) {
                runActivations(now);
            }
            if (status == RunStatus.RUNNING && activations.isEmpty() && !events.listening()) {
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
            return type == EventType.ON_CHAT ? events.chat(payload) : events.checkConditions();
        } catch (ScriptRuntimeException e) {
            fail(e.path(), e.getMessage());
            return false;
        }
    }

    /**
     * Whether {@code typed} (text the user typed) equals one of the script's chat triggers, using the same rule as
     * {@link #fire}: surrounding spaces removed, case ignored. The binding can use it to keep trigger text from being
     * sent to the server. True even while that handler is running.
     */
    public boolean isChatTrigger(String typed) {
        return events.isChatTrigger(typed);
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
        if (slot.running()) {
            Activation owner = slot.owner();
            slot.cancelKeepTurn();
            owner.blocker = null; // keeps its turn and re-runs the action block when resumed
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
        Activation a = slot.owner() != null ? slot.owner() : activations.isEmpty() ? null : activations.get(0);
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

    /** Statement and expression steps executed since the start. */
    public long totalSteps() {
        return totalSteps;
    }

    public int stepsLastTick() {
        return stepsLastTick;
    }

    /** Host actions started since the start. */
    public int actionsStarted() {
        return executor.actionsStarted();
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
    static <T> T hostCall(String path, String what, Supplier<T> call) {
        try {
            return call.get();
        } catch (ScriptRuntimeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ScriptRuntimeException(path, "Alto could not " + what + ": " + e.getMessage());
        }
    }

    /** Ends the run: cancels the running action and drops every activation. */
    void end(RunStatus newStatus, String reason) {
        slot.clear();
        status = newStatus;
        endReason = reason;
        activations.clear();
        events.clear();
    }

    // ------------------------------------------------------------------------------------------ execution

    private Activation startActivation(int handlerIndex, Script.EventHandler handler) {
        Activation a = new Activation(nextActivationId++, handlerIndex, handler);
        activations.add(a);
        return a;
    }

    private void pollAction() {
        if (!slot.running()) {
            return;
        }
        ActionCall call = slot.call();
        ActionHandle handle = slot.handle();
        String name = call.type().jsonName();
        ActionHandle.Status s = hostCall(call.path(), "check " + name, handle::poll);
        if (s == null) {
            throw new ScriptRuntimeException(call.path(), "Alto returned no status for " + name);
        }
        if (s == ActionHandle.Status.RUNNING) {
            return;
        }
        Activation owner = slot.owner();
        slot.release(); // the action has ended either way; it is not cancelled
        if (s == ActionHandle.Status.SUCCEEDED) {
            owner.blocker = null;
            owner.advance();
            return;
        }
        String reason = hostCall(call.path(), "read why " + name + " failed", handle::failureReason);
        throw new ScriptRuntimeException(call.path(), name + " failed: "
                + (reason == null || reason.isBlank() ? "no reason given" : reason));
    }

    private void runActivations(long now) {
        Activation exhausted = null;
        for (Activation a : new ArrayList<>(activations)) {
            if (status != RunStatus.RUNNING) {
                return;
            }
            if (a.done || (a.blocker != null && !executor.tryUnblock(a, now))) {
                continue;
            }
            while (status == RunStatus.RUNNING && !a.done && a.blocker == null && exhausted == null) {
                if (stepsThisTick >= options.stepBudget) {
                    exhausted = a;
                } else {
                    executor.step(a, now);
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
                slot.forget(a);
                events.ended(a);
            }
        }
        if (exhausted != null && !exhausted.done && exhausted.loopPassesSinceYield > 0
                && ++exhausted.guardStrikes >= options.loopGuardTicks) {
            throw new ScriptRuntimeException(exhausted.lastLoopPath, "runtime loop guard: this loop used the whole step"
                    + " budget (" + options.stepBudget + " steps) in " + exhausted.guardStrikes + " ticks in a row"
                    + " without waiting; add a wait block or an action inside the loop");
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
        long now = runMillis();
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
