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
 * A running action started by {@link ScriptHost#start}. The interpreter polls it once per tick until it is no longer
 * {@link Status#RUNNING}; it calls {@link #cancel()} when the run is paused, stopped or fails while the action runs.
 */
public interface ActionHandle {

    enum Status {
        RUNNING,
        SUCCEEDED,
        /** The run fails with {@link #failureReason()}. */
        FAILED
    }

    Status poll();

    /** Short human-readable reason, read once after {@link #poll()} returned {@link Status#FAILED}. */
    String failureReason();

    /** Stops the action as soon as possible (stop walking, close screens). Must be safe to call more than once. */
    void cancel();
}
