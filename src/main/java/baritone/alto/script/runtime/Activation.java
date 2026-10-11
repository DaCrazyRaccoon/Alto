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

import baritone.alto.script.lang.Script;
import baritone.alto.script.lang.Stmt;
import baritone.alto.script.lang.Value;

import java.util.ArrayDeque;
import java.util.Map;

/**
 * Internal: one running event handler with its own frame stack (no threads: the interpreter steps activations in a
 * fixed order inside {@link ScriptRun#tick()}).
 */
final class Activation {

    /** What a blocked activation waits for. {@code null} blocker = ready to execute. */
    sealed interface Blocker {
        /** A host action of this activation is running (it owns the action slot). */
        record Task(ActionCall call) implements Blocker {
        }

        /** Waiting for the action slot (another handler's action runs, or others queued first). */
        record AwaitTurn() implements Blocker {
        }

        /** A {@code wait} block; the statement completes at {@code untilMillis}. */
        record Sleep(long untilMillis) implements Blocker {
        }

        /** Re-execute the current statement at {@code atMillis} ({@code send_chat} cooldown). */
        record Retry(long atMillis) implements Blocker {
        }

        /** A {@code wait_until}; {@code deadlineMillis} is -1 without a timeout. */
        record Condition(Stmt.WaitUntil stmt, long deadlineMillis, double timeoutSeconds) implements Blocker {
        }
    }

    final int id;
    final int handlerIndex;
    final Script.EventHandler handler;
    /** Top of the stack is {@link ArrayDeque#peek()}. */
    final ArrayDeque<Frame> frames = new ArrayDeque<>();
    Blocker blocker;
    boolean done;
    /** Loop passes completed since this activation last waited (runtime loop guard). */
    int loopPassesSinceYield;
    /** Ticks in a row in which this activation used up the step budget while looping. */
    int guardStrikes;
    String lastLoopPath = "";

    Activation(int id, int handlerIndex, Script.EventHandler handler) {
        this.id = id;
        this.handlerIndex = handlerIndex;
        this.handler = handler;
        frames.push(Frame.handler(handler.body()));
    }

    /** Arguments of the innermost procedure call, or {@code null} outside procedures. */
    Map<String, Value> params() {
        for (Frame f : frames) {
            if (f.kind == Frame.Kind.CALL) {
                return f.params;
            }
        }
        return null;
    }

    int callDepth() {
        int depth = 0;
        for (Frame f : frames) {
            if (f.kind == Frame.Kind.CALL) {
                depth++;
            }
        }
        return depth;
    }

    /** Moves past the current statement of the top frame. */
    void advance() {
        frames.peek().index++;
    }

    /** The statement that runs next or is waiting, or the owner of a frame at its end; {@code null} when done. */
    Stmt currentStatement() {
        Frame top = frames.peek();
        if (top == null) {
            return null;
        }
        if (top.index < top.body.size()) {
            return top.body.get(top.index);
        }
        return top.owner;
    }

    void block(Blocker reason) {
        blocker = reason;
        loopPassesSinceYield = 0;
        guardStrikes = 0;
    }
}
