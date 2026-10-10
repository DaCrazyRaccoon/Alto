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

import baritone.alto.script.lang.ActionArg;
import baritone.alto.script.lang.ActionType;
import baritone.alto.script.lang.Expr;
import baritone.alto.script.lang.Face;
import baritone.alto.script.lang.FieldSpec;
import baritone.alto.script.lang.Fields;
import baritone.alto.script.lang.Ids;
import baritone.alto.script.lang.JsonNamed;
import baritone.alto.script.lang.StationType;
import baritone.alto.script.lang.Stmt;
import baritone.alto.script.lang.Target;
import baritone.alto.script.lang.Value;
import baritone.alto.script.lang.VehicleKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Internal: evaluates the fields of an action block into a typed {@link ActionRequest}, checking every value with
 * the field's {@link FieldSpec} (the same checks the validator applies to literals).
 */
final class ActionResolver {

    private final Evaluator ev;

    ActionResolver(Evaluator ev) {
        this.ev = ev;
    }

    ActionRequest resolve(Stmt.Action a, Map<String, Value> params) {
        Args args = new Args(a, params);
        return switch (a.type()) {
            case GOTO -> new ActionRequest.Goto(target(a, params));
            case MINE -> new ActionRequest.Mine(args.id("block_or_item"), args.whole("count"));
            case BUILD -> new ActionRequest.Build(args.text("schematic"), args.whole("x"), args.whole("y"), args.whole("z"));
            case FILL -> new ActionRequest.Fill(args.id("material"), args.whole("x1"), args.whole("y1"), args.whole("z1"),
                    args.whole("x2"), args.whole("y2"), args.whole("z2"));
            case FARM -> new ActionRequest.Farm(a.args().containsKey("range") ? args.whole("range") : 0, args.ids("crops"));
            case CRAFT -> new ActionRequest.Craft(args.id("item"), args.whole("count"));
            case SMELT -> new ActionRequest.Smelt(args.id("item"), args.whole("count"));
            case FETCH -> new ActionRequest.Fetch(args.id("item"), args.whole("count"));
            case DEPOSIT -> new ActionRequest.Deposit(args.ids("except"), args.ids("only"));
            case EAT -> new ActionRequest.Eat();
            case COMMAND -> new ActionRequest.Command(ActionType.normalizeCommand(args.literal("command")));
            case SEND_CHAT -> new ActionRequest.SendChat(args.text("text"));
            case USE_ITEM_ON_BLOCK -> new ActionRequest.UseItemOnBlock(args.id("item"), args.whole("x"), args.whole("y"),
                    args.whole("z"), args.choice("face", Face.values()));
            case SET_FILTER -> new ActionRequest.SetFilter(args.whole("x"), args.whole("y"), args.whole("z"),
                    a.args().containsKey("face") ? args.choice("face", Face.values()) : null, args.id("item"));
            case PLACE_STATION -> new ActionRequest.PlaceStation(args.choice("kind", StationType.values()));
            case PICKUP_STATION -> new ActionRequest.PickupStation(args.choice("kind", StationType.values()));
            case BOARD_VEHICLE -> new ActionRequest.BoardVehicle(args.choice("kind", VehicleKind.values()));
            case RIDE_TRAIN -> new ActionRequest.RideTrain(args.text("from_station"), args.text("to_station"));
            case LEAVE_VEHICLE -> new ActionRequest.LeaveVehicle();
            case TAKE_PORTAL -> new ActionRequest.TakePortal();
            case WAIT, MESSAGE, STOP -> throw new IllegalArgumentException(a.type().jsonName() + " is not a host action");
        };
    }

    private GotoTarget target(Stmt.Action a, Map<String, Value> params) {
        if (!(a.args().get("target") instanceof ActionArg.TargetArg t)) {
            throw new ScriptRuntimeException(a.path(), "goto has no target");
        }
        return switch (t.target()) {
            case Target.Coords c -> new GotoTarget.Coords(ev.whole(c.x(), Fields.BLOCK_X, params),
                    ev.whole(c.y(), Fields.BLOCK_Y, params), ev.whole(c.z(), Fields.BLOCK_Z, params),
                    c.dimension() == null ? null : Ids.normalize(c.dimension()));
            case Target.Home h -> new GotoTarget.Home(h.name());
            case Target.StorageZone z -> new GotoTarget.StorageZone(z.name());
            case Target.Portal ignored -> new GotoTarget.Portal();
        };
    }

    /** Field access for one action block. */
    private final class Args {
        private final Stmt.Action action;
        private final Map<String, Value> params;

        Args(Stmt.Action action, Map<String, Value> params) {
            this.action = action;
            this.params = params;
        }

        private FieldSpec spec(String field) {
            return action.type().field(field);
        }

        private Expr expr(String field) {
            Expr e = action.expr(field);
            if (e == null) {
                throw new ScriptRuntimeException(action.path(), action.type().jsonName() + " has no " + field);
            }
            return e;
        }

        int whole(String field) {
            return ev.whole(expr(field), spec(field), params);
        }

        String text(String field) {
            return ev.text(expr(field), spec(field), params);
        }

        String id(String field) {
            return ev.id(expr(field), spec(field), params);
        }

        List<String> ids(String field) {
            List<String> out = new ArrayList<>();
            if (action.args().get(field) instanceof ActionArg.ListArg list) {
                for (Expr item : list.items()) {
                    String id = ev.id(item, spec(field), params);
                    if (!out.contains(id)) {
                        out.add(id);
                    }
                }
            }
            return out;
        }

        String literal(String field) {
            if (action.args().get(field) instanceof ActionArg.TextArg t) {
                return t.text();
            }
            throw new ScriptRuntimeException(action.path(), action.type().jsonName() + " has no " + field);
        }

        <E extends JsonNamed> E choice(String field, E[] values) {
            if (action.args().get(field) instanceof ActionArg.ChoiceArg c) {
                E value = JsonNamed.find(values, c.value());
                if (value != null) {
                    return value;
                }
            }
            throw new ScriptRuntimeException(action.path(), action.type().jsonName() + " has no valid " + field);
        }
    }
}
