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

import java.util.List;
import java.util.Map;

/**
 * Internal: one statement list being executed (a handler body, an if branch, a loop body or a procedure body) and
 * the position in it. {@link #index} points at the statement that runs next (or is waiting).
 */
final class Frame {

    enum Kind {
        HANDLER,
        BLOCK,
        REPEAT,
        REPEAT_UNTIL,
        FOREVER,
        CALL
    }

    final Kind kind;
    final List<Stmt> body;
    /** The if/loop/call statement that opened this frame; {@code null} for {@link Kind#HANDLER}. */
    final Stmt owner;
    /** {@link Kind#CALL} only. */
    final Script.Procedure procedure;
    /** {@link Kind#CALL} only: evaluated arguments. */
    final Map<String, Value> params;
    int index;
    /** {@link Kind#REPEAT} only: passes left including the current one. */
    long remaining;

    private Frame(Kind kind, List<Stmt> body, Stmt owner, Script.Procedure procedure, Map<String, Value> params) {
        this.kind = kind;
        this.body = body;
        this.owner = owner;
        this.procedure = procedure;
        this.params = params;
    }

    static Frame handler(List<Stmt> body) {
        return new Frame(Kind.HANDLER, body, null, null, null);
    }

    static Frame block(Stmt owner, List<Stmt> body) {
        return new Frame(Kind.BLOCK, body, owner, null, null);
    }

    static Frame loop(Kind kind, Stmt owner, List<Stmt> body, long remaining) {
        Frame f = new Frame(kind, body, owner, null, null);
        f.remaining = remaining;
        return f;
    }

    static Frame call(Stmt.Call owner, Script.Procedure procedure, Map<String, Value> params) {
        return new Frame(Kind.CALL, procedure.body(), owner, procedure, Map.copyOf(params));
    }

    boolean isLoop() {
        return kind == Kind.REPEAT || kind == Kind.REPEAT_UNTIL || kind == Kind.FOREVER;
    }
}
