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
 * Mirrors vanilla {@code ClickType}, kept here so planning code stays Minecraft-free.
 * {@code CLONE} (creative middle-click) is deliberately absent: survival clients cannot use it.
 */
public enum ClickKind {
    /** Left (button 0) or right (button 1) click: pick up / put down / split. */
    PICKUP,
    /** Shift-click: move the stack to the other part of the menu. */
    QUICK_MOVE,
    /** Number key: swap with hotbar slot {@code button} (0-8) or offhand (40). */
    SWAP,
    /** Drop key: button 0 = one item, 1 = whole stack. */
    THROW,
    /** Drag distribution: header/slot/end steps encoded in the button. */
    QUICK_CRAFT,
    /** Double click: gather all of the carried item. */
    PICKUP_ALL
}
