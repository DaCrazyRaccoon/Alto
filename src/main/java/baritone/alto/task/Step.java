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

package baritone.alto.task;

import baritone.api.pathing.goals.Goal;

/**
 * What a task wants this tick. Movement is requested as a Baritone goal (Baritone's pathfinder does the walking);
 * GUI work holds still ({@link #stay()}); {@link #defer()} lets another Baritone process (such as the miner) act.
 */
public record Step(Kind kind, Goal goal, String message) {

    public enum Kind {
        /** Walk towards {@link #goal()}. */
        WALK,
        /** Stand still this tick (container work, waiting for the server). */
        STAY,
        /** Let lower-priority Baritone processes run this tick (e.g. MineProcess doing a gather step). */
        DEFER,
        SUCCESS,
        FAILURE
    }

    private static final Step STAY = new Step(Kind.STAY, null, null);
    private static final Step DEFER = new Step(Kind.DEFER, null, null);

    public static Step walk(Goal goal) {
        return new Step(Kind.WALK, goal, null);
    }

    public static Step stay() {
        return STAY;
    }

    public static Step defer() {
        return DEFER;
    }

    public static Step success(String message) {
        return new Step(Kind.SUCCESS, null, message);
    }

    public static Step failure(String message) {
        return new Step(Kind.FAILURE, null, message);
    }

    public boolean finished() {
        return kind == Kind.SUCCESS || kind == Kind.FAILURE;
    }
}
