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

package baritone.alto.script.codec;

import baritone.alto.script.lang.ActionArg;
import baritone.alto.script.lang.ActionType;
import baritone.alto.script.lang.Axis;
import baritone.alto.script.lang.BinaryOp;
import baritone.alto.script.lang.Diagnostic;
import baritone.alto.script.lang.DiagnosticCode;
import baritone.alto.script.lang.Event;
import baritone.alto.script.lang.EventType;
import baritone.alto.script.lang.Expr;
import baritone.alto.script.lang.FieldSpec;
import baritone.alto.script.lang.JsonNamed;
import baritone.alto.script.lang.Limits;
import baritone.alto.script.lang.ParamType;
import baritone.alto.script.lang.Script;
import baritone.alto.script.lang.Script.EventHandler;
import baritone.alto.script.lang.Script.ParamDecl;
import baritone.alto.script.lang.Script.Procedure;
import baritone.alto.script.lang.Script.VarDecl;
import baritone.alto.script.lang.SensorType;
import baritone.alto.script.lang.Stmt;
import baritone.alto.script.lang.Suggestions;
import baritone.alto.script.lang.Target;
import baritone.alto.script.lang.UnaryOp;
import baritone.alto.script.lang.Value;
import baritone.alto.script.lang.Where;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Builds the AST from a JSON tree. Structural problems (unknown blocks, missing or unknown fields, wrong JSON types,
 * invalid choices) are collected as diagnostics; reading continues so one pass reports as many as possible. The
 * result is only usable when no error was reported.
 */
final class AstReader {

    private static final List<String> TOP_FIELDS =
            List.of("format", "version", "name", "description", "variables", "procedures", "events");

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private int nodes;
    private boolean tooLarge;

    List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    boolean hasErrors() {
        return !diagnostics.isEmpty();
    }

    Script read(JsonElement root) {
        JsonObject o = object(root, "", "the script");
        if (o == null) {
            return null;
        }
        String format = string(o, "", "format", true);
        if (format != null && !format.equals(Script.FORMAT)) {
            error(DiagnosticCode.UNSUPPORTED_FORMAT, "format",
                    "this is not an Alto script: format must be \"" + Script.FORMAT + "\", got \"" + format + "\"");
            return null;
        }
        Double version = number(o, "", "version", true);
        if (version != null && version != Script.CURRENT_VERSION) {
            error(DiagnosticCode.UNSUPPORTED_VERSION, "version", "this Alto understands script version "
                    + Script.CURRENT_VERSION + ", but the script has version " + Value.formatNumber(version)
                    + "; update Alto or save the script again from a matching editor");
            return null;
        }
        onlyFields(o, "", TOP_FIELDS, "the script");
        String name = string(o, "", "name", true);
        String description = string(o, "", "description", false);
        List<VarDecl> variables = list(o, "", "variables", false, this::variable);
        List<Procedure> procedures = list(o, "", "procedures", false, this::procedure);
        List<EventHandler> handlers = list(o, "", "events", true, this::handler);
        if (hasErrors()) {
            return null;
        }
        return new Script(Script.CURRENT_VERSION, name, description == null ? "" : description, variables,
                procedures, handlers);
    }

    // ------------------------------------------------------------------------------------------ declarations

    private VarDecl variable(JsonElement e, String path) {
        JsonObject o = object(e, path, "a variable");
        if (o == null) {
            return null;
        }
        onlyFields(o, path, List.of("name", "initial"), "a variable");
        String name = string(o, path, "name", true);
        JsonElement init = field(o, path, "initial", true);
        Value initial = init == null ? null : literal(init);
        if (init != null && initial == null) {
            error(DiagnosticCode.WRONG_JSON_TYPE, join(path, "initial"),
                    "initial must be a number, a string or true/false, got " + describe(init));
        }
        return name == null || initial == null ? null : new VarDecl(path, name, initial);
    }

    private Procedure procedure(JsonElement e, String path) {
        JsonObject o = object(e, path, "a procedure");
        if (o == null) {
            return null;
        }
        onlyFields(o, path, List.of("name", "params", "body"), "a procedure");
        String name = string(o, path, "name", true);
        List<ParamDecl> params = list(o, path, "params", false, this::param);
        List<Stmt> body = body(o, path, "body", true);
        return name == null ? null : new Procedure(path, name, params, body);
    }

    private ParamDecl param(JsonElement e, String path) {
        JsonObject o = object(e, path, "a parameter");
        if (o == null) {
            return null;
        }
        onlyFields(o, path, List.of("name", "type"), "a parameter");
        String name = string(o, path, "name", true);
        ParamType type = choice(o, path, "type", ParamType.values(), null);
        return name == null || type == null ? null : new ParamDecl(path, name, type);
    }

    private EventHandler handler(JsonElement e, String path) {
        JsonObject o = object(e, path, "an event handler");
        if (o == null) {
            return null;
        }
        String kind = string(o, path, "event", true);
        if (kind == null) {
            return null;
        }
        EventType type = JsonNamed.find(EventType.values(), kind);
        if (type == null) {
            error(DiagnosticCode.UNKNOWN_EVENT, path, "unknown event '" + kind + "'"
                    + Suggestions.hint(kind, JsonNamed.names(EventType.values())));
            return null;
        }
        String what = "event " + kind;
        Event event = switch (type) {
            case ON_START -> {
                onlyFields(o, path, List.of("event", "body"), what);
                yield new Event.OnStart();
            }
            case ON_CHAT -> {
                onlyFields(o, path, List.of("event", "trigger", "body"), what);
                String trigger = string(o, path, "trigger", true);
                yield trigger == null ? null : new Event.OnChat(trigger);
            }
            case ON_INVENTORY_FULL -> {
                onlyFields(o, path, List.of("event", "body"), what);
                yield new Event.OnInventoryFull();
            }
            case ON_HEALTH_BELOW -> {
                onlyFields(o, path, List.of("event", "health", "body"), what);
                Double health = number(o, path, "health", true);
                yield health == null ? null : new Event.OnHealthBelow(health);
            }
            case ON_ITEM_BELOW -> {
                onlyFields(o, path, List.of("event", "item", "count", "where", "body"), what);
                String item = string(o, path, "item", true);
                Double count = number(o, path, "count", true);
                Where where = choice(o, path, "where", Where.values(), Where.INVENTORY);
                yield item == null || count == null ? null : new Event.OnItemBelow(item, count, where);
            }
            case EVERY -> {
                onlyFields(o, path, List.of("event", "seconds", "body"), what);
                Double seconds = number(o, path, "seconds", true);
                yield seconds == null ? null : new Event.Every(seconds);
            }
        };
        List<Stmt> body = body(o, path, "body", true);
        return event == null ? null : new EventHandler(path, event, body);
    }

    // ------------------------------------------------------------------------------------------ statements

    private List<Stmt> body(JsonObject o, String path, String key, boolean required) {
        return list(o, path, key, required, this::statement);
    }

    private Stmt statement(JsonElement e, String path) {
        JsonObject o = object(e, path, "a block");
        if (o == null || !countNode(path)) {
            return null;
        }
        String block = string(o, path, "block", true);
        if (block == null) {
            return null;
        }
        ActionType action = ActionType.fromJson(block);
        if (action != null) {
            return action(o, path, action);
        }
        String what = "block " + block;
        switch (block) {
            case "repeat" -> {
                onlyFields(o, path, List.of("block", "times", "body"), what);
                Expr times = exprField(o, path, "times", true);
                List<Stmt> body = body(o, path, "body", true);
                return times == null ? null : new Stmt.Repeat(path, times, body);
            }
            case "repeat_until" -> {
                onlyFields(o, path, List.of("block", "condition", "body"), what);
                Expr condition = exprField(o, path, "condition", true);
                List<Stmt> body = body(o, path, "body", true);
                return condition == null ? null : new Stmt.RepeatUntil(path, condition, body);
            }
            case "forever" -> {
                onlyFields(o, path, List.of("block", "body"), what);
                return new Stmt.Forever(path, body(o, path, "body", true));
            }
            case "if" -> {
                onlyFields(o, path, List.of("block", "condition", "then", "else"), what);
                Expr condition = exprField(o, path, "condition", true);
                List<Stmt> then = body(o, path, "then", true);
                List<Stmt> otherwise = body(o, path, "else", false);
                return condition == null ? null : new Stmt.If(path, condition, then, otherwise);
            }
            case "wait_until" -> {
                onlyFields(o, path, List.of("block", "condition", "timeout_seconds"), what);
                Expr condition = exprField(o, path, "condition", true);
                Expr timeout = exprField(o, path, "timeout_seconds", false);
                return condition == null ? null : new Stmt.WaitUntil(path, condition, timeout);
            }
            case "break" -> {
                onlyFields(o, path, List.of("block"), what);
                return new Stmt.Break(path);
            }
            case "call" -> {
                onlyFields(o, path, List.of("block", "procedure", "args"), what);
                String procedure = string(o, path, "procedure", true);
                Map<String, Expr> args = callArgs(o, path);
                return procedure == null || args == null ? null : new Stmt.Call(path, procedure, args);
            }
            case "set_var" -> {
                onlyFields(o, path, List.of("block", "name", "value"), what);
                String name = string(o, path, "name", true);
                Expr value = exprField(o, path, "value", true);
                return name == null || value == null ? null : new Stmt.SetVar(path, name, value);
            }
            case "change_var" -> {
                onlyFields(o, path, List.of("block", "name", "delta"), what);
                String name = string(o, path, "name", true);
                Expr delta = exprField(o, path, "delta", true);
                return name == null || delta == null ? null : new Stmt.ChangeVar(path, name, delta);
            }
            default -> {
                List<String> known = new ArrayList<>(JsonNamed.names(ActionType.values()));
                known.addAll(Stmt.CONTROL_BLOCKS);
                error(DiagnosticCode.UNKNOWN_BLOCK, path, "unknown block '" + block + "'" + Suggestions.hint(block, known));
                return null;
            }
        }
    }

    private Map<String, Expr> callArgs(JsonObject o, String path) {
        JsonElement e = field(o, path, "args", false);
        if (e == null) {
            return Map.of();
        }
        String argsPath = join(path, "args");
        JsonObject args = object(e, argsPath, "args");
        if (args == null) {
            return null;
        }
        Map<String, Expr> out = new LinkedHashMap<>();
        boolean ok = true;
        for (Map.Entry<String, JsonElement> entry : args.entrySet()) {
            Expr value = expr(entry.getValue(), join(argsPath, entry.getKey()));
            if (value == null) {
                ok = false;
            } else {
                out.put(entry.getKey(), value);
            }
        }
        return ok ? out : null;
    }

    private Stmt action(JsonObject o, String path, ActionType type) {
        List<String> allowed = new ArrayList<>();
        allowed.add("block");
        for (FieldSpec f : type.fields()) {
            allowed.add(f.name());
        }
        onlyFields(o, path, allowed, "block " + type.jsonName());
        Map<String, ActionArg> args = new LinkedHashMap<>();
        boolean ok = true;
        for (FieldSpec f : type.fields()) {
            JsonElement e = field(o, path, f.name(), f.required());
            if (e == null) {
                ok &= !f.required();
                continue;
            }
            ActionArg arg = actionArg(f, e, join(path, f.name()));
            if (arg == null) {
                ok = false;
            } else {
                args.put(f.name(), arg);
            }
        }
        return ok ? new Stmt.Action(path, type, args) : null;
    }

    private ActionArg actionArg(FieldSpec f, JsonElement e, String path) {
        switch (f.kind()) {
            case TEXT -> {
                String s = asString(e, path, f.name());
                return s == null ? null : new ActionArg.TextArg(s);
            }
            case CHOICE -> {
                String s = asString(e, path, f.name());
                if (s == null) {
                    return null;
                }
                if (!f.choices().contains(s)) {
                    error(DiagnosticCode.INVALID_CHOICE, path, f.name() + " must be one of " + String.join(", ", f.choices())
                            + ", got '" + s + "'" + Suggestions.hint(s, f.choices()));
                    return null;
                }
                return new ActionArg.ChoiceArg(s);
            }
            case ID_LIST -> {
                if (!e.isJsonArray()) {
                    error(DiagnosticCode.WRONG_JSON_TYPE, path, f.name() + " must be an array of item ids, got " + describe(e));
                    return null;
                }
                List<Expr> items = new ArrayList<>();
                boolean ok = true;
                int i = 0;
                for (JsonElement item : e.getAsJsonArray()) {
                    Expr x = expr(item, path + "[" + i++ + "]");
                    if (x == null) {
                        ok = false;
                    } else {
                        items.add(x);
                    }
                }
                return ok ? new ActionArg.ListArg(items) : null;
            }
            case TARGET -> {
                Target target = target(e, path);
                return target == null ? null : new ActionArg.TargetArg(target);
            }
            default -> {
                Expr x = expr(e, path);
                return x == null ? null : new ActionArg.ExprArg(x);
            }
        }
    }

    private Target target(JsonElement e, String path) {
        JsonObject o = object(e, path, "a goto target");
        if (o == null) {
            return null;
        }
        String type = string(o, path, "type", true);
        if (type == null) {
            return null;
        }
        switch (type) {
            case "coords" -> {
                onlyFields(o, path, List.of("type", "x", "y", "z", "dimension"), "a coords target");
                Expr x = exprField(o, path, "x", true);
                Expr y = exprField(o, path, "y", true);
                Expr z = exprField(o, path, "z", true);
                String dimension = string(o, path, "dimension", false);
                return x == null || y == null || z == null ? null : new Target.Coords(x, y, z, dimension);
            }
            case "home" -> {
                onlyFields(o, path, List.of("type", "name"), "a home target");
                return new Target.Home(string(o, path, "name", false));
            }
            case "storage_zone" -> {
                onlyFields(o, path, List.of("type", "name"), "a storage_zone target");
                String name = string(o, path, "name", true);
                return name == null ? null : new Target.StorageZone(name);
            }
            case "portal" -> {
                onlyFields(o, path, List.of("type"), "a portal target");
                return new Target.Portal();
            }
            default -> {
                error(DiagnosticCode.INVALID_CHOICE, join(path, "type"), "target type must be one of "
                        + String.join(", ", Target.types()) + ", got '" + type + "'" + Suggestions.hint(type, Target.types()));
                return null;
            }
        }
    }

    // ------------------------------------------------------------------------------------------ expressions

    private Expr exprField(JsonObject o, String path, String key, boolean required) {
        JsonElement e = field(o, path, key, required);
        return e == null ? null : expr(e, join(path, key));
    }

    private Expr expr(JsonElement e, String path) {
        if (e.isJsonNull()) {
            error(DiagnosticCode.WRONG_JSON_TYPE, path, "null is not allowed here");
            return null;
        }
        if (e.isJsonArray()) {
            error(DiagnosticCode.WRONG_JSON_TYPE, path, "expected a number, a string, true/false or an {\"expr\": ...} object, got an array");
            return null;
        }
        if (!countNode(path)) {
            return null;
        }
        if (e.isJsonPrimitive()) {
            return new Expr.Literal(path, literal(e));
        }
        JsonObject o = e.getAsJsonObject();
        String kind = string(o, path, "expr", true);
        if (kind == null) {
            return null;
        }
        String what = "expression " + kind;
        SensorType sensor = JsonNamed.find(SensorType.values(), kind);
        if (sensor != null) {
            onlyFields(o, path, List.of("expr"), what);
            return new Expr.Sensor(path, sensor);
        }
        BinaryOp binary = JsonNamed.find(BinaryOp.values(), kind);
        if (binary != null) {
            onlyFields(o, path, List.of("expr", "a", "b"), what);
            Expr a = exprField(o, path, "a", true);
            Expr b = exprField(o, path, "b", true);
            return a == null || b == null ? null : new Expr.Binary(path, binary, a, b);
        }
        UnaryOp unary = JsonNamed.find(UnaryOp.values(), kind);
        if (unary != null) {
            onlyFields(o, path, List.of("expr", "a"), what);
            Expr a = exprField(o, path, "a", true);
            return a == null ? null : new Expr.Unary(path, unary, a);
        }
        switch (kind) {
            case "var", "param" -> {
                onlyFields(o, path, List.of("expr", "name"), what);
                String name = string(o, path, "name", true);
                if (name == null) {
                    return null;
                }
                return kind.equals("var") ? new Expr.Var(path, name) : new Expr.Param(path, name);
            }
            case "item_count" -> {
                onlyFields(o, path, List.of("expr", "item", "where"), what);
                Expr item = exprField(o, path, "item", true);
                Where where = choice(o, path, "where", Where.values(), Where.INVENTORY);
                return item == null ? null : new Expr.ItemCount(path, item, where);
            }
            case "position" -> {
                onlyFields(o, path, List.of("expr", "axis"), what);
                Axis axis = choice(o, path, "axis", Axis.values(), null);
                return axis == null ? null : new Expr.Position(path, axis);
            }
            case "block_at" -> {
                onlyFields(o, path, List.of("expr", "x", "y", "z"), what);
                Expr x = exprField(o, path, "x", true);
                Expr y = exprField(o, path, "y", true);
                Expr z = exprField(o, path, "z", true);
                return x == null || y == null || z == null ? null : new Expr.BlockAt(path, x, y, z);
            }
            case "random" -> {
                onlyFields(o, path, List.of("expr", "min", "max"), what);
                Expr min = exprField(o, path, "min", true);
                Expr max = exprField(o, path, "max", true);
                return min == null || max == null ? null : new Expr.RandomInt(path, min, max);
            }
            case "join" -> {
                onlyFields(o, path, List.of("expr", "parts"), what);
                int before = diagnostics.size();
                List<Expr> parts = list(o, path, "parts", true, this::expr);
                return diagnostics.size() > before ? null : new Expr.Join(path, parts);
            }
            default -> {
                error(DiagnosticCode.UNKNOWN_EXPR, path, "unknown expression '" + kind + "'" + Suggestions.hint(kind, Expr.kinds()));
                return null;
            }
        }
    }

    // ------------------------------------------------------------------------------------------ JSON helpers

    private boolean countNode(String path) {
        if (++nodes <= Limits.MAX_NODES) {
            return true;
        }
        if (!tooLarge) {
            tooLarge = true;
            error(DiagnosticCode.TOO_LARGE, path, "the script has more than " + Limits.MAX_NODES + " blocks and values");
        }
        return false;
    }

    private void error(DiagnosticCode code, String path, String message) {
        diagnostics.add(Diagnostic.of(code, path, message));
    }

    static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private JsonObject object(JsonElement e, String path, String what) {
        if (e.isJsonObject()) {
            return e.getAsJsonObject();
        }
        error(DiagnosticCode.WRONG_JSON_TYPE, path, what + " must be a JSON object {...}, got " + describe(e));
        return null;
    }

    private void onlyFields(JsonObject o, String path, List<String> allowed, String what) {
        for (String key : o.keySet()) {
            if (!allowed.contains(key)) {
                error(DiagnosticCode.UNKNOWN_FIELD, join(path, key),
                        what + " has no field '" + key + "'" + Suggestions.hint(key, allowed));
            }
        }
    }

    /** The field value; reports missing required fields and nulls. */
    private JsonElement field(JsonObject o, String path, String key, boolean required) {
        JsonElement e = o.get(key);
        if (e == null) {
            if (required) {
                error(DiagnosticCode.MISSING_FIELD, join(path, key), "missing field '" + key + "'");
            }
            return null;
        }
        if (e.isJsonNull()) {
            error(DiagnosticCode.WRONG_JSON_TYPE, join(path, key), key + " must not be null; leave it out instead");
            return null;
        }
        return e;
    }

    private String string(JsonObject o, String path, String key, boolean required) {
        JsonElement e = field(o, path, key, required);
        return e == null ? null : asString(e, join(path, key), key);
    }

    private String asString(JsonElement e, String path, String key) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            return e.getAsString();
        }
        error(DiagnosticCode.WRONG_JSON_TYPE, path, key + " must be a string, got " + describe(e));
        return null;
    }

    private Double number(JsonObject o, String path, String key, boolean required) {
        JsonElement e = field(o, path, key, required);
        if (e == null) {
            return null;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return e.getAsDouble();
        }
        error(DiagnosticCode.WRONG_JSON_TYPE, join(path, key), key + " must be a number, got " + describe(e));
        return null;
    }

    /** A choice field; {@code fallback == null} makes it required. Returns {@code fallback} after reporting a problem. */
    private <E extends JsonNamed> E choice(JsonObject o, String path, String key, E[] values, E fallback) {
        String s = string(o, path, key, fallback == null);
        if (s == null) {
            return fallback;
        }
        E value = JsonNamed.find(values, s);
        if (value == null) {
            List<String> names = JsonNamed.names(values);
            error(DiagnosticCode.INVALID_CHOICE, join(path, key), key + " must be one of " + String.join(", ", names)
                    + ", got '" + s + "'" + Suggestions.hint(s, names));
            return fallback;
        }
        return value;
    }

    private <T> List<T> list(JsonObject o, String path, String key, boolean required, BiFunction<JsonElement, String, T> reader) {
        JsonElement e = field(o, path, key, required);
        if (e == null) {
            return List.of();
        }
        String listPath = join(path, key);
        if (!e.isJsonArray()) {
            error(DiagnosticCode.WRONG_JSON_TYPE, listPath, key + " must be an array [...], got " + describe(e));
            return List.of();
        }
        List<T> out = new ArrayList<>();
        int i = 0;
        for (JsonElement item : e.getAsJsonArray()) {
            String itemPath = listPath + "[" + i++ + "]";
            if (item.isJsonNull()) {
                error(DiagnosticCode.WRONG_JSON_TYPE, itemPath, "null is not allowed here");
                continue;
            }
            T value = reader.apply(item, itemPath);
            if (value != null) {
                out.add(value);
            }
        }
        return out;
    }

    private static Value literal(JsonElement e) {
        if (!e.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return Value.of(p.getAsBoolean());
        }
        if (p.isNumber()) {
            return Value.of(p.getAsDouble());
        }
        return Value.of(p.getAsString());
    }

    static String describe(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return "null";
        }
        if (e.isJsonArray()) {
            return "an array";
        }
        if (e.isJsonObject()) {
            return "an object";
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return Boolean.toString(p.getAsBoolean());
        }
        if (p.isNumber()) {
            return "the number " + Value.formatNumber(p.getAsDouble());
        }
        String s = p.getAsString();
        return "the string \"" + (s.length() > 40 ? s.substring(0, 40) + "..." : s) + "\"";
    }
}
