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

package baritone.alto.integration.create;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.task.Job;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.ItemById;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Chat commands of the Create integration (registered only when Create is installed). */
final class CreateCommands {

    private CreateCommands() {
    }

    static void register(Alto alto) {
        IBaritone b = alto.baritone();
        var registry = b.getCommandManager().getRegistry();
        registry.register(new TrainCommand(b, alto));
        registry.register(new StationsCommand(b, alto));
        registry.register(new ElevatorCommand(b, alto));
        registry.register(new OrderCommand(b, alto));
    }

    private static void enabled() throws CommandException {
        if (!Baritone.settings().altoEnabled.value) {
            throw new CommandInvalidStateException("Alto is switched off (altoEnabled false)");
        }
    }

    static final class TrainCommand extends Command {
        private final Alto alto;

        TrainCommand(IBaritone baritone, Alto alto) {
            super(baritone, "train");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            enabled();
            if (!Baritone.settings().altoTrains.value) {
                throw new CommandInvalidStateException("Trains are switched off (altoTrains)");
            }
            String raw = args.rawRest().trim();
            while (args.hasAny()) {
                args.getString();
            }
            String[] parts = raw.contains("->") ? raw.split("->", 2) : raw.split("\\s+", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new CommandInvalidStateException("Usage: #train <from> -> <to> (or #train <from> <to> for one-word names)");
            }
            Optional<Map.Entry<String, BlockPos>> from = CreateTravel.station(ctx.world(), parts[0]);
            Optional<Map.Entry<String, BlockPos>> to = CreateTravel.station(ctx.world(), parts[1]);
            if (from.isEmpty() || to.isEmpty()) {
                throw new CommandInvalidStateException("Unknown station '" + (from.isEmpty() ? parts[0] : parts[1]).trim()
                        + "'. #stations lists them.");
            }
            alto.startJob(Job.of(new CreateTravel.TrainTask(alto, from.get().getKey(), from.get().getValue(),
                    to.get().getKey(), to.get().getValue()), "command"));
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) {
            return CreateTravel.stations(ctx.world()).keySet().stream();
        }

        @Override
        public String getShortDesc() {
            return "Ride a Create train between two named stations (Alto)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList(
                    "Walks to the first station, waits for a train to stop there, sits on a free passenger seat, rides,",
                    "and gets off when the train stops at the second station. Trains' schedules are not visible to",
                    "clients, so if the train returns to the start without stopping at the destination, Alto gets off",
                    "and tells you. Never touches train controls, conductors or schedules.",
                    "",
                    "Usage:",
                    "> train <from> -> <to>",
                    "> train <from> <to> - one-word station names",
                    "> stations - list the stations"
            );
        }
    }

    static final class StationsCommand extends Command {
        private final Alto alto;

        StationsCommand(IBaritone baritone, Alto alto) {
            super(baritone, "stations");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            Map<String, BlockPos> all = CreateTravel.stations(ctx.world());
            logDirect("Create train stations here: " + all.size());
            all.forEach((n, p) -> logDirect("  " + n + " at " + p.toShortString()));
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) {
            return Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "List Create train stations (Alto)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList("Usage:", "> stations");
        }
    }

    static final class ElevatorCommand extends Command {
        private final Alto alto;

        ElevatorCommand(IBaritone baritone, Alto alto) {
            super(baritone, "elevator");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            enabled();
            String floor = args.rawRest().trim();
            while (args.hasAny()) {
                args.getString();
            }
            if (floor.isEmpty()) {
                throw new CommandInvalidStateException("Usage: #elevator <floor name or y>");
            }
            alto.startJob(Job.of(new CreateTravel.ElevatorTask(alto, floor), "command"));
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) {
            return Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "Ride a Create elevator to a floor (Alto)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList("Stand in or next to the elevator cab. Alto looks at the cab's contraption controls, scrolls",
                    "to the floor (short name, long name or Y level) and right-clicks, then waits until the cab arrives.",
                    "", "Usage:", "> elevator <floor>");
        }
    }

    static final class OrderCommand extends Command {
        private final Alto alto;

        OrderCommand(IBaritone baritone, Alto alto) {
            super(baritone, "order");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            enabled();
            if (!Baritone.settings().altoStockKeeper.value) {
                throw new CommandInvalidStateException("Stock keeper ordering is switched off (altoStockKeeper)");
            }
            args.requireMin(2);
            Item item = args.getDatatypeFor(ItemById.INSTANCE);
            int count = args.getAs(Integer.class);
            if (count < 1 || count > 100_000) {
                throw new CommandInvalidStateException("count must be between 1 and 100000");
            }
            String address = args.hasAny() ? args.rawRest().trim() : null;
            while (args.hasAny()) {
                args.getString();
            }
            alto.startJob(Job.of(new CreateTravel.OrderTask(alto, item, count, address), "command"));
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
            return args.hasExactlyOne() ? args.tabCompleteDatatype(ItemById.INSTANCE) : Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "Order items from the nearest Create stock keeper (Alto)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList("Walks to the nearest stock keeper, right-clicks it, and orders the item through its screen",
                    "(only when the server lets you open it). The packages travel through your own network to the",
                    "address (default: the address last used at that keeper).", "",
                    "Usage:", "> order <item> <count> [address]");
        }
    }
}
