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

/**
 * Interpreter limits and knobs. Mutable plain configuration (like {@code PlannerOptions}); {@link ScriptRun} copies
 * it on construction, so later changes do not affect a running script.
 */
public final class RunOptions {

    /** Statement + expression steps per tick before the interpreter waits for the next tick. */
    public int stepBudget = 200;
    /**
     * Runtime loop guard: a handler that uses up the whole step budget in this many ticks in a row while looping,
     * without waiting once, fails the run.
     */
    public int loopGuardTicks = 2;
    /** Maximum nesting of procedure calls (recursion is already rejected by the validator). */
    public int maxCallDepth = 32;
    /** Run time limit in milliseconds (paused time excluded); 0 = none. Reaching it stops the run. */
    public long maxRuntimeMillis = 0;
    /** Maximum number of host actions the run may start; 0 = none. Reaching it stops the run. */
    public int maxTotalActions = 0;
    /** How often {@code on_inventory_full}, {@code on_health_below} and {@code on_item_below} are checked. */
    public long conditionCheckMillis = 1000;
    /** Minimum time between two {@code send_chat} actions; a faster one waits. */
    public long minChatIntervalMillis = 1000;
    /** Seed for {@code random}; the binding passes a fresh seed per run, tests a fixed one. */
    public long randomSeed = 0;

    public RunOptions copy() {
        RunOptions o = new RunOptions();
        o.stepBudget = stepBudget;
        o.loopGuardTicks = loopGuardTicks;
        o.maxCallDepth = maxCallDepth;
        o.maxRuntimeMillis = maxRuntimeMillis;
        o.maxTotalActions = maxTotalActions;
        o.conditionCheckMillis = conditionCheckMillis;
        o.minChatIntervalMillis = minChatIntervalMillis;
        o.randomSeed = randomSeed;
        return o;
    }

    void check() {
        if (stepBudget < 1 || loopGuardTicks < 1 || maxCallDepth < 1 || maxRuntimeMillis < 0 || maxTotalActions < 0
                || conditionCheckMillis < 0 || minChatIntervalMillis < 0) {
            throw new IllegalArgumentException("invalid run options");
        }
    }
}
