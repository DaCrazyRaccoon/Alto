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

import baritone.alto.script.lang.ActionType;

import java.util.EnumSet;
import java.util.Set;

/**
 * Something a script may do in the world, listed on the first-run confirmation screen.
 * The mapping from blocks is {@link #of(ActionType)}; the host must not let an action do more than its capabilities.
 */
public enum Capability {
    MOVES("walks around"),
    BREAKS_BLOCKS("breaks blocks"),
    PLACES_BLOCKS("places blocks"),
    USES_ITEMS("uses items (right-clicks blocks, eats)"),
    CRAFTS("crafts items"),
    SMELTS("smelts items in furnaces"),
    MOVES_ITEMS_TO_STORAGE("puts items from your inventory into storage"),
    TAKES_FROM_STORAGE("takes items out of storage"),
    TRAVELS_PORTALS("walks through portals"),
    RIDES_VEHICLES("rides boats, minecarts or trains"),
    RUNS_COMMANDS("runs Baritone commands"),
    SENDS_CHAT("sends chat messages or server commands as you"),
    SETS_FILTERS("changes filters on blocks (e.g. Create deployers)"),
    RUNS_UNTIL_STOPPED("keeps running until you stop it"),
    LISTENS_TO_CHAT("reacts to chat triggers that you type");

    private final String sentence;

    Capability(String sentence) {
        this.sentence = sentence;
    }

    /** Lower-case phrase, e.g. {@code breaks blocks}. */
    public String sentence() {
        return sentence;
    }

    /** What one action block may do. {@code wait}, {@code message} and {@code stop} do nothing in the world. */
    public static Set<Capability> of(ActionType type) {
        return switch (type) {
            case GOTO -> EnumSet.of(MOVES);
            case MINE -> EnumSet.of(MOVES, BREAKS_BLOCKS);
            case BUILD, FILL, FARM -> EnumSet.of(MOVES, BREAKS_BLOCKS, PLACES_BLOCKS);
            case CRAFT -> EnumSet.of(MOVES, CRAFTS);
            case SMELT -> EnumSet.of(MOVES, SMELTS);
            case FETCH -> EnumSet.of(MOVES, TAKES_FROM_STORAGE);
            case DEPOSIT -> EnumSet.of(MOVES, MOVES_ITEMS_TO_STORAGE);
            case EAT -> EnumSet.of(USES_ITEMS);
            case USE_ITEM_ON_BLOCK -> EnumSet.of(MOVES, USES_ITEMS);
            case SET_FILTER -> EnumSet.of(MOVES, USES_ITEMS, SETS_FILTERS);
            case PLACE_STATION -> EnumSet.of(PLACES_BLOCKS);
            case PICKUP_STATION -> EnumSet.of(BREAKS_BLOCKS);
            case BOARD_VEHICLE, RIDE_TRAIN -> EnumSet.of(MOVES, RIDES_VEHICLES);
            case LEAVE_VEHICLE -> EnumSet.of(RIDES_VEHICLES);
            case TAKE_PORTAL -> EnumSet.of(MOVES, TRAVELS_PORTALS);
            case COMMAND -> EnumSet.of(RUNS_COMMANDS);
            case SEND_CHAT -> EnumSet.of(SENDS_CHAT);
            case WAIT, MESSAGE, STOP -> EnumSet.noneOf(Capability.class);
        };
    }
}
