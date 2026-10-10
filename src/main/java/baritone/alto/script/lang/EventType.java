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
 * The event kinds a handler can listen to (the {@code event} field of a handler).
 */
public enum EventType implements JsonNamed {
    /** Runs once when the script starts. */
    ON_START("on_start"),
    /** A chat trigger typed by the local user (never text received from the server). */
    ON_CHAT("on_chat"),
    ON_INVENTORY_FULL("on_inventory_full"),
    ON_HEALTH_BELOW("on_health_below"),
    ON_ITEM_BELOW("on_item_below"),
    /** A timer the interpreter runs itself. */
    EVERY("every");

    private final String jsonName;

    EventType(String jsonName) {
        this.jsonName = jsonName;
    }

    @Override
    public String jsonName() {
        return jsonName;
    }
}
