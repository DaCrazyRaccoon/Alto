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

package baritone.alto.script.check;

import baritone.alto.script.lang.ActionArg;
import baritone.alto.script.lang.ActionType;
import baritone.alto.script.lang.Diagnostic;
import baritone.alto.script.lang.DiagnosticCode;
import baritone.alto.script.lang.Event;
import baritone.alto.script.lang.Expr;
import baritone.alto.script.lang.FieldSpec;
import baritone.alto.script.lang.Fields;
import baritone.alto.script.lang.Ids;
import baritone.alto.script.lang.Limits;
import baritone.alto.script.lang.Script;
import baritone.alto.script.lang.Script.EventHandler;
import baritone.alto.script.lang.Script.ParamDecl;
import baritone.alto.script.lang.Script.Procedure;
import baritone.alto.script.lang.Script.VarDecl;
import baritone.alto.script.lang.Stmt;
import baritone.alto.script.lang.Suggestions;
import baritone.alto.script.lang.Target;
import baritone.alto.script.lang.Value;
import baritone.alto.script.lang.ValueType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks the meaning of a parsed script: names, references, static types, literal ranges, recursion, {@code break}
 * placement, the loop guard and warnings. Structural problems are the codec's job ({@code ScriptCodec.parse}).
 * <p>
 * Every expression has a static type (variables keep the type of their initial value, parameters declare theirs),
 * so type errors are found here, before the script runs. A script is runnable when this returns no
 * {@link baritone.alto.script.lang.Severity#ERROR}.
 */
public final class ScriptValidator {

    private final Script script;
    private final List<Diagnostic> out = new ArrayList<>();
    private final Map<String, VarDecl> variables = new LinkedHashMap<>();
    private final Map<String, Procedure> procedures = new LinkedHashMap<>();
    private final Set<String> usedVariables = new HashSet<>();
    private final Set<String> calledProcedures = new HashSet<>();
    /** caller -> (callee -> path of the first call). */
    private final Map<String, Map<String, String>> callGraph = new LinkedHashMap<>();
    private final Map<String, Boolean> procedureYields = new HashMap<>();

    /** Where a block is: inside which procedure (or a handler when {@code null}) and how many loops deep. */
    private record Scope(Procedure procedure, int loopDepth) {
        Scope inLoop() {
            return new Scope(procedure, loopDepth + 1);
        }
    }

    private ScriptValidator(Script script) {
        this.script = script;
    }

    /** All errors and warnings, deterministic order (declarations, procedures, events, then whole-script checks). */
    public static List<Diagnostic> validate(Script script) {
        return new ScriptValidator(script).run();
    }

    public static boolean hasErrors(List<Diagnostic> diagnostics) {
        for (Diagnostic d : diagnostics) {
            if (d.isError()) {
                return true;
            }
        }
        return false;
    }

    private List<Diagnostic> run() {
        header();
        declarations();
        for (Procedure p : script.procedures()) {
            body(p.body(), new Scope(p, 0));
        }
        events();
        for (EventHandler h : script.handlers()) {
            body(h.body(), new Scope(null, 0));
        }
        recursion();
        unused();
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------------------------------ declarations

    private void header() {
        problem(Fields.SCRIPT_NAME.checkText(script.name()), "name");
        problem(Fields.SCRIPT_DESCRIPTION.checkText(script.description()), "description");
    }

    private void declarations() {
        for (VarDecl v : script.variables()) {
            String path = join(v.path(), "name");
            name(v.name(), path, "variable");
            if (variables.putIfAbsent(v.name(), v) != null) {
                error(DiagnosticCode.DUPLICATE_NAME, path, "there is already a variable called '" + v.name() + "'");
            }
            if (v.initial() instanceof Value.Str s && s.value().length() > Limits.MAX_TEXT_LENGTH) {
                error(DiagnosticCode.OUT_OF_RANGE, join(v.path(), "initial"),
                        "initial must be at most " + Limits.MAX_TEXT_LENGTH + " characters");
            }
        }
        for (Procedure p : script.procedures()) {
            String path = join(p.path(), "name");
            name(p.name(), path, "procedure");
            if (procedures.putIfAbsent(p.name(), p) != null) {
                error(DiagnosticCode.DUPLICATE_NAME, path, "there is already a procedure called '" + p.name() + "'");
            }
            Set<String> params = new HashSet<>();
            for (ParamDecl param : p.params()) {
                String paramPath = join(param.path(), "name");
                name(param.name(), paramPath, "parameter");
                if (!params.add(param.name())) {
                    error(DiagnosticCode.DUPLICATE_NAME, paramPath,
                            "procedure '" + p.name() + "' already has a parameter called '" + param.name() + "'");
                }
            }
        }
    }

    private void name(String name, String path, String what) {
        if (!Ids.isValidName(name)) {
            error(DiagnosticCode.INVALID_NAME, path, what + " names must start with a letter or _ and contain only"
                    + " letters, digits and _ (at most " + Limits.MAX_NAME_LENGTH + " characters), got '" + name + "'");
        }
    }

    private void events() {
        if (script.handlers().isEmpty()) {
            error(DiagnosticCode.NO_EVENTS, "events",
                    "the script has no event handlers, so nothing would ever run; add an on_start handler");
        }
        Map<String, String> seen = new HashMap<>();
        for (EventHandler h : script.handlers()) {
            String path = h.path();
            switch (h.event()) {
                case Event.OnChat c -> {
                    String triggerPath = join(path, "trigger");
                    problem(Fields.EVENT_TRIGGER.checkText(c.trigger()), triggerPath);
                    if (c.trigger().trim().startsWith("/")) {
                        error(DiagnosticCode.INVALID_TEXT, triggerPath,
                                "chat triggers cannot start with '/': that text is a server command, not chat");
                    }
                }
                case Event.OnHealthBelow b -> problem(Fields.EVENT_HEALTH.checkNumber(b.health()), join(path, "health"));
                case Event.OnItemBelow b -> {
                    problem(Fields.EVENT_ITEM.checkText(b.item()), join(path, "item"));
                    problem(Fields.EVENT_COUNT.checkNumber(b.count()), join(path, "count"));
                }
                case Event.Every e -> problem(Fields.EVENT_SECONDS.checkNumber(e.seconds()), join(path, "seconds"));
                case Event.OnStart ignored -> {
                }
                case Event.OnInventoryFull ignored -> {
                }
            }
            String first = seen.putIfAbsent(h.event().duplicateKey(), path);
            if (first != null) {
                error(DiagnosticCode.DUPLICATE_EVENT, path,
                        "another handler (" + first + ") already listens to " + h.event().label());
            }
        }
    }

    // ------------------------------------------------------------------------------------------ statements

    private void body(List<Stmt> body, Scope scope) {
        String endedBecause = null;
        boolean reported = false;
        for (Stmt s : body) {
            if (endedBecause != null && !reported) {
                warning(DiagnosticCode.UNREACHABLE_CODE, s.path(),
                        "this block never runs: the block before it " + endedBecause);
                reported = true;
            }
            statement(s, scope);
            if (endedBecause == null) {
                endedBecause = endsFlow(s);
            }
        }
    }

    /** Why nothing after {@code s} in the same list can run, or {@code null}. */
    private static String endsFlow(Stmt s) {
        if (s instanceof Stmt.Break) {
            return "always leaves the loop";
        }
        if (s instanceof Stmt.Action a && a.type() == ActionType.STOP) {
            return "always stops the script";
        }
        if (s instanceof Stmt.Forever f && !breaksOut(f.body())) {
            return "loops forever (it has no break)";
        }
        return null;
    }

    /** Whether a {@code break} in {@code body} (outside nested loops) leaves the enclosing loop. */
    private static boolean breaksOut(List<Stmt> body) {
        for (Stmt s : body) {
            if (s instanceof Stmt.Break) {
                return true;
            }
            if (s instanceof Stmt.If i && (breaksOut(i.then()) || breaksOut(i.otherwise()))) {
                return true;
            }
        }
        return false;
    }

    private void statement(Stmt s, Scope scope) {
        switch (s) {
            case Stmt.Action a -> action(a, scope);
            case Stmt.Repeat r -> {
                checkField(Fields.REPEAT_TIMES, r.times(), scope);
                largeCount(r.times(), "repeat times");
                loop(r.path(), r.body(), scope);
            }
            case Stmt.RepeatUntil r -> {
                condition(r.condition(), scope);
                loop(r.path(), r.body(), scope);
            }
            case Stmt.Forever f -> {
                warning(DiagnosticCode.FOREVER_LOOP, f.path(), "forever runs until the script is stopped; Alto only"
                        + " starts it when safety stops are enabled in its settings");
                loop(f.path(), f.body(), scope);
            }
            case Stmt.If i -> {
                condition(i.condition(), scope);
                body(i.then(), scope);
                body(i.otherwise(), scope);
            }
            case Stmt.WaitUntil w -> {
                condition(w.condition(), scope);
                if (w.timeoutSeconds() != null) {
                    checkField(Fields.TIMEOUT_SECONDS, w.timeoutSeconds(), scope);
                }
            }
            case Stmt.Break b -> {
                if (scope.loopDepth() == 0) {
                    error(DiagnosticCode.BREAK_OUTSIDE_LOOP, b.path(), scope.procedure() == null
                            ? "break must be inside a repeat, repeat_until or forever block"
                            : "break must be inside a loop of the same procedure (it cannot leave the caller's loop)");
                }
            }
            case Stmt.Call c -> call(c, scope);
            case Stmt.SetVar sv -> {
                VarDecl v = variable(sv.name(), join(sv.path(), "name"));
                ValueType type = typeOf(sv.value(), scope);
                if (v != null && type != null && type != v.type()) {
                    error(DiagnosticCode.TYPE_MISMATCH, sv.value().path(), "variable '" + v.name() + "' holds "
                            + v.type().withArticle() + ", but this value is " + type.withArticle());
                }
                if (sv.value() instanceof Expr.Literal l && l.value() instanceof Value.Str str
                        && str.value().length() > Limits.MAX_TEXT_LENGTH) {
                    error(DiagnosticCode.OUT_OF_RANGE, l.path(), "text must be at most " + Limits.MAX_TEXT_LENGTH + " characters");
                }
            }
            case Stmt.ChangeVar cv -> {
                VarDecl v = variable(cv.name(), join(cv.path(), "name"));
                if (v != null && v.type() != ValueType.NUMBER) {
                    error(DiagnosticCode.TYPE_MISMATCH, join(cv.path(), "name"), "change_var only works on number"
                            + " variables, but '" + v.name() + "' holds " + v.type().withArticle());
                }
                expect(cv.delta(), ValueType.NUMBER, "delta", scope);
            }
        }
    }

    private void loop(String path, List<Stmt> body, Scope scope) {
        if (!yields(body)) {
            error(DiagnosticCode.LOOP_WITHOUT_WAIT, path, "loop without a wait or task step: put a wait block or an"
                    + " action such as craft or goto inside it, otherwise it would spin without giving the game time");
        }
        body(body, scope.inLoop());
    }

    private void condition(Expr e, Scope scope) {
        expect(e, ValueType.BOOLEAN, "condition", scope);
    }

    private void action(Stmt.Action a, Scope scope) {
        for (FieldSpec f : a.type().fields()) {
            ActionArg arg = a.args().get(f.name());
            if (arg == null) {
                continue;
            }
            String path = join(a.path(), f.name());
            switch (arg) {
                case ActionArg.ExprArg e -> checkField(f, e.expr(), scope);
                case ActionArg.ListArg l -> {
                    if (l.items().isEmpty()) {
                        error(DiagnosticCode.OUT_OF_RANGE, path,
                                f.name() + " must list at least one item; leave the field out instead");
                    }
                    for (Expr item : l.items()) {
                        checkField(f, item, scope);
                    }
                }
                case ActionArg.TextArg t -> problem(f.checkText(t.text()), path);
                case ActionArg.TargetArg t -> target(t.target(), path, scope);
                case ActionArg.ChoiceArg ignored -> {
                }
            }
        }
        Expr count = a.expr("count");
        if (count != null) {
            largeCount(count, a.type().jsonName() + " count");
        }
        switch (a.type()) {
            case COMMAND -> command(a);
            case SEND_CHAT -> sendChat(a);
            case FILL -> fillVolume(a);
            default -> {
            }
        }
    }

    private void command(Stmt.Action a) {
        if (!(a.args().get("command") instanceof ActionArg.TextArg t)) {
            return;
        }
        String command = ActionType.normalizeCommand(t.text());
        if (command.startsWith("/")) {
            error(DiagnosticCode.INVALID_TEXT, join(a.path(), "command"), "command takes a Baritone command such as"
                    + " 'mine diamond_ore'; text starting with '/' is a server command (see send_chat)");
        }
        warning(DiagnosticCode.RUNS_COMMAND, a.path(), "runs the Baritone command '" + command + "'; it only runs"
                + " when it is on the allowed commands list in Alto's settings");
    }

    private void sendChat(Stmt.Action a) {
        boolean slash = a.expr("text") instanceof Expr.Literal l && l.value() instanceof Value.Str s
                && s.value().trim().startsWith("/");
        warning(DiagnosticCode.SENDS_CHAT, a.path(), "sends a real chat message as you"
                + (slash ? " (this text starts with '/', so the server runs it as a command)" : "")
                + "; Alto refuses it unless scripts are allowed to chat in its settings");
    }

    private void fillVolume(Stmt.Action a) {
        long[] c = new long[6];
        String[] names = {"x1", "y1", "z1", "x2", "y2", "z2"};
        for (int i = 0; i < 6; i++) {
            if (!(a.expr(names[i]) instanceof Expr.Literal l) || !(l.value() instanceof Value.Num n)) {
                return;
            }
            c[i] = (long) n.value();
        }
        long volume = (Math.abs(c[3] - c[0]) + 1) * (Math.abs(c[4] - c[1]) + 1) * (Math.abs(c[5] - c[2]) + 1);
        if (volume > Limits.LARGE_FILL_VOLUME) {
            warning(DiagnosticCode.LARGE_COUNT, a.path(), "fill covers " + volume + " blocks; that takes a very long time");
        }
    }

    private void largeCount(Expr e, String what) {
        if (e instanceof Expr.Literal l && l.value() instanceof Value.Num n && n.value() >= Limits.LARGE_COUNT
                && n.value() <= Limits.MAX_COUNT) {
            warning(DiagnosticCode.LARGE_COUNT, e.path(), what + " is " + n.display() + "; that takes a very long time");
        }
    }

    private void target(Target t, String path, Scope scope) {
        switch (t) {
            case Target.Coords c -> {
                checkField(Fields.BLOCK_X, c.x(), scope);
                checkField(Fields.BLOCK_Y, c.y(), scope);
                checkField(Fields.BLOCK_Z, c.z(), scope);
                if (c.dimension() != null) {
                    problem(Fields.DIMENSION.checkText(c.dimension()), join(path, "dimension"));
                }
            }
            case Target.Home h -> {
                if (h.name() != null) {
                    problem(Fields.PLACE_NAME.checkText(h.name()), join(path, "name"));
                }
            }
            case Target.StorageZone z -> problem(Fields.PLACE_NAME.checkText(z.name()), join(path, "name"));
            case Target.Portal ignored -> {
            }
        }
    }

    private void call(Stmt.Call c, Scope scope) {
        Procedure p = procedures.get(c.procedure());
        String argsPath = join(c.path(), "args");
        if (p == null) {
            error(DiagnosticCode.UNKNOWN_PROCEDURE, join(c.path(), "procedure"), "there is no procedure called '"
                    + c.procedure() + "'" + Suggestions.hint(c.procedure(), procedures.keySet()));
            for (Expr arg : c.args().values()) {
                typeOf(arg, scope);
            }
            return;
        }
        calledProcedures.add(p.name());
        if (scope.procedure() != null) {
            callGraph.computeIfAbsent(scope.procedure().name(), k -> new LinkedHashMap<>()).putIfAbsent(p.name(), c.path());
        }
        for (ParamDecl param : p.params()) {
            Expr arg = c.args().get(param.name());
            if (arg == null) {
                error(DiagnosticCode.MISSING_ARGUMENT, argsPath, "procedure '" + p.name() + "' needs a value for"
                        + " parameter '" + param.name() + "' (" + param.type().jsonName() + ")");
                continue;
            }
            ValueType type = typeOf(arg, scope);
            if (type != null && type != param.type().valueType()) {
                error(DiagnosticCode.TYPE_MISMATCH, arg.path(), "parameter '" + param.name() + "' is "
                        + param.type().jsonName() + " (" + param.type().valueType().withArticle() + "), but this is "
                        + type.withArticle());
            } else if (param.type().isId() && arg instanceof Expr.Literal l && l.value() instanceof Value.Str s
                    && !Ids.isValidId(s.value())) {
                error(DiagnosticCode.INVALID_ID, arg.path(), "parameter '" + param.name() + "' needs an item or block"
                        + " id like minecraft:oak_planks, got '" + s.value() + "'");
            }
        }
        for (Map.Entry<String, Expr> arg : c.args().entrySet()) {
            if (p.param(arg.getKey()) == null) {
                List<String> names = new ArrayList<>();
                p.params().forEach(param -> names.add(param.name()));
                error(DiagnosticCode.UNKNOWN_ARGUMENT, join(argsPath, arg.getKey()), "procedure '" + p.name()
                        + "' has no parameter '" + arg.getKey() + "'" + Suggestions.hint(arg.getKey(), names));
                typeOf(arg.getValue(), scope);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ expressions

    /** Type-checks {@code e} against a field and checks literal values against its range/format. */
    private void checkField(FieldSpec f, Expr e, Scope scope) {
        ValueType want = f.valueType();
        ValueType got = typeOf(e, scope);
        if (want != null && got != null && got != want) {
            error(DiagnosticCode.TYPE_MISMATCH, e.path(),
                    f.name() + " must be " + want.withArticle() + ", but this is " + got.withArticle());
            return;
        }
        if (e instanceof Expr.Literal l) {
            if (l.value() instanceof Value.Num n && want == ValueType.NUMBER) {
                problem(f.checkNumber(n.value()), l.path());
            } else if (l.value() instanceof Value.Str s) {
                problem(f.checkText(s.value()), l.path());
            }
        }
    }

    private void expect(Expr e, ValueType want, String what, Scope scope) {
        ValueType got = typeOf(e, scope);
        if (got != null && got != want) {
            error(DiagnosticCode.TYPE_MISMATCH, e.path(),
                    what + " must be " + want.withArticle() + ", but this is " + got.withArticle());
        }
    }

    /** Static type of {@code e}; reports problems inside it. {@code null} when unknown because of an error. */
    private ValueType typeOf(Expr e, Scope scope) {
        return switch (e) {
            case Expr.Literal l -> l.value().type();
            case Expr.Var v -> {
                VarDecl d = variable(v.name(), v.path());
                yield d == null ? null : d.type();
            }
            case Expr.Param p -> param(p, scope);
            case Expr.ItemCount ic -> {
                checkField(Fields.ITEM, ic.item(), scope);
                yield ValueType.NUMBER;
            }
            case Expr.Sensor s -> s.sensor().valueType();
            case Expr.Position ignored -> ValueType.NUMBER;
            case Expr.BlockAt b -> {
                checkField(Fields.BLOCK_X, b.x(), scope);
                checkField(Fields.BLOCK_Y, b.y(), scope);
                checkField(Fields.BLOCK_Z, b.z(), scope);
                yield ValueType.STRING;
            }
            case Expr.RandomInt r -> {
                checkField(Fields.RANDOM_MIN, r.min(), scope);
                checkField(Fields.RANDOM_MAX, r.max(), scope);
                yield ValueType.NUMBER;
            }
            case Expr.Binary b -> {
                ValueType ta = typeOf(b.a(), scope);
                ValueType tb = typeOf(b.b(), scope);
                ValueType want = b.op().operandType();
                if (want != null) {
                    operand(b.a(), ta, want, b.op().jsonName());
                    operand(b.b(), tb, want, b.op().jsonName());
                } else if (ta != null && tb != null && ta != tb) {
                    error(DiagnosticCode.TYPE_MISMATCH, b.path(), b.op().jsonName() + " compares two values of the"
                            + " same type, but got " + ta.withArticle() + " and " + tb.withArticle());
                }
                yield b.op().resultType();
            }
            case Expr.Unary u -> {
                operand(u.a(), typeOf(u.a(), scope), u.op().type(), u.op().jsonName());
                yield u.op().type();
            }
            case Expr.Join j -> {
                for (Expr part : j.parts()) {
                    typeOf(part, scope);
                }
                yield ValueType.STRING;
            }
        };
    }

    private void operand(Expr e, ValueType got, ValueType want, String op) {
        if (got != null && got != want) {
            error(DiagnosticCode.TYPE_MISMATCH, e.path(),
                    op + " needs " + want.withArticle() + " here, but this is " + got.withArticle());
        }
    }

    private ValueType param(Expr.Param p, Scope scope) {
        if (scope.procedure() == null) {
            error(DiagnosticCode.PARAM_OUTSIDE_PROCEDURE, p.path(),
                    "param '" + p.name() + "' can only be used inside a procedure");
            return null;
        }
        ParamDecl d = scope.procedure().param(p.name());
        if (d == null) {
            List<String> names = new ArrayList<>();
            scope.procedure().params().forEach(param -> names.add(param.name()));
            error(DiagnosticCode.UNKNOWN_PARAM, p.path(), "procedure '" + scope.procedure().name()
                    + "' has no parameter '" + p.name() + "'" + Suggestions.hint(p.name(), names));
            return null;
        }
        return d.type().valueType();
    }

    private VarDecl variable(String name, String path) {
        VarDecl v = variables.get(name);
        if (v == null) {
            error(DiagnosticCode.UNKNOWN_VARIABLE, path, "there is no variable called '" + name + "'"
                    + Suggestions.hint(name, variables.keySet()));
            return null;
        }
        usedVariables.add(name);
        return v;
    }

    // ------------------------------------------------------------------------------------------ whole script

    /** Loop guard: whether running {@code body} always reaches a block that gives the game time (statically). */
    private boolean yields(List<Stmt> body) {
        for (Stmt s : body) {
            if (yields(s)) {
                return true;
            }
        }
        return false;
    }

    private boolean yields(Stmt s) {
        return switch (s) {
            case Stmt.Action a -> a.type().yields();
            case Stmt.WaitUntil ignored -> true;
            case Stmt.Repeat r -> yields(r.body());
            case Stmt.RepeatUntil r -> yields(r.body());
            case Stmt.Forever f -> yields(f.body());
            case Stmt.If i -> yields(i.then()) || yields(i.otherwise());
            case Stmt.Call c -> procedureYields(c.procedure());
            case Stmt.Break ignored -> false;
            case Stmt.SetVar ignored -> false;
            case Stmt.ChangeVar ignored -> false;
        };
    }

    private boolean procedureYields(String name) {
        Boolean known = procedureYields.get(name);
        if (known != null) {
            return known;
        }
        Procedure p = procedures.get(name);
        if (p == null) {
            return false;
        }
        procedureYields.put(name, false); // cycle guard; recursion is reported separately
        boolean result = yields(p.body());
        procedureYields.put(name, result);
        return result;
    }

    private void recursion() {
        Map<String, Integer> state = new HashMap<>();
        for (String name : procedures.keySet()) {
            if (!state.containsKey(name)) {
                visit(name, state, new ArrayList<>());
            }
        }
    }

    /** Depth-first search; a call to a procedure that is still on the stack closes a cycle. */
    private void visit(String name, Map<String, Integer> state, List<String> stack) {
        state.put(name, 1);
        stack.add(name);
        for (Map.Entry<String, String> call : callGraph.getOrDefault(name, Map.of()).entrySet()) {
            String callee = call.getKey();
            Integer calleeState = state.get(callee);
            if (calleeState == null) {
                visit(callee, state, stack);
            } else if (calleeState == 1) {
                List<String> cycle = new ArrayList<>(stack.subList(stack.indexOf(callee), stack.size()));
                cycle.add(callee);
                error(DiagnosticCode.RECURSION, call.getValue(), "procedure '" + callee + "' would call itself ("
                        + String.join(" -> ", cycle) + "); recursion is not allowed, use a loop instead");
            }
        }
        stack.remove(stack.size() - 1);
        state.put(name, 2);
    }

    private void unused() {
        for (Procedure p : script.procedures()) {
            if (!calledProcedures.contains(p.name())) {
                warning(DiagnosticCode.UNUSED_PROCEDURE, p.path(), "procedure '" + p.name() + "' is never called");
            }
        }
        for (VarDecl v : script.variables()) {
            if (!usedVariables.contains(v.name())) {
                warning(DiagnosticCode.UNUSED_VARIABLE, v.path(), "variable '" + v.name() + "' is never used");
            }
        }
    }

    // ------------------------------------------------------------------------------------------ output

    private void problem(FieldSpec.Problem problem, String path) {
        if (problem != null) {
            error(problem.code(), path, problem.message());
        }
    }

    private void error(DiagnosticCode code, String path, String message) {
        out.add(Diagnostic.of(code, path, message));
    }

    private void warning(DiagnosticCode code, String path, String message) {
        out.add(Diagnostic.of(code, path, message));
    }

    private static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }
}
