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

import java.util.List;

/**
 * A parsed Alto script (the root of the immutable AST). Build it with {@code ScriptCodec.parse}; check it with
 * {@code ScriptValidator.validate} before running.
 *
 * @param version     language version (always {@link #CURRENT_VERSION} after parsing)
 * @param name        display name
 * @param description free text, may be empty
 * @param variables   global variables in declaration order
 * @param procedures  "My Blocks"
 * @param handlers    event handlers in file order
 */
public record Script(int version, String name, String description, List<VarDecl> variables,
                     List<Procedure> procedures, List<EventHandler> handlers) {

    /** Value of the top-level {@code format} field. */
    public static final String FORMAT = "alto-script";
    public static final int CURRENT_VERSION = 1;

    public Script {
        variables = List.copyOf(variables);
        procedures = List.copyOf(procedures);
        handlers = List.copyOf(handlers);
    }

    /** The first procedure called {@code name}, or {@code null}. */
    public Procedure procedure(String name) {
        for (Procedure p : procedures) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    /** The first variable called {@code name}, or {@code null}. */
    public VarDecl variable(String name) {
        for (VarDecl v : variables) {
            if (v.name().equals(name)) {
                return v;
            }
        }
        return null;
    }

    /** A global variable with its initial value (which also fixes its type). */
    public record VarDecl(String path, String name, Value initial) {
        public ValueType type() {
            return initial.type();
        }
    }

    public record ParamDecl(String path, String name, ParamType type) {
    }

    /** A custom block ("My Block"); calls must pass every parameter. Recursion is not allowed. */
    public record Procedure(String path, String name, List<ParamDecl> params, List<Stmt> body) {
        public Procedure {
            params = List.copyOf(params);
            body = List.copyOf(body);
        }

        /** The parameter called {@code name}, or {@code null}. */
        public ParamDecl param(String name) {
            for (ParamDecl p : params) {
                if (p.name().equals(name)) {
                    return p;
                }
            }
            return null;
        }
    }

    /** An event handler: when {@code event} happens, run {@code body}. */
    public record EventHandler(String path, Event event, List<Stmt> body) {
        public EventHandler {
            body = List.copyOf(body);
        }
    }
}
