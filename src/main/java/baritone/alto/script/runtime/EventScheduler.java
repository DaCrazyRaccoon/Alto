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

import baritone.alto.script.lang.Event;
import baritone.alto.script.lang.EventType;
import baritone.alto.script.lang.Ids;
import baritone.alto.script.lang.Script;
import baritone.alto.script.lang.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * Internal: decides when event handlers start (the rules are in {@link ScriptRun}'s class comment): no re-entrancy,
 * {@code every} timers on the run clock without catch-up, edge-triggered condition events checked periodically.
 */
final class EventScheduler {

    /** Creates and registers an activation for a handler. */
    interface Starter {
        Activation start(int handlerIndex, Script.EventHandler handler);
    }

    /** Per handler: its activation (if running), condition arming and timer. */
    private static final class HandlerState {
        final int index;
        final Script.EventHandler handler;
        Activation running;
        boolean armed = true;
        long nextFireAt;

        HandlerState(int index, Script.EventHandler handler) {
            this.index = index;
            this.handler = handler;
        }

        Event event() {
            return handler.event();
        }
    }

    private final List<HandlerState> handlers = new ArrayList<>();
    private final Evaluator evaluator;
    private final Starter starter;
    private final long conditionCheckMillis;
    private final boolean listening;
    private final boolean hasConditions;
    private long nextConditionCheck;

    EventScheduler(Script script, Evaluator evaluator, Starter starter, long conditionCheckMillis) {
        this.evaluator = evaluator;
        this.starter = starter;
        this.conditionCheckMillis = conditionCheckMillis;
        boolean listens = false;
        boolean conditions = false;
        for (int i = 0; i < script.handlers().size(); i++) {
            HandlerState h = new HandlerState(i, script.handlers().get(i));
            EventType type = h.event().type();
            listens |= type != EventType.ON_START;
            conditions |= type == EventType.ON_INVENTORY_FULL || type == EventType.ON_HEALTH_BELOW
                    || type == EventType.ON_ITEM_BELOW;
            if (h.event() instanceof Event.Every every) {
                h.nextFireAt = periodMillis(every);
            }
            handlers.add(h);
        }
        this.listening = listens;
        this.hasConditions = conditions;
    }

    /** Whether the script has handlers other than {@code on_start} (then it waits for events instead of finishing). */
    boolean listening() {
        return listening;
    }

    /** Starts {@code on_start}, and after a restore the {@code on_chat} handlers that were active. */
    void startInitial(RunSnapshot snapshot) {
        for (HandlerState h : handlers) {
            EventType type = h.event().type();
            boolean activeChat = snapshot != null && type == EventType.ON_CHAT
                    && snapshot.activeHandlers().contains(h.handler.path());
            if (type == EventType.ON_START || activeChat) {
                start(h);
            }
        }
    }

    boolean isChatTrigger(String typed) {
        return typed != null && chatHandler(typed.trim()) != null;
    }

    /** Starts the handler of a typed chat trigger unless it is already running. */
    boolean chat(String typed) {
        HandlerState h = typed == null ? null : chatHandler(typed.trim());
        if (h == null || h.running != null) {
            return false;
        }
        start(h);
        return true;
    }

    private HandlerState chatHandler(String text) {
        for (HandlerState h : handlers) {
            if (h.event() instanceof Event.OnChat c && c.trigger().trim().equalsIgnoreCase(text)) {
                return h;
            }
        }
        return null;
    }

    /** Starts due {@code every} handlers and runs the periodic condition check. {@code now} is the run clock. */
    void startDue(long now) {
        for (HandlerState h : handlers) {
            if (h.event() instanceof Event.Every every && now >= h.nextFireAt) {
                if (h.running == null) {
                    start(h);
                }
                long period = periodMillis(every);
                h.nextFireAt += ((now - h.nextFireAt) / period + 1) * period; // skip missed periods, no bursts
            }
        }
        if (hasConditions && now >= nextConditionCheck) {
            nextConditionCheck = now + conditionCheckMillis;
            checkConditions();
        }
    }

    /** Checks every condition event now; returns whether a handler started. */
    boolean checkConditions() {
        boolean started = false;
        for (HandlerState h : handlers) {
            String path = h.handler.path();
            Boolean holds = switch (h.event()) {
                case Event.OnInventoryFull ignored ->
                        ((Value.Bool) evaluator.query(new QueryCall.InventoryFull(), path)).value();
                case Event.OnHealthBelow e -> number(evaluator.query(new QueryCall.Health(), path)) < e.health();
                case Event.OnItemBelow e -> number(evaluator.query(
                        new QueryCall.ItemCount(Ids.normalize(e.item()), e.where()), path)) < e.count();
                default -> null;
            };
            if (holds == null) {
                continue;
            }
            if (!holds) {
                h.armed = true;
            } else if (h.armed && h.running == null) {
                h.armed = false;
                start(h);
                started = true;
            }
        }
        return started;
    }

    /** The activation ended; its handler may start again. */
    void ended(Activation a) {
        HandlerState h = handlers.get(a.handlerIndex);
        if (h.running == a) {
            h.running = null;
        }
    }

    /** The run ended: no handler is running any more. */
    void clear() {
        for (HandlerState h : handlers) {
            h.running = null;
        }
    }

    private void start(HandlerState h) {
        h.running = starter.start(h.index, h.handler);
    }

    private static long periodMillis(Event.Every every) {
        return Math.max(1, Math.round(every.seconds() * 1000));
    }

    private static double number(Value v) {
        return ((Value.Num) v).value();
    }
}
