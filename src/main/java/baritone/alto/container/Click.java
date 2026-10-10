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

package baritone.alto.container;

/**
 * One click to send, in menu-slot terms (never screen coordinates).
 *
 * @param slot   menu slot index, or -999 for "outside the window" (drop carried stack)
 * @param button mouse button / hotbar index / drag code, as vanilla expects for {@code kind}
 * @param kind   click type
 * @param why    short reason, shown in debug logs and the HUD
 */
public record Click(int slot, int button, ClickKind kind, String why) {

    public static final int OUTSIDE = -999;

    public static Click left(int slot, String why) {
        return new Click(slot, 0, ClickKind.PICKUP, why);
    }

    public static Click right(int slot, String why) {
        return new Click(slot, 1, ClickKind.PICKUP, why);
    }

    public static Click shift(int slot, String why) {
        return new Click(slot, 0, ClickKind.QUICK_MOVE, why);
    }

    public static Click swap(int slot, int hotbarIndex, String why) {
        return new Click(slot, hotbarIndex, ClickKind.SWAP, why);
    }

    @Override
    public String toString() {
        return kind + "(" + slot + "," + button + ") " + why;
    }
}
