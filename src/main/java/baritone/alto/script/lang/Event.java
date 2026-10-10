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

import java.util.Locale;

/**
 * What starts an event handler. Event fields are literals (known before the script runs).
 */
public sealed interface Event {

    EventType type();

    /** Short label for the runner UI, e.g. {@code on_chat "!go"}, {@code every 300 s}. */
    String label();

    /** Two events with the same key would start for the same reason (the validator forbids that). */
    default String duplicateKey() {
        return label().toLowerCase(Locale.ROOT);
    }

    record OnStart() implements Event {
        @Override
        public EventType type() {
            return EventType.ON_START;
        }

        @Override
        public String label() {
            return "on_start";
        }
    }

    /** {@code trigger} is compared to the trimmed typed text, ignoring case. */
    record OnChat(String trigger) implements Event {
        @Override
        public EventType type() {
            return EventType.ON_CHAT;
        }

        @Override
        public String label() {
            return "on_chat \"" + trigger.trim() + "\"";
        }
    }

    record OnInventoryFull() implements Event {
        @Override
        public EventType type() {
            return EventType.ON_INVENTORY_FULL;
        }

        @Override
        public String label() {
            return "on_inventory_full";
        }
    }

    /** Starts when health drops below {@code health}. */
    record OnHealthBelow(double health) implements Event {
        @Override
        public EventType type() {
            return EventType.ON_HEALTH_BELOW;
        }

        @Override
        public String label() {
            return "on_health_below " + Value.formatNumber(health);
        }
    }

    /** Starts when the count of {@code item} in {@code where} drops below {@code count} (a whole number). */
    record OnItemBelow(String item, double count, Where where) implements Event {
        @Override
        public EventType type() {
            return EventType.ON_ITEM_BELOW;
        }

        @Override
        public String label() {
            String id = Ids.isValidId(item) ? Ids.normalize(item) : item;
            return "on_item_below " + id + " " + Value.formatNumber(count) + " " + where.jsonName();
        }
    }

    /** A timer: starts every {@code seconds}, the first time {@code seconds} after the run starts. */
    record Every(double seconds) implements Event {
        @Override
        public EventType type() {
            return EventType.EVERY;
        }

        @Override
        public String label() {
            return "every " + Value.formatNumber(seconds) + " s";
        }
    }
}
