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

package baritone.alto.command;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.alto.core.ItemCounts;
import baritone.alto.integration.Integrations;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import baritone.alto.planner.Plan;
import baritone.alto.task.Job;
import baritone.alto.task.PlanContext;
import baritone.alto.task.PlanTask;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.ICommand;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.ItemById;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Registers Alto's chat commands with Baritone's command system (same prefix, help and tab completion as every
 * Baritone command). Later milestones add more commands here.
 */
public final class AltoCommands {

    private AltoCommands() {
    }

    public static void registerAll(Baritone baritone, Alto alto) {
        List<ICommand> commands = new ArrayList<>();
        commands.add(new CraftCommand(baritone, alto));
        commands.add(new PlanCommand(baritone, alto));
        commands.add(new AltoCommand(baritone, alto));
        commands.add(new SelfTestCommand(baritone, alto));
        commands.add(new StorageCommands.HomeCommand(baritone, alto));
        commands.add(new StorageCommands.FetchCommand(baritone, alto));
        commands.add(new StorageCommands.DepositCommand(baritone, alto));
        commands.add(new StorageCommands.ShulkerCommand(baritone, alto));
        commands.add(new StorageCommands.ExportRegistryCommand(baritone, alto));
        commands.add(new FilterCommands.FilterCommand(baritone, alto));
        commands.add(new FilterCommands.MarkCommand(baritone, alto));
        commands.add(new FilterCommands.PickCommand(baritone, alto));
        commands.add(new FilterCommands.UseOnCommand(baritone, alto));
        commands.forEach(baritone.getCommandManager().getRegistry()::register);
    }

    static void requireEnabled() throws CommandException {
        if (!Baritone.settings().altoEnabled.value) {
            throw new CommandInvalidStateException("Alto is switched off. Use #set altoEnabled true");
        }
    }

    /** #craft <item> [count] — craft (planning the whole chain) until you have count more than now. */
    static final class CraftCommand extends Command {
        private final Alto alto;

        CraftCommand(IBaritone baritone, Alto alto) {
            super(baritone, "craft");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            requireEnabled();
            args.requireMin(1);
            args.requireMax(2);
            Item item = args.getDatatypeFor(ItemById.INSTANCE);
            int count = args.hasAny() ? args.getAs(Integer.class) : 1;
            if (count < 1 || count > 100_000) {
                throw new CommandInvalidStateException("count must be between 1 and 100000");
            }
            ItemKey key = McItems.key(item);
            int have = McItems.count(ctx.player().getInventory(), item);
            ItemCounts target = ItemCounts.of(key, have + count);
            Plan plan = alto.plan(target, new ItemCounts());
            if (!plan.isComplete()) {
                plan.describe().forEach(this::logDirect);
                alto.hintsFor(plan.missing()).forEach(this::logDirect);
                throw new CommandInvalidStateException("Cannot get " + count + "x " + key.shortName() + ". Missing: " + plan.missing());
            }
            plan.describe().forEach(this::logDirect);
            alto.startJob(Job.of(new PlanTask(alto, plan, new PlanContext(new ItemCounts(), p -> false), target), "command"));
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
            if (args.hasExactlyOne()) {
                return args.tabCompleteDatatype(ItemById.INSTANCE);
            }
            return Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "Craft an item, planning the whole chain (Alto)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList(
                    "Plans and crafts an item: uses what you have, then known storage, then crafting/smelting, then mining.",
                    "2x2 recipes are crafted in your inventory unless alwaysUseCraftingStation is true; 3x3 recipes use a",
                    "crafting table (found nearby, or placed and picked up again).",
                    "",
                    "Usage:",
                    "> craft <item> [count] - craft count (default 1) more of item",
                    "> plan <item> [count] - only show the plan"
            );
        }
    }

    /** #plan <item> [count] — show the plan only. */
    static final class PlanCommand extends Command {
        private final Alto alto;

        PlanCommand(IBaritone baritone, Alto alto) {
            super(baritone, "plan");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            requireEnabled();
            args.requireMin(1);
            args.requireMax(2);
            Item item = args.getDatatypeFor(ItemById.INSTANCE);
            int count = args.hasAny() ? args.getAs(Integer.class) : 1;
            ItemKey key = McItems.key(item);
            int have = McItems.count(ctx.player().getInventory(), item);
            Plan plan = alto.plan(ItemCounts.of(key, have + count), new ItemCounts());
            plan.describe().forEach(this::logDirect);
            alto.hintsFor(plan.missing()).forEach(this::logDirect);
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
            if (args.hasExactlyOne()) {
                return args.tabCompleteDatatype(ItemById.INSTANCE);
            }
            return Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "Show how Alto would get an item";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList(
                    "Shows the plan Alto would follow to get count more of an item, with time estimate and missing items.",
                    "",
                    "Usage:",
                    "> plan <item> [count]"
            );
        }
    }

    /** #alto [status|stop|help] */
    static final class AltoCommand extends Command {
        private final Alto alto;

        AltoCommand(IBaritone baritone, Alto alto) {
            super(baritone, "alto");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            String sub = args.hasAny() ? args.getString().toLowerCase(java.util.Locale.ROOT) : "status";
            switch (sub) {
                case "status":
                    alto.status().forEach(this::logDirect);
                    break;
                case "stop":
                    alto.process().cancelJob("stopped with #alto stop");
                    break;
                case "hazards":
                    logDirect("Hazard rules: " + alto.hazards().reload());
                    break;
                case "integrations":
                    logDirect("Active integrations: " + Integrations.active());
                    if (!Integrations.failed().isEmpty()) {
                        logDirect("Disabled after an error: " + Integrations.failed());
                    }
                    break;
                default:
                    getLongDesc().forEach(this::logDirect);
            }
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) {
            return args.hasExactlyOne() ? Stream.of("status", "stop", "hazards", "integrations", "help") : Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "Alto fork status and control";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList(
                    "Alto adds container automation, crafting, smelting, storage and more to Baritone.",
                    "Settings start with 'alto' (#set altoEnabled false switches everything off).",
                    "",
                    "Usage:",
                    "> alto status - what Alto is doing, server lag, pacing",
                    "> alto stop - stop the current Alto job (#stop stops everything)",
                    "> alto hazards - reload baritone/alto/hazards.json",
                    "> alto integrations - which mod integrations are active"
            );
        }
    }

    /** #selftest [list|all|<name>] [confirm] */
    static final class SelfTestCommand extends Command {
        private final Alto alto;

        SelfTestCommand(IBaritone baritone, Alto alto) {
            super(baritone, "selftest");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            if (!args.hasAny()) {
                getLongDesc().forEach(this::logDirect);
                logDirect("Scenarios: " + String.join(", ", alto.selfTests().names()));
                return;
            }
            String what = args.getString();
            boolean confirm = args.hasAny() && args.getString().equalsIgnoreCase("confirm");
            if (what.equalsIgnoreCase("list")) {
                logDirect("Scenarios: " + String.join(", ", alto.selfTests().names()));
                return;
            }
            String problem = alto.selfTests().start(what, confirm);
            if (problem != null) {
                throw new CommandInvalidStateException(problem);
            }
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) {
            if (args.hasExactlyOne()) {
                List<String> opts = new ArrayList<>(alto.selfTests().names());
                opts.add("all");
                opts.add("list");
                return opts.stream();
            }
            return Stream.of("confirm");
        }

        @Override
        public String getShortDesc() {
            return "Run Alto's in-game self-tests (test worlds only)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList(
                    "Runs named in-game scenarios and prints PASS or FAIL for each. The scenarios build their own",
                    "setup around you (they place and remove blocks and change your inventory), so they only run in",
                    "singleplayer, and only in worlds whose name starts with alto_test unless you add 'confirm'.",
                    "Results are also written to baritone/alto/selftest/.",
                    "",
                    "Usage:",
                    "> selftest list",
                    "> selftest <name> [confirm]",
                    "> selftest all [confirm]"
            );
        }
    }

    static void log(String s) {
        AltoLog.chat(s);
    }
}
