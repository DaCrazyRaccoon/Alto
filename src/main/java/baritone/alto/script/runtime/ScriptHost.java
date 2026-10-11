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

import baritone.alto.script.lang.Value;

/**
 * What a {@link ScriptRun} needs from the game. The Minecraft binding implements this; tests use a mock world.
 * All methods are called on the thread that calls {@link ScriptRun#tick()} (the client thread), never concurrently.
 * They must not call back into the {@link ScriptRun} (no {@code fire}, {@code stop}... from inside a callback; do it
 * on the next tick instead). A {@link RuntimeException} thrown by {@link #start}, {@link #query}, the setting checks,
 * {@link #showMessage} or the handle's {@code poll} fails the run with a clear error instead of crashing the tick.
 */
public interface ScriptHost {

    /**
     * Starts an action in the world and returns its handle; the interpreter polls it once per tick, starting with
     * the tick after this call. Never called for {@code wait}, {@code message} or {@code stop}. Returning
     * {@code null} fails the run ("could not start").
     * <p>
     * Before this is called for {@code send_chat} the interpreter has checked {@link #chatAllowed()}; for
     * {@code command} it has checked {@link #commandAllowed(String)}. The host may refuse again (fail the handle).
     */
    ActionHandle start(ActionCall call);

    /** Reads world state; must return a value of {@link QueryCall#resultType()}. */
    Value query(QueryCall query);

    /**
     * Whether Alto's safety stops (low health, hostile mobs, lava, hunger...) are enabled. {@code forever} loops
     * refuse to start without them.
     */
    boolean safetyStopsEnabled();

    /** Whether scripts may send real chat messages ({@code send_chat}); off by default. */
    boolean chatAllowed();

    /** Whether this normalized Baritone command (no {@code #} prefix) is on the user's allow-list. */
    boolean commandAllowed(String command);

    /** Shows a message to the local user only (never sent to the server). */
    void showMessage(String text);

    /** Milliseconds from any fixed origin (monotonic preferred). Timers, {@code every} and limits use it. */
    long nowMillis();
}
