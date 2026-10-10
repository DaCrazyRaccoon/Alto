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

import baritone.alto.script.lang.BinaryOp;
import baritone.alto.script.lang.Expr;
import baritone.alto.script.lang.FieldSpec;
import baritone.alto.script.lang.Fields;
import baritone.alto.script.lang.Ids;
import baritone.alto.script.lang.Limits;
import baritone.alto.script.lang.UnaryOp;
import baritone.alto.script.lang.Value;
import baritone.alto.script.lang.ValueType;

import java.util.Map;
import java.util.Random;

/**
 * Internal: evaluates expressions by walking the immutable {@link Expr} records produced by the codec (a fixed set
 * of node types; nothing is compiled or executed dynamically). Every expression node costs one step of the tick
 * budget. Type, range and arithmetic problems throw {@link ScriptRuntimeException} with the expression's path.
 */
final class Evaluator {

    private final ScriptRun run;
    private final Random random;

    Evaluator(ScriptRun run, long seed) {
        this.run = run;
        this.random = new Random(seed);
    }

    Value eval(Expr e, Map<String, Value> params) {
        run.countStep();
        return switch (e) {
            case Expr.Literal l -> l.value();
            case Expr.Var v -> {
                Value value = run.variableValue(v.name());
                if (value == null) {
                    throw new ScriptRuntimeException(v.path(), "there is no variable called '" + v.name() + "'");
                }
                yield value;
            }
            case Expr.Param p -> {
                Value value = params == null ? null : params.get(p.name());
                if (value == null) {
                    throw new ScriptRuntimeException(p.path(), "there is no parameter called '" + p.name() + "' here");
                }
                yield value;
            }
            case Expr.ItemCount ic -> query(new QueryCall.ItemCount(id(ic.item(), Fields.ITEM, params), ic.where()), ic.path());
            case Expr.Sensor s -> query(switch (s.sensor()) {
                case HEALTH -> new QueryCall.Health();
                case HUNGER -> new QueryCall.Hunger();
                case TIME_OF_DAY -> new QueryCall.TimeOfDay();
                case DIMENSION -> new QueryCall.Dimension();
                case INVENTORY_FULL -> new QueryCall.InventoryFull();
            }, s.path());
            case Expr.Position p -> query(new QueryCall.Position(p.axis()), p.path());
            case Expr.BlockAt b -> query(new QueryCall.BlockAt(whole(b.x(), Fields.BLOCK_X, params),
                    whole(b.y(), Fields.BLOCK_Y, params), whole(b.z(), Fields.BLOCK_Z, params)), b.path());
            case Expr.RandomInt r -> {
                long lo = whole(r.min(), Fields.RANDOM_MIN, params);
                long hi = whole(r.max(), Fields.RANDOM_MAX, params);
                if (lo > hi) {
                    long swap = lo;
                    lo = hi;
                    hi = swap;
                }
                yield Value.of(lo + random.nextLong(hi - lo + 1));
            }
            case Expr.Binary b -> binary(b, params);
            case Expr.Unary u -> unary(u, params);
            case Expr.Join j -> {
                StringBuilder sb = new StringBuilder();
                for (Expr part : j.parts()) {
                    sb.append(eval(part, params).display());
                    if (sb.length() > Limits.MAX_STRING_VALUE) {
                        throw new ScriptRuntimeException(j.path(), "joined text is longer than "
                                + Limits.MAX_STRING_VALUE + " characters");
                    }
                }
                yield Value.of(sb.toString());
            }
        };
    }

    private Value binary(Expr.Binary b, Map<String, Value> params) {
        BinaryOp op = b.op();
        String what = op.jsonName();
        switch (op.category()) {
            case LOGIC -> {
                boolean left = bool(b.a(), params, what);
                if (op == BinaryOp.AND && !left) {
                    return Value.of(false);
                }
                if (op == BinaryOp.OR && left) {
                    return Value.of(true);
                }
                return Value.of(bool(b.b(), params, what));
            }
            case EQUALITY -> {
                Value left = eval(b.a(), params);
                Value right = eval(b.b(), params);
                if (left.type() != right.type()) {
                    throw new ScriptRuntimeException(b.path(), what + " compares two values of the same type, got "
                            + left.type().withArticle() + " and " + right.type().withArticle());
                }
                boolean equal = left.equals(right);
                return Value.of(op == BinaryOp.EQ ? equal : !equal);
            }
            case ORDER -> {
                double x = number(b.a(), params, what);
                double y = number(b.b(), params, what);
                return Value.of(switch (op) {
                    case LT -> x < y;
                    case LTE -> x <= y;
                    case GT -> x > y;
                    default -> x >= y;
                });
            }
            default -> {
                double x = number(b.a(), params, what);
                double y = number(b.b(), params, what);
                if ((op == BinaryOp.DIV || op == BinaryOp.MOD) && y == 0) {
                    throw new ScriptRuntimeException(b.path(), what + " by zero");
                }
                double result = switch (op) {
                    case ADD -> x + y;
                    case SUB -> x - y;
                    case MUL -> x * y;
                    case DIV -> x / y;
                    case MOD -> x - y * Math.floor(x / y);
                    case MIN -> Math.min(x, y);
                    case MAX -> Math.max(x, y);
                    default -> throw new IllegalStateException(what);
                };
                return finite(result, b.path(), what);
            }
        }
    }

    private Value unary(Expr.Unary u, Map<String, Value> params) {
        UnaryOp op = u.op();
        if (op == UnaryOp.NOT) {
            return Value.of(!bool(u.a(), params, "not"));
        }
        double x = number(u.a(), params, op.jsonName());
        return finite(op == UnaryOp.ABS ? Math.abs(x) : Math.floor(x + 0.5), u.path(), op.jsonName());
    }

    private static Value finite(double v, String path, String what) {
        if (!Double.isFinite(v)) {
            throw new ScriptRuntimeException(path, what + " produced a number that is too large");
        }
        return Value.of(v);
    }

    // ------------------------------------------------------------------------------------------ typed helpers

    boolean bool(Expr e, Map<String, Value> params, String what) {
        Value v = eval(e, params);
        if (v instanceof Value.Bool b) {
            return b.value();
        }
        throw typeError(e, v, ValueType.BOOLEAN, what);
    }

    double number(Expr e, Map<String, Value> params, String what) {
        Value v = eval(e, params);
        if (v instanceof Value.Num n) {
            return n.value();
        }
        throw typeError(e, v, ValueType.NUMBER, what);
    }

    /** A number checked against the field's range. */
    double number(Expr e, FieldSpec spec, Map<String, Value> params) {
        double d = number(e, params, spec.name());
        FieldSpec.Problem problem = spec.checkNumber(d);
        if (problem != null) {
            throw new ScriptRuntimeException(e.path(), problem.message());
        }
        return d;
    }

    /** A whole number checked against the field's range (all integer fields fit in an {@code int}). */
    int whole(Expr e, FieldSpec spec, Map<String, Value> params) {
        return (int) number(e, spec, params);
    }

    /** A string checked against the field (length, id format, file name). */
    String text(Expr e, FieldSpec spec, Map<String, Value> params) {
        Value v = eval(e, params);
        if (!(v instanceof Value.Str s)) {
            throw typeError(e, v, ValueType.STRING, spec.name());
        }
        FieldSpec.Problem problem = spec.checkText(s.value());
        if (problem != null) {
            throw new ScriptRuntimeException(e.path(), problem.message());
        }
        return s.value();
    }

    /** A checked and normalized item/block id. */
    String id(Expr e, FieldSpec spec, Map<String, Value> params) {
        return Ids.normalize(text(e, spec, params));
    }

    /** Asks the host; checks the answer's type. Not counted as a step (the expression node was). */
    Value query(QueryCall q, String path) {
        Value v = run.hostCall(path, "read " + q.name(), () -> run.host().query(q));
        if (v == null || v.type() != q.resultType()) {
            throw new ScriptRuntimeException(path, "Alto answered " + (v == null ? "nothing" : v.type().withArticle())
                    + " for " + q.name() + " instead of " + q.resultType().withArticle());
        }
        return v;
    }

    private static ScriptRuntimeException typeError(Expr e, Value v, ValueType want, String what) {
        return new ScriptRuntimeException(e.path(), what + " must be " + want.withArticle() + ", got "
                + v.type().withArticle() + " (" + v.display() + ")");
    }
}
