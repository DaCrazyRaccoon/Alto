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
 * Life cycle of a {@link ScriptRun}. The last three are final.
 */
public enum RunStatus {
    /** Executing, waiting for a task/timer/condition, or idle waiting for events. */
    RUNNING,
    /** {@link ScriptRun#pause()}: nothing advances, timers are frozen, the running action was cancelled. */
    PAUSED,
    /** Every handler ended (and none can start again), or a {@code stop} block ran. */
    FINISHED,
    /** A runtime error or a failed action; see {@link ScriptRun#lastError()}. */
    FAILED,
    /** {@link ScriptRun#stop()} or a run limit (max run time, max actions). */
    STOPPED;

    public boolean isEnded() {
        return this == FINISHED || this == FAILED || this == STOPPED;
    }
}
