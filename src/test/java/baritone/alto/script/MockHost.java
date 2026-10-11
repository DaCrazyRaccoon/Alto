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
import baritone.alto.script.lang.Ids;
import baritone.alto.script.lang.Value;
import baritone.alto.script.lang.Where;
import baritone.alto.script.runtime.ActionCall;
import baritone.alto.script.runtime.ActionHandle;
import baritone.alto.script.runtime.ActionRequest;
import baritone.alto.script.runtime.GotoTarget;
import baritone.alto.script.runtime.QueryCall;
import baritone.alto.script.runtime.RunStatus;
import baritone.alto.script.runtime.ScriptHost;
import baritone.alto.script.runtime.ScriptRun;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * A scripted fake world: inventory and storage counts, player state, a fake clock, and actions that take a
 * configurable number of ticks and then succeed (changing the world) or fail.
 */
public final class MockHost implements ScriptHost {

    /** {@code inputCount} of {@code input} make {@code outputCount} of the output. */
    public record Recipe(String input, int inputCount, int outputCount) {
    }

    public long now = 1_000_000;
    public long millisPerTick = 50;
    public final Map<String, Integer> inventory = new TreeMap<>();
    public final Map<String, Integer> storage = new TreeMap<>();
    public final Map<String, Recipe> recipes = new HashMap<>();
    public final Map<String, String> blocks = new HashMap<>();
    public final Map<String, String> filters = new TreeMap<>();
    public double health = 20;
    public double hunger = 20;
    public int x;
    public int y = 64;
    public int z;
    public String dimension = "minecraft:overworld";
    public double timeOfDay = 1000;
    public boolean inventoryFull;
    /** Inventory counts as full at this many items in total (0 = use {@link #inventoryFull} only). */
    public int fullAtItems;
    public boolean safetyStops = true;
    public boolean chatAllowed;
    public final Set<String> allowedCommands = new HashSet<>();
    public final List<String> messages = new ArrayList<>();
    public final List<ActionCall> started = new ArrayList<>();
    public final List<String> chatSent = new ArrayList<>();
    public final List<String> commandsRun = new ArrayList<>();
    /** Events in order: {@code start craft}, {@code done craft}, {@code fail craft}, {@code cancel craft}. */
    public final List<String> log = new ArrayList<>();
    public int defaultTicks = 2;
    public final Map<ActionType, Integer> ticksFor = new EnumMap<>(ActionType.class);
    public final Map<ActionType, String> failWith = new EnumMap<>(ActionType.class);
    /** Crops a farm pass adds. */
    public final Map<String, Integer> harvest = new TreeMap<>();
    /** Replaces the answer to a query (e.g. a wrong type); return null to fall back to the world. */
    public Function<QueryCall, Value> queryOverride;
    /** Thrown by {@link #start} when set (a broken host). */
    public RuntimeException startFailure;
    public int queries;

    public MockHost() {
        recipes.put("minecraft:oak_planks", new Recipe("minecraft:oak_log", 1, 4));
        recipes.put("minecraft:stick", new Recipe("minecraft:oak_planks", 2, 4));
        recipes.put("minecraft:iron_ingot", new Recipe("minecraft:raw_iron", 1, 1));
        harvest.put("minecraft:sugar_cane", 48);
    }

    public MockHost give(String item, int count) {
        inventory.merge(Ids.normalize(item), count, Integer::sum);
        return this;
    }

    public MockHost stock(String item, int count) {
        storage.merge(Ids.normalize(item), count, Integer::sum);
        return this;
    }

    public int count(String item) {
        return inventory.getOrDefault(Ids.normalize(item), 0);
    }

    public int stored(String item) {
        return storage.getOrDefault(Ids.normalize(item), 0);
    }

    /** Advances the clock by one tick and ticks the run. */
    public void tick(ScriptRun run) {
        now += millisPerTick;
        run.tick();
    }

    public void ticks(ScriptRun run, int n) {
        for (int i = 0; i < n; i++) {
            tick(run);
        }
    }

    /** Ticks until the run ends or {@code maxTicks} pass; returns the ticks used. */
    public int runToEnd(ScriptRun run, int maxTicks) {
        int i = 0;
        while (i < maxTicks && !run.status().isEnded()) {
            tick(run);
            i++;
        }
        return i;
    }

    /** Ticks until the run is idle (no active handler) or ended. */
    public void runUntilIdle(ScriptRun run, int maxTicks) {
        for (int i = 0; i < maxTicks && run.status() == RunStatus.RUNNING; i++) {
            tick(run);
            if (run.isIdle()) {
                return;
            }
        }
    }

    public List<ActionType> startedTypes() {
        List<ActionType> types = new ArrayList<>();
        for (ActionCall c : started) {
            types.add(c.type());
        }
        return types;
    }

    // ------------------------------------------------------------------------------------------ ScriptHost

    @Override
    public ActionHandle start(ActionCall call) {
        if (startFailure != null) {
            throw startFailure;
        }
        started.add(call);
        log.add("start " + call.type().jsonName());
        return new MockAction(call, ticksFor.getOrDefault(call.type(), defaultTicks));
    }

    @Override
    public Value query(QueryCall q) {
        queries++;
        if (queryOverride != null) {
            Value v = queryOverride.apply(q);
            if (v != null) {
                return v;
            }
        }
        return switch (q) {
            case QueryCall.ItemCount ic -> Value.of(ic.where() == Where.INVENTORY
                    ? inventory.getOrDefault(ic.item(), 0) : storage.getOrDefault(ic.item(), 0));
            case QueryCall.Health ignored -> Value.of(health);
            case QueryCall.Hunger ignored -> Value.of(hunger);
            case QueryCall.Position p -> Value.of(switch (p.axis()) {
                case X -> x;
                case Y -> y;
                case Z -> z;
            });
            case QueryCall.TimeOfDay ignored -> Value.of(timeOfDay);
            case QueryCall.Dimension ignored -> Value.of(dimension);
            case QueryCall.BlockAt b -> Value.of(blocks.getOrDefault(b.x() + "," + b.y() + "," + b.z(), "minecraft:air"));
            case QueryCall.InventoryFull ignored -> Value.of(isFull());
        };
    }

    private boolean isFull() {
        if (inventoryFull) {
            return true;
        }
        if (fullAtItems <= 0) {
            return false;
        }
        int total = 0;
        for (int c : inventory.values()) {
            total += c;
        }
        return total >= fullAtItems;
    }

    @Override
    public boolean safetyStopsEnabled() {
        return safetyStops;
    }

    @Override
    public boolean chatAllowed() {
        return chatAllowed;
    }

    @Override
    public boolean commandAllowed(String command) {
        return allowedCommands.contains(command);
    }

    @Override
    public void showMessage(String text) {
        messages.add(text);
    }

    @Override
    public long nowMillis() {
        return now;
    }

    // ------------------------------------------------------------------------------------------ actions

    private final class MockAction implements ActionHandle {
        private final ActionCall call;
        private int ticksLeft;
        private Status status = Status.RUNNING;
        private String reason;
        private boolean cancelled;

        MockAction(ActionCall call, int ticks) {
            this.call = call;
            this.ticksLeft = ticks;
        }

        @Override
        public Status poll() {
            if (status != Status.RUNNING || cancelled) {
                return status;
            }
            if (--ticksLeft > 0) {
                return Status.RUNNING;
            }
            String failure = failWith.get(call.type());
            if (failure == null) {
                failure = apply(call.request());
            }
            if (failure != null) {
                reason = failure;
                status = Status.FAILED;
                log.add("fail " + call.type().jsonName());
            } else {
                status = Status.SUCCEEDED;
                log.add("done " + call.type().jsonName());
            }
            return status;
        }

        @Override
        public String failureReason() {
            return reason;
        }

        @Override
        public void cancel() {
            if (!cancelled) {
                cancelled = true;
                log.add("cancel " + call.type().jsonName());
            }
        }
    }

    /** World effect of a finished action; returns a failure reason or null. */
    private String apply(ActionRequest request) {
        switch (request) {
            case ActionRequest.Craft c -> {
                return produce(c.item(), c.count());
            }
            case ActionRequest.Smelt s -> {
                return produce(s.item(), s.count());
            }
            case ActionRequest.Fetch f -> {
                int have = storage.getOrDefault(f.item(), 0);
                if (have < f.count()) {
                    return "only " + have + " " + f.item() + " in storage";
                }
                storage.put(f.item(), have - f.count());
                inventory.merge(f.item(), f.count(), Integer::sum);
                return null;
            }
            case ActionRequest.Deposit d -> {
                for (String item : new ArrayList<>(inventory.keySet())) {
                    if (d.except().contains(item) || (!d.only().isEmpty() && !d.only().contains(item))) {
                        continue;
                    }
                    storage.merge(item, inventory.remove(item), Integer::sum);
                }
                inventoryFull = false;
                return null;
            }
            case ActionRequest.Mine m -> {
                inventory.merge(m.blockOrItem(), m.count(), Integer::sum);
                return null;
            }
            case ActionRequest.Farm f -> {
                harvest.forEach((item, n) -> {
                    if (f.crops().isEmpty() || f.crops().contains(item)) {
                        inventory.merge(item, n, Integer::sum);
                    }
                });
                return null;
            }
            case ActionRequest.Eat ignored -> {
                hunger = 20;
                health = Math.max(health, 20);
                return null;
            }
            case ActionRequest.Goto g -> {
                if (g.target() instanceof GotoTarget.Coords c) {
                    x = c.x();
                    y = c.y();
                    z = c.z();
                }
                return null;
            }
            case ActionRequest.SendChat s -> {
                chatSent.add(s.text());
                return null;
            }
            case ActionRequest.Command c -> {
                commandsRun.add(c.command());
                return null;
            }
            case ActionRequest.SetFilter f -> {
                filters.put(f.x() + "," + f.y() + "," + f.z(), f.item());
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private String produce(String item, int count) {
        Recipe r = recipes.get(item);
        if (r == null) {
            inventory.merge(item, count, Integer::sum);
            return null;
        }
        int operations = (count + r.outputCount() - 1) / r.outputCount();
        int need = operations * r.inputCount();
        int have = inventory.getOrDefault(r.input(), 0);
        if (have < need) {
            return "not enough " + r.input() + " (have " + have + ", need " + need + ")";
        }
        inventory.put(r.input(), have - need);
        if (have - need == 0) {
            inventory.remove(r.input());
        }
        inventory.merge(item, operations * r.outputCount(), Integer::sum);
        return null;
    }
}
