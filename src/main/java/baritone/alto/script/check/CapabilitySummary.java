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

import baritone.alto.script.lang.ActionArg;
import baritone.alto.script.lang.ActionType;
import baritone.alto.script.lang.Event;
import baritone.alto.script.lang.EventType;
import baritone.alto.script.lang.Expr;
import baritone.alto.script.lang.Script;
import baritone.alto.script.lang.Stmt;
import baritone.alto.script.lang.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Static summary of what a script may do, for the first-run confirmation screen. Conservative: every block in the
 * file counts, including branches that may never run and procedures that are never called.
 *
 * @param capabilities what the script may do (enum order)
 * @param sources      for each capability, the block or event names that cause it (sorted)
 * @param commands     literal Baritone commands, normalized ({@code #} prefix removed), in file order
 * @param chatTexts    literal {@code send_chat} texts in file order; {@link #COMPUTED_TEXT} for computed ones
 * @param chatTriggers {@code on_chat} triggers in file order
 */
public record CapabilitySummary(Set<Capability> capabilities, Map<Capability, List<String>> sources,
                                List<String> commands, List<String> chatTexts, List<String> chatTriggers) {

    /** Placeholder in {@link #chatTexts()} for a text that is only known while the script runs. */
    public static final String COMPUTED_TEXT = "(text computed while the script runs)";

    public CapabilitySummary {
        capabilities = Collections.unmodifiableSet(capabilities.isEmpty()
                ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(capabilities));
        Map<Capability, List<String>> copy = new EnumMap<>(Capability.class);
        sources.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        sources = Collections.unmodifiableMap(copy);
        commands = List.copyOf(commands);
        chatTexts = List.copyOf(chatTexts);
        chatTriggers = List.copyOf(chatTriggers);
    }

    public static CapabilitySummary of(Script script) {
        Collector c = new Collector();
        for (Script.Procedure p : script.procedures()) {
            c.body(p.body());
        }
        for (Script.EventHandler h : script.handlers()) {
            if (h.event().type() != EventType.ON_START) {
                c.add(Capability.RUNS_UNTIL_STOPPED, h.event().type().jsonName());
            }
            if (h.event() instanceof Event.OnChat chat) {
                c.add(Capability.LISTENS_TO_CHAT, h.event().type().jsonName());
                c.triggers.add(chat.trigger().trim());
            }
            c.body(h.body());
        }
        Map<Capability, List<String>> sources = new EnumMap<>(Capability.class);
        c.sources.forEach((k, v) -> sources.put(k, new ArrayList<>(v)));
        return new CapabilitySummary(c.sources.keySet(), sources, new ArrayList<>(c.commands),
                new ArrayList<>(c.chatTexts), new ArrayList<>(c.triggers));
    }

    public boolean has(Capability capability) {
        return capabilities.contains(capability);
    }

    /**
     * One line per capability, e.g. {@code Breaks blocks (fill, mine).} or
     * {@code Runs Baritone commands: mine diamond_ore.}
     */
    public List<String> sentences() {
        List<String> lines = new ArrayList<>();
        for (Capability c : capabilities) {
            String text = Character.toUpperCase(c.sentence().charAt(0)) + c.sentence().substring(1);
            List<String> details = switch (c) {
                case RUNS_COMMANDS -> commands;
                case SENDS_CHAT -> quoted(chatTexts);
                case LISTENS_TO_CHAT -> chatTriggers;
                default -> null;
            };
            if (details != null) {
                lines.add(text + ": " + String.join(", ", details) + ".");
            } else {
                lines.add(text + " (" + String.join(", ", sources.getOrDefault(c, List.of())) + ").");
            }
        }
        return lines;
    }

    private static List<String> quoted(List<String> texts) {
        List<String> out = new ArrayList<>();
        for (String t : texts) {
            out.add(t.equals(COMPUTED_TEXT) ? t : "\"" + t + "\"");
        }
        return out;
    }

    /** Walks all statements and collects capabilities. */
    private static final class Collector {
        final Map<Capability, Set<String>> sources = new EnumMap<>(Capability.class);
        final Set<String> commands = new LinkedHashSet<>();
        final Set<String> chatTexts = new LinkedHashSet<>();
        final Set<String> triggers = new LinkedHashSet<>();

        void add(Capability c, String source) {
            sources.computeIfAbsent(c, k -> new TreeSet<>()).add(source);
        }

        void body(List<Stmt> body) {
            for (Stmt s : body) {
                statement(s);
            }
        }

        void statement(Stmt s) {
            switch (s) {
                case Stmt.Action a -> action(a);
                case Stmt.Repeat r -> body(r.body());
                case Stmt.RepeatUntil r -> body(r.body());
                case Stmt.Forever f -> {
                    add(Capability.RUNS_UNTIL_STOPPED, "forever");
                    body(f.body());
                }
                case Stmt.If i -> {
                    body(i.then());
                    body(i.otherwise());
                }
                default -> {
                }
            }
        }

        void action(Stmt.Action a) {
            for (Capability c : Capability.of(a.type())) {
                add(c, a.type().jsonName());
            }
            if (a.type() == ActionType.COMMAND && a.args().get("command") instanceof ActionArg.TextArg t) {
                commands.add(ActionType.normalizeCommand(t.text()));
            }
            if (a.type() == ActionType.SEND_CHAT) {
                Expr text = a.expr("text");
                chatTexts.add(text instanceof Expr.Literal l && l.value() instanceof Value.Str str
                        ? str.value() : COMPUTED_TEXT);
            }
        }
    }
}
