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

import java.util.ArrayDeque;

/**
 * Internal: the player's one body. At most one host action runs at a time; a handler that wants to start an action
 * while the slot is taken waits in a first-come, first-served queue.
 */
final class ActionSlot {

    private final ArrayDeque<Activation> queue = new ArrayDeque<>();
    private Activation owner;
    private ActionHandle handle;
    private ActionCall call;

    /**
     * Takes the slot for {@code a} when it is free and {@code a} is first in line, otherwise puts {@code a} in line.
     * An owner whose action was cancelled by a pause still has the slot.
     */
    boolean acquire(Activation a) {
        if (owner == a) {
            return true;
        }
        if (owner == null && (queue.isEmpty() || queue.peekFirst() == a)) {
            queue.remove(a);
            owner = a;
            return true;
        }
        if (!queue.contains(a)) {
            queue.addLast(a);
        }
        return false;
    }

    /** Whether {@code a}, waiting in line, may try to start its action now. */
    boolean isNext(Activation a) {
        return owner == null && queue.peekFirst() == a;
    }

    void started(ActionHandle handle, ActionCall call) {
        this.handle = handle;
        this.call = call;
    }

    boolean running() {
        return handle != null;
    }

    Activation owner() {
        return owner;
    }

    ActionHandle handle() {
        return handle;
    }

    ActionCall call() {
        return call;
    }

    /** The action ended by itself (succeeded or failed): free the slot without cancelling anything. */
    void release() {
        owner = null;
        handle = null;
        call = null;
    }

    /** Cancels the running action; the owner keeps the slot (pause). */
    void cancelKeepTurn() {
        ActionHandle h = handle;
        handle = null;
        call = null;
        if (h != null) {
            try {
                h.cancel();
            } catch (RuntimeException ignored) {
                // pausing or ending anyway; a broken cancel must not crash the tick
            }
        }
    }

    /** Cancels the running action and forgets every owner and waiter (end of the run). */
    void clear() {
        cancelKeepTurn();
        owner = null;
        queue.clear();
    }

    /** Removes an activation that ended from the line. */
    void forget(Activation a) {
        queue.remove(a);
    }
}
