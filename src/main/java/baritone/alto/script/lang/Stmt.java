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

package baritone.alto.script.lang;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A statement (stack block): {@code {"block": "<type>", ...fields}}. Action blocks share one record
 * ({@link Action}) described by {@link ActionType}; control blocks have their own records.
 */
public sealed interface Stmt {

    /** Location in the script, e.g. {@code events[0].body[2]}. */
    String path();

    /** The JSON {@code block} name. */
    String block();

    /** Names of the control blocks (the action block names are {@link ActionType}). */
    List<String> CONTROL_BLOCKS = List.of("repeat", "repeat_until", "forever", "if", "wait_until", "break", "call",
            "set_var", "change_var");

    /**
     * An action block. {@code args} holds the present fields in {@link ActionType#fields()} order.
     */
    record Action(String path, ActionType type, Map<String, ActionArg> args) implements Stmt {
        public Action {
            args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
        }

        @Override
        public String block() {
            return type.jsonName();
        }

        /** The expression of an expression field, or {@code null} when absent. */
        public Expr expr(String field) {
            return args.get(field) instanceof ActionArg.ExprArg e ? e.expr() : null;
        }
    }

    /** Runs the body {@code times} times (0 skips it). */
    record Repeat(String path, Expr times, List<Stmt> body) implements Stmt {
        public Repeat {
            body = List.copyOf(body);
        }

        @Override
        public String block() {
            return "repeat";
        }
    }

    /** Checks the condition before every pass and stops when it is true (it may run zero times). */
    record RepeatUntil(String path, Expr condition, List<Stmt> body) implements Stmt {
        public RepeatUntil {
            body = List.copyOf(body);
        }

        @Override
        public String block() {
            return "repeat_until";
        }
    }

    /** Runs the body until a {@code break}, a {@code stop} or the run ends. Needs safety stops at runtime. */
    record Forever(String path, List<Stmt> body) implements Stmt {
        public Forever {
            body = List.copyOf(body);
        }

        @Override
        public String block() {
            return "forever";
        }
    }

    /** {@code otherwise} is the JSON {@code else} list (empty when absent). */
    record If(String path, Expr condition, List<Stmt> then, List<Stmt> otherwise) implements Stmt {
        public If {
            then = List.copyOf(then);
            otherwise = List.copyOf(otherwise);
        }

        @Override
        public String block() {
            return "if";
        }
    }

    /** Waits until the condition is true; fails the run after {@code timeoutSeconds} (may be {@code null}). */
    record WaitUntil(String path, Expr condition, Expr timeoutSeconds) implements Stmt {
        @Override
        public String block() {
            return "wait_until";
        }
    }

    /** Leaves the innermost loop. */
    record Break(String path) implements Stmt {
        @Override
        public String block() {
            return "break";
        }
    }

    /** Calls a procedure; {@code args} maps parameter names to values (sorted by name). */
    record Call(String path, String procedure, Map<String, Expr> args) implements Stmt {
        public Call {
            args = Collections.unmodifiableMap(new TreeMap<>(args));
        }

        @Override
        public String block() {
            return "call";
        }
    }

    record SetVar(String path, String name, Expr value) implements Stmt {
        @Override
        public String block() {
            return "set_var";
        }
    }

    /** Adds {@code delta} to a number variable. */
    record ChangeVar(String path, String name, Expr delta) implements Stmt {
        @Override
        public String block() {
            return "change_var";
        }
    }
}
