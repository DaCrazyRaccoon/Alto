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

import baritone.alto.script.lang.ActionType;
import baritone.alto.script.lang.Face;
import baritone.alto.script.lang.StationType;
import baritone.alto.script.lang.VehicleKind;

import java.util.List;

/**
 * The evaluated arguments of a host action, one record per action block. Ids are normalized
 * ({@code minecraft:oak_planks}); counts and coordinates are range-checked whole numbers. The binding switches over
 * this sealed type, so a new action block is a compile error until it is handled.
 */
public sealed interface ActionRequest {

    ActionType type();

    record Goto(GotoTarget target) implements ActionRequest {
        public ActionType type() {
            return ActionType.GOTO;
        }
    }

    /** Mine until {@code count} more of the block (or of blocks that drop the item) have been collected. */
    record Mine(String blockOrItem, int count) implements ActionRequest {
        public ActionType type() {
            return ActionType.MINE;
        }
    }

    /** Build a schematic from the host's schematics folder ({@code schematic} is a plain file name) at x, y, z. */
    record Build(String schematic, int x, int y, int z) implements ActionRequest {
        public ActionType type() {
            return ActionType.BUILD;
        }
    }

    /** Fill the box between the two corners (inclusive) with the block {@code material}. */
    record Fill(String material, int x1, int y1, int z1, int x2, int y2, int z2) implements ActionRequest {
        public ActionType type() {
            return ActionType.FILL;
        }
    }

    /** One harvest-and-replant pass. {@code range} 0 = host default; empty {@code crops} = every crop. */
    record Farm(int range, List<String> crops) implements ActionRequest {
        public Farm {
            crops = List.copyOf(crops);
        }

        public ActionType type() {
            return ActionType.FARM;
        }
    }

    /** Craft {@code count} more of {@code item} (the planner decides how). */
    record Craft(String item, int count) implements ActionRequest {
        public ActionType type() {
            return ActionType.CRAFT;
        }
    }

    /** Smelt until {@code count} more of {@code item} (the result, e.g. iron_ingot) have been produced. */
    record Smelt(String item, int count) implements ActionRequest {
        public ActionType type() {
            return ActionType.SMELT;
        }
    }

    /** Take {@code count} of {@code item} from known storage into the inventory. */
    record Fetch(String item, int count) implements ActionRequest {
        public ActionType type() {
            return ActionType.FETCH;
        }
    }

    /**
     * Put inventory items into storage. Items in {@code except} stay; when {@code only} is not empty, only those
     * items are deposited. Equipped armor, the offhand and tools in use always stay (host rule).
     */
    record Deposit(List<String> except, List<String> only) implements ActionRequest {
        public Deposit {
            except = List.copyOf(except);
            only = List.copyOf(only);
        }

        public ActionType type() {
            return ActionType.DEPOSIT;
        }
    }

    /** Eat food from the inventory until no longer hungry. */
    record Eat() implements ActionRequest {
        public ActionType type() {
            return ActionType.EAT;
        }
    }

    /** A normalized Baritone command (no {@code #}) that already passed {@link ScriptHost#commandAllowed}. */
    record Command(String command) implements ActionRequest {
        public ActionType type() {
            return ActionType.COMMAND;
        }
    }

    /** Real chat (or a server command when it starts with {@code /}); {@link ScriptHost#chatAllowed} was true. */
    record SendChat(String text) implements ActionRequest {
        public ActionType type() {
            return ActionType.SEND_CHAT;
        }
    }

    /** Right-click {@code face} of the block at x, y, z while holding {@code item}. */
    record UseItemOnBlock(String item, int x, int y, int z, Face face) implements ActionRequest {
        public ActionType type() {
            return ActionType.USE_ITEM_ON_BLOCK;
        }
    }

    /** Set the filter of the block at x, y, z to {@code item}; {@code face} may be {@code null} (host decides). */
    record SetFilter(int x, int y, int z, Face face, String item) implements ActionRequest {
        public ActionType type() {
            return ActionType.SET_FILTER;
        }
    }

    record PlaceStation(StationType kind) implements ActionRequest {
        public ActionType type() {
            return ActionType.PLACE_STATION;
        }
    }

    /** Pick up a station Alto placed (never one the player built). */
    record PickupStation(StationType kind) implements ActionRequest {
        public ActionType type() {
            return ActionType.PICKUP_STATION;
        }
    }

    record BoardVehicle(VehicleKind kind) implements ActionRequest {
        public ActionType type() {
            return ActionType.BOARD_VEHICLE;
        }
    }

    /** Ride a Create train from one named station to another. */
    record RideTrain(String fromStation, String toStation) implements ActionRequest {
        public ActionType type() {
            return ActionType.RIDE_TRAIN;
        }
    }

    record LeaveVehicle() implements ActionRequest {
        public ActionType type() {
            return ActionType.LEAVE_VEHICLE;
        }
    }

    /** Go through the portal the player stands next to (or the nearest known one) and wait for the new dimension. */
    record TakePortal() implements ActionRequest {
        public ActionType type() {
            return ActionType.TAKE_PORTAL;
        }
    }
}
