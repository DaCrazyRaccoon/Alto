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

/**
 * Ranges of the fields of control blocks, expressions and events (action fields are in {@link ActionType}).
 */
public final class Fields {

    public static final FieldSpec REPEAT_TIMES = FieldSpec.integer("times", 0, Limits.MAX_REPEAT);
    public static final FieldSpec TIMEOUT_SECONDS = FieldSpec.number("timeout_seconds", 0, Limits.MAX_WAIT_SECONDS).optional();
    public static final FieldSpec RANDOM_MIN = FieldSpec.integer("min", -Limits.MAX_RANDOM, Limits.MAX_RANDOM);
    public static final FieldSpec RANDOM_MAX = FieldSpec.integer("max", -Limits.MAX_RANDOM, Limits.MAX_RANDOM);
    public static final FieldSpec BLOCK_X = FieldSpec.coordinate("x");
    public static final FieldSpec BLOCK_Y = FieldSpec.coordinate("y");
    public static final FieldSpec BLOCK_Z = FieldSpec.coordinate("z");
    public static final FieldSpec ITEM = FieldSpec.id("item");
    public static final FieldSpec DIMENSION = FieldSpec.id("dimension").optional();
    public static final FieldSpec PLACE_NAME = FieldSpec.literalText("name", Limits.MAX_NAME_LENGTH);

    public static final FieldSpec EVENT_TRIGGER = FieldSpec.literalText("trigger", Limits.MAX_TRIGGER_LENGTH);
    public static final FieldSpec EVENT_HEALTH = FieldSpec.number("health", 1, Limits.MAX_HEALTH);
    public static final FieldSpec EVENT_ITEM = FieldSpec.id("item");
    public static final FieldSpec EVENT_COUNT = FieldSpec.count("count");
    public static final FieldSpec EVENT_SECONDS = FieldSpec.number("seconds", Limits.MIN_EVERY_SECONDS, Limits.MAX_WAIT_SECONDS);

    public static final FieldSpec SCRIPT_NAME = FieldSpec.literalText("name", Limits.MAX_SCRIPT_NAME_LENGTH);
    public static final FieldSpec SCRIPT_DESCRIPTION = FieldSpec.string("description", Limits.MAX_DESCRIPTION_LENGTH).optional();

    private Fields() {
    }
}
