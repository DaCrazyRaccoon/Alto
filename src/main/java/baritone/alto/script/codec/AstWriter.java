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
import baritone.alto.script.lang.Event;
import baritone.alto.script.lang.Expr;
import baritone.alto.script.lang.FieldSpec;
import baritone.alto.script.lang.Script;
import baritone.alto.script.lang.Script.EventHandler;
import baritone.alto.script.lang.Script.ParamDecl;
import baritone.alto.script.lang.Script.Procedure;
import baritone.alto.script.lang.Script.VarDecl;
import baritone.alto.script.lang.Stmt;
import baritone.alto.script.lang.Target;
import baritone.alto.script.lang.Value;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * Writes the AST as JSON in canonical form: fixed key order (the order of the language reference), whole numbers
 * without a fraction, defaults written out ({@code where}), empty optional lists left out ({@code else}), call
 * arguments sorted by name. Equal scripts therefore produce byte-identical output.
 */
final class AstWriter {

    private final JsonWriter w;

    private AstWriter(JsonWriter w) {
        this.w = w;
    }

    static String write(Script script, boolean pretty) {
        StringWriter out = new StringWriter();
        JsonWriter writer = new JsonWriter(out);
        writer.setHtmlSafe(false);
        writer.setSerializeNulls(false);
        if (pretty) {
            writer.setIndent("  ");
        }
        try {
            new AstWriter(writer).script(script);
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return pretty ? out + "\n" : out.toString();
    }

    private void script(Script s) throws IOException {
        w.beginObject();
        w.name("format").value(Script.FORMAT);
        w.name("version").value(s.version());
        w.name("name").value(s.name());
        w.name("description").value(s.description());
        w.name("variables").beginArray();
        for (VarDecl v : s.variables()) {
            w.beginObject();
            w.name("name").value(v.name());
            w.name("initial");
            value(v.initial());
            w.endObject();
        }
        w.endArray();
        w.name("procedures").beginArray();
        for (Procedure p : s.procedures()) {
            w.beginObject();
            w.name("name").value(p.name());
            w.name("params").beginArray();
            for (ParamDecl param : p.params()) {
                w.beginObject();
                w.name("name").value(param.name());
                w.name("type").value(param.type().jsonName());
                w.endObject();
            }
            w.endArray();
            body("body", p.body());
            w.endObject();
        }
        w.endArray();
        w.name("events").beginArray();
        for (EventHandler h : s.handlers()) {
            handler(h);
        }
        w.endArray();
        w.endObject();
    }

    private void handler(EventHandler h) throws IOException {
        w.beginObject();
        w.name("event").value(h.event().type().jsonName());
        switch (h.event()) {
            case Event.OnChat c -> w.name("trigger").value(c.trigger());
            case Event.OnHealthBelow b -> {
                w.name("health");
                number(b.health());
            }
            case Event.OnItemBelow b -> {
                w.name("item").value(b.item());
                w.name("count");
                number(b.count());
                w.name("where").value(b.where().jsonName());
            }
            case Event.Every e -> {
                w.name("seconds");
                number(e.seconds());
            }
            case Event.OnStart ignored -> {
            }
            case Event.OnInventoryFull ignored -> {
            }
        }
        body("body", h.body());
        w.endObject();
    }

    private void body(String key, List<Stmt> body) throws IOException {
        w.name(key).beginArray();
        for (Stmt s : body) {
            statement(s);
        }
        w.endArray();
    }

    private void statement(Stmt s) throws IOException {
        w.beginObject();
        w.name("block").value(s.block());
        switch (s) {
            case Stmt.Action a -> actionFields(a);
            case Stmt.Repeat r -> {
                exprField("times", r.times());
                body("body", r.body());
            }
            case Stmt.RepeatUntil r -> {
                exprField("condition", r.condition());
                body("body", r.body());
            }
            case Stmt.Forever f -> body("body", f.body());
            case Stmt.If i -> {
                exprField("condition", i.condition());
                body("then", i.then());
                if (!i.otherwise().isEmpty()) {
                    body("else", i.otherwise());
                }
            }
            case Stmt.WaitUntil wu -> {
                exprField("condition", wu.condition());
                if (wu.timeoutSeconds() != null) {
                    exprField("timeout_seconds", wu.timeoutSeconds());
                }
            }
            case Stmt.Break ignored -> {
            }
            case Stmt.Call c -> {
                w.name("procedure").value(c.procedure());
                w.name("args").beginObject();
                for (Map.Entry<String, Expr> arg : c.args().entrySet()) {
                    exprField(arg.getKey(), arg.getValue());
                }
                w.endObject();
            }
            case Stmt.SetVar sv -> {
                w.name("name").value(sv.name());
                exprField("value", sv.value());
            }
            case Stmt.ChangeVar cv -> {
                w.name("name").value(cv.name());
                exprField("delta", cv.delta());
            }
        }
        w.endObject();
    }

    private void actionFields(Stmt.Action a) throws IOException {
        for (FieldSpec f : a.type().fields()) {
            ActionArg arg = a.args().get(f.name());
            if (arg == null) {
                continue;
            }
            w.name(f.name());
            switch (arg) {
                case ActionArg.ExprArg e -> expr(e.expr());
                case ActionArg.ChoiceArg c -> w.value(c.value());
                case ActionArg.TextArg t -> w.value(t.text());
                case ActionArg.ListArg l -> {
                    w.beginArray();
                    for (Expr item : l.items()) {
                        expr(item);
                    }
                    w.endArray();
                }
                case ActionArg.TargetArg t -> target(t.target());
            }
        }
    }

    private void target(Target t) throws IOException {
        w.beginObject();
        w.name("type").value(t.type());
        switch (t) {
            case Target.Coords c -> {
                exprField("x", c.x());
                exprField("y", c.y());
                exprField("z", c.z());
                if (c.dimension() != null) {
                    w.name("dimension").value(c.dimension());
                }
            }
            case Target.Home h -> {
                if (h.name() != null) {
                    w.name("name").value(h.name());
                }
            }
            case Target.StorageZone z -> w.name("name").value(z.name());
            case Target.Portal ignored -> {
            }
        }
        w.endObject();
    }

    private void exprField(String key, Expr e) throws IOException {
        w.name(key);
        expr(e);
    }

    private void expr(Expr e) throws IOException {
        if (e instanceof Expr.Literal l) {
            value(l.value());
            return;
        }
        w.beginObject();
        w.name("expr").value(e.kind());
        switch (e) {
            case Expr.Var v -> w.name("name").value(v.name());
            case Expr.Param p -> w.name("name").value(p.name());
            case Expr.ItemCount ic -> {
                exprField("item", ic.item());
                w.name("where").value(ic.where().jsonName());
            }
            case Expr.Position p -> w.name("axis").value(p.axis().jsonName());
            case Expr.BlockAt b -> {
                exprField("x", b.x());
                exprField("y", b.y());
                exprField("z", b.z());
            }
            case Expr.RandomInt r -> {
                exprField("min", r.min());
                exprField("max", r.max());
            }
            case Expr.Binary b -> {
                exprField("a", b.a());
                exprField("b", b.b());
            }
            case Expr.Unary u -> exprField("a", u.a());
            case Expr.Join j -> {
                w.name("parts").beginArray();
                for (Expr part : j.parts()) {
                    expr(part);
                }
                w.endArray();
            }
            case Expr.Sensor ignored -> {
            }
            case Expr.Literal ignored -> {
            }
        }
        w.endObject();
    }

    private void value(Value v) throws IOException {
        switch (v) {
            case Value.Num n -> number(n.value());
            case Value.Str s -> w.value(s.value());
            case Value.Bool b -> w.value(b.value());
        }
    }

    /** Whole numbers below 2^53 as integers ({@code 64}, not {@code 64.0}); others as the shortest double form. */
    private void number(double v) throws IOException {
        if (Value.isWhole(v) && Math.abs(v) < 9.007199254740992E15) {
            w.value((long) v);
        } else {
            w.value(v);
        }
    }
}
