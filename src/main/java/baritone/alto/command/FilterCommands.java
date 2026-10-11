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

import baritone.alto.Alto;
import baritone.alto.core.ItemKey;
import baritone.alto.integration.FilterAccess;
import baritone.alto.mc.McItems;
import baritone.alto.task.AltoTask;
import baritone.alto.task.Job;
import baritone.alto.task.SequenceTask;
import baritone.alto.task.SetFilterTask;
import baritone.alto.task.UseItemOnBlockTask;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.ForDirection;
import baritone.api.command.datatypes.ItemById;
import baritone.api.command.datatypes.RelativeBlockPos;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.selection.ISelection;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * #filter (set a block's filter), #mark (blocks the picked item goes to), #pick (last picked item / EMI favorites),
 * #useon (use an item on a block).
 */
final class FilterCommands {

    private FilterCommands() {
    }

    /** Item argument: an id, "picked" (last picked item), "hand" (held item) or "clear". */
    static ItemKey itemArg(IArgConsumer args, Alto alto, Minecraft mc) throws CommandException {
        String peek = args.peekString().toLowerCase(Locale.ROOT);
        switch (peek) {
            case "picked" -> {
                args.getString();
                if (alto.picker().last().isEmpty()) {
                    throw new CommandInvalidStateException("Nothing picked yet (press the Alto pick key over an item)");
                }
                return alto.picker().last();
            }
            case "hand" -> {
                args.getString();
                ItemStack held = mc.player.getMainHandItem();
                return held.isEmpty() ? ItemKey.EMPTY : McItems.key(held.getItem());
            }
            case "clear", "none", "empty" -> {
                args.getString();
                return ItemKey.EMPTY;
            }
            default -> {
                return McItems.key(args.getDatatypeFor(ItemById.INSTANCE));
            }
        }
    }

    static final class FilterCommand extends AltoChatCommand {
        private final Alto alto;

        FilterCommand(IBaritone baritone, Alto alto) {
            super(baritone, "filter");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            AltoCommands.requireEnabled();
            args.requireMin(1);
            Minecraft mc = Minecraft.getInstance();
            ItemKey item = itemArg(args, alto, mc);
            Set<BlockPos> targets = new LinkedHashSet<>();
            Direction face = null;
            if (args.hasAny() && args.peekString().equalsIgnoreCase("marked")) {
                args.getString();
                targets.addAll(alto.picker().marked());
                alto.picker().clearMarks();
            } else if (args.hasAny() && args.peekString().equalsIgnoreCase("sel")) {
                args.getString();
                for (ISelection s : baritone.getSelectionManager().getSelections()) {
                    for (BlockPos p : BlockPos.betweenClosed(s.min(), s.max())) {
                        if (alto.filterAccess(mc.level, p) != null) {
                            targets.add(p.immutable());
                        }
                    }
                }
            } else if (args.hasAny()) {
                BetterBlockPos pos = args.getDatatypePost(RelativeBlockPos.INSTANCE, ctx.playerFeet());
                targets.add(pos);
                if (args.hasAny()) {
                    face = args.getDatatypeFor(ForDirection.INSTANCE);
                }
            } else if (!alto.picker().marked().isEmpty()) {
                targets.addAll(alto.picker().marked());
                alto.picker().clearMarks();
            } else {
                HitResult hit = mc.hitResult;
                if (!(hit instanceof BlockHitResult b) || hit.getType() != HitResult.Type.BLOCK) {
                    throw new CommandInvalidStateException("Look at the block, give coordinates, or mark blocks first");
                }
                targets.add(b.getBlockPos().immutable());
                face = b.getDirection();
                FilterAccess access = alto.filterAccess(mc.level, b.getBlockPos());
                if (access != null && access.slots(mc.level, b.getBlockPos()).stream().noneMatch(s -> s.face() == b.getDirection())) {
                    face = null; // looking at a face without a slot: use any slot
                }
            }
            if (targets.isEmpty()) {
                throw new CommandInvalidStateException("No blocks with filter slots found");
            }
            List<AltoTask> steps = new ArrayList<>();
            for (BlockPos p : targets) {
                steps.add(new SetFilterTask(alto, p, item, face));
            }
            alto.startJob(Job.of(new SequenceTask(alto, (item.isEmpty() ? "Clear filters" : "Set filters to " + item.shortName())
                    + " (" + targets.size() + " block" + (targets.size() == 1 ? "" : "s") + ")", steps), "command"));
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
            if (args.hasExactlyOne()) {
                return Stream.concat(Stream.of("picked", "hand", "clear"), args.tabCompleteDatatype(ItemById.INSTANCE));
            }
            args.getString();
            if (args.hasExactlyOne()) {
                return Stream.of("marked", "sel", "~");
            }
            return Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "Set the filter of a block (Create) to an item (Alto)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList(
                    "Walks to a block with a filter slot (Create deployer, brass funnel/tunnel, smart chute...), holds the item,",
                    "looks exactly at the slot and right-clicks it. Plain items are not used up. If the item is not carried, Alto",
                    "fetches or crafts it first. 'clear' uses an empty hand, which clears the filter.",
                    "",
                    "Usage:",
                    "> filter <item|picked|hand|clear> - the block you look at (or the marked blocks)",
                    "> filter <item> <x> <y> <z> [face]",
                    "> filter <item> marked - all blocks marked with the pick key / #mark",
                    "> filter <item> sel - every block with a filter slot in your #sel selection"
            );
        }
    }

    static final class MarkCommand extends AltoChatCommand {
        private final Alto alto;

        MarkCommand(IBaritone baritone, Alto alto) {
            super(baritone, "mark");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            String op = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "";
            switch (op) {
                case "clear" -> {
                    alto.picker().clearMarks();
                    logDirect("Marks cleared");
                }
                case "list" -> logDirect("Marked: " + alto.picker().marked().stream().map(BlockPos::toShortString).toList());
                default -> alto.picker().toggleMarkAtCrosshair();
            }
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) {
            return args.hasExactlyOne() ? Stream.of("list", "clear") : Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "Mark the block you look at as a target for the picked item (Alto)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList("Marks/unmarks the block under your crosshair (the Alto pick key does the same).",
                    "Then pick an item in EMI or your inventory with the pick key to set it as their filter.", "",
                    "Usage:", "> mark", "> mark list", "> mark clear");
        }
    }

    static final class PickCommand extends AltoChatCommand {
        private final Alto alto;

        PickCommand(IBaritone baritone, Alto alto) {
            super(baritone, "pick");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            String op = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "";
            if (op.equals("favorites")) {
                List<ItemStack> favs = new ArrayList<>();
                for (var p : alto.picker().providers()) {
                    favs.addAll(p.favorites());
                }
                if (favs.isEmpty()) {
                    logDirect("No EMI favorites found (is EMI installed?)");
                }
                for (ItemStack s : favs) {
                    alto.picker().pick(s);
                }
                return;
            }
            if (op.isEmpty()) {
                logDirect(alto.picker().last().isEmpty() ? "Nothing picked yet" : "Picked: " + alto.picker().last());
                return;
            }
            alto.picker().pick(new ItemStack(McItems.item(ItemKey.of(op))));
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
            return args.hasExactlyOne() ? Stream.concat(Stream.of("favorites"), args.tabCompleteDatatype(ItemById.INSTANCE)) : Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "Show the picked item, or pick from EMI favorites (Alto)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList("The Alto pick key picks the item under the mouse in EMI or any inventory screen.", "",
                    "Usage:", "> pick - show the last picked item", "> pick favorites - list EMI favorites with actions",
                    "> pick <item> - pick an item by id");
        }
    }

    static final class UseOnCommand extends AltoChatCommand {
        private final Alto alto;

        UseOnCommand(IBaritone baritone, Alto alto) {
            super(baritone, "useon");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            AltoCommands.requireEnabled();
            args.requireMin(4);
            Minecraft mc = Minecraft.getInstance();
            ItemKey item = itemArg(args, alto, mc);
            BetterBlockPos pos = args.getDatatypePost(RelativeBlockPos.INSTANCE, ctx.playerFeet());
            Direction face = args.hasAny() ? args.getDatatypeFor(ForDirection.INSTANCE) : Direction.UP;
            alto.startJob(Job.of(new UseItemOnBlockTask(alto, pos, face, item), "command"));
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
            return args.hasExactlyOne() ? args.tabCompleteDatatype(ItemById.INSTANCE) : Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "Use an item on a block face, e.g. put an item on a Create depot (Alto)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList("Walks to the block, holds the item, looks at the face and right-clicks once.", "",
                    "Usage:", "> useon <item|hand|picked> <x> <y> <z> [face]");
        }
    }
}
