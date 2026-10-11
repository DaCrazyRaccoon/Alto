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

import static baritone.alto.script.lang.FieldSpec.choice;
import static baritone.alto.script.lang.FieldSpec.coordinate;
import static baritone.alto.script.lang.FieldSpec.count;
import static baritone.alto.script.lang.FieldSpec.id;
import static baritone.alto.script.lang.FieldSpec.idList;

/**
 * The catalog of action blocks and their fields, in canonical field order. This is the single Java source of truth
 * for action blocks: the codec reads, the validator checks, the interpreter resolves and the schema test compares
 * against this list.
 * <p>
 * {@link Runner#HOST} actions are tasks the host performs over several ticks ({@code ScriptHost.start}); the
 * interpreter waits for each to succeed or fail. {@link Runner#INTERPRETER} actions ({@code wait}, {@code message},
 * {@code stop}) are handled by the interpreter itself.
 */
public enum ActionType implements JsonNamed {
    GOTO("goto", Runner.HOST, FieldSpec.target("target")),
    MINE("mine", Runner.HOST, id("block_or_item"), count("count")),
    BUILD("build", Runner.HOST, FieldSpec.fileName("schematic"), coordinate("x"), coordinate("y"), coordinate("z")),
    FILL("fill", Runner.HOST, id("material"), coordinate("x1"), coordinate("y1"), coordinate("z1"),
            coordinate("x2"), coordinate("y2"), coordinate("z2")),
    FARM("farm", Runner.HOST, FieldSpec.integer("range", 1, Limits.MAX_FARM_RANGE).optional(), idList("crops").optional()),
    CRAFT("craft", Runner.HOST, id("item"), count("count")),
    SMELT("smelt", Runner.HOST, id("item"), count("count")),
    FETCH("fetch", Runner.HOST, id("item"), count("count")),
    DEPOSIT("deposit", Runner.HOST, idList("except").optional(), idList("only").optional()),
    EAT("eat", Runner.HOST),
    WAIT("wait", Runner.INTERPRETER, FieldSpec.number("seconds", 0, Limits.MAX_WAIT_SECONDS)),
    MESSAGE("message", Runner.INTERPRETER, FieldSpec.any("text")),
    COMMAND("command", Runner.HOST, FieldSpec.literalText("command", Limits.MAX_CHAT_LENGTH)),
    SEND_CHAT("send_chat", Runner.HOST, FieldSpec.string("text", Limits.MAX_CHAT_LENGTH)),
    USE_ITEM_ON_BLOCK("use_item_on_block", Runner.HOST, id("item"), coordinate("x"), coordinate("y"), coordinate("z"),
            choice("face", Face.values())),
    SET_FILTER("set_filter", Runner.HOST, coordinate("x"), coordinate("y"), coordinate("z"),
            choice("face", Face.values()).optional(), id("item")),
    PLACE_STATION("place_station", Runner.HOST, choice("kind", StationType.values())),
    PICKUP_STATION("pickup_station", Runner.HOST, choice("kind", StationType.values())),
    BOARD_VEHICLE("board_vehicle", Runner.HOST, choice("kind", VehicleKind.values())),
    RIDE_TRAIN("ride_train", Runner.HOST, FieldSpec.name("from_station", Limits.MAX_NAME_LENGTH),
            FieldSpec.name("to_station", Limits.MAX_NAME_LENGTH)),
    LEAVE_VEHICLE("leave_vehicle", Runner.HOST),
    TAKE_PORTAL("take_portal", Runner.HOST),
    STOP("stop", Runner.INTERPRETER);

    /** Who performs an action. */
    public enum Runner {
        /** A task in the world, performed by the host over one or more ticks. */
        HOST,
        /** Handled by the interpreter. */
        INTERPRETER
    }

    private final String jsonName;
    private final Runner runner;
    private final List<FieldSpec> fields;

    ActionType(String jsonName, Runner runner, FieldSpec... fields) {
        this.jsonName = jsonName;
        this.runner = runner;
        this.fields = List.of(fields);
    }

    @Override
    public String jsonName() {
        return jsonName;
    }

    public Runner runner() {
        return runner;
    }

    public boolean isHostTask() {
        return runner == Runner.HOST;
    }

    /**
     * Whether the block gives the game time to run: every host task and {@code wait}. Loops must contain at least
     * one yielding block (the loop guard). {@code message} and {@code stop} do not yield.
     */
    public boolean yields() {
        return runner == Runner.HOST || this == WAIT;
    }

    /** Fields in canonical order. */
    public List<FieldSpec> fields() {
        return fields;
    }

    /** The field called {@code name}, or {@code null}. */
    public FieldSpec field(String name) {
        for (FieldSpec f : fields) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        return null;
    }

    public static ActionType fromJson(String name) {
        return JsonNamed.find(values(), name);
    }

    /**
     * The form of a {@code command} text that is checked against the allow-list and passed to the host: trimmed,
     * without Baritone's {@code #} chat prefix ({@code "#mine diamond_ore"} becomes {@code "mine diamond_ore"}).
     */
    public static String normalizeCommand(String raw) {
        String s = raw.trim();
        while (s.startsWith("#")) {
            s = s.substring(1).trim();
        }
        return s;
    }
}
