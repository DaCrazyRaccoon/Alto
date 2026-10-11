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
import baritone.alto.task.Job;
import baritone.alto.travel.PortalBook;
import baritone.alto.travel.TravelTask;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.argument.ICommandArgument;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.exception.CommandInvalidTypeException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** #travel and the dimension form of #goto (feature 6), plus #portals. */
public final class TravelCommands {

    private TravelCommands() {
    }

    /**
     * Hook for upstream #goto: {@code goto <x> [y] <z> <dimension>}. Returns false (and consumes nothing) when the
     * last argument is not a dimension, so #goto behaves exactly as before.
     */
    public static boolean tryGoto(IBaritone baritone, IArgConsumer args) throws CommandException {
        List<ICommandArgument> all = args.getArgs();
        if (all.size() < 3 || all.size() > 4 || PortalBook.parseDimension(all.get(all.size() - 1).getValue()) == null) {
            return false;
        }
        Alto alto = ((Baritone) baritone).getAlto();
        AltoCommands.requireEnabled();
        start(alto, parse(new ArrayList<>(all)));
        while (args.hasAny()) {
            args.getString();
        }
        return true;
    }

    private record Target(String dim, int x, Integer y, int z) {
    }

    /** {@code <x> [y] <z> <dimension>} */
    private static Target parse(List<ICommandArgument> a) throws CommandException {
        String dim = PortalBook.parseDimension(a.get(a.size() - 1).getValue());
        try {
            int x = Integer.parseInt(a.get(0).getValue());
            Integer y = a.size() == 4 ? Integer.parseInt(a.get(1).getValue()) : null;
            int z = Integer.parseInt(a.get(a.size() - 2).getValue());
            return new Target(dim, x, y, z);
        } catch (NumberFormatException e) {
            throw new CommandInvalidTypeException(a.get(0), "whole-number coordinates (no ~ when changing dimension)");
        }
    }

    private static void start(Alto alto, Target t) throws CommandException {
        if (!Baritone.settings().altoPortals.value) {
            throw new CommandInvalidStateException("Portal travel is switched off (altoPortals)");
        }
        alto.startJob(Job.of(new TravelTask(alto, t.dim(), t.x(), t.y(), t.z()), "command"));
    }

    static final class TravelCommand extends Command {
        private final Alto alto;

        TravelCommand(IBaritone baritone, Alto alto) {
            super(baritone, "travel");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            AltoCommands.requireEnabled();
            args.requireMin(3);
            args.requireMax(4);
            List<ICommandArgument> a = new ArrayList<>(args.getArgs());
            // #travel <dimension> <x> [y] <z>  ->  same as #goto <x> [y] <z> <dimension>
            ICommandArgument dim = a.remove(0);
            if (PortalBook.parseDimension(dim.getValue()) == null) {
                throw new CommandInvalidTypeException(dim, "overworld or nether");
            }
            a.add(dim);
            start(alto, parse(a));
            while (args.hasAny()) {
                args.getString();
            }
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) {
            return args.hasExactlyOne() ? Stream.of("overworld", "nether") : Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "Travel to coordinates in another dimension through nether portals (Alto)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList(
                    "Walks to the best nether portal Alto knows, goes through it and walks on to the target.",
                    "Alto learns portals you come near and remembers where each portal led when you used it;",
                    "without that, it estimates with the 8:1 nether scale. #goto x y z nether does the same.",
                    "",
                    "Usage:",
                    "> travel <overworld|nether> <x> [y] <z>",
                    "> goto <x> [y] <z> <overworld|nether>",
                    "> portals - list the portals Alto knows in this world"
            );
        }
    }

    static final class MinecartCommand extends Command {
        private final Alto alto;

        MinecartCommand(IBaritone baritone, Alto alto) {
            super(baritone, "minecart");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            AltoCommands.requireEnabled();
            vehiclesOn();
            args.requireMax(2);
            Integer x = null;
            Integer z = null;
            if (args.hasAny()) {
                args.requireExactly(2);
                x = args.getAs(Integer.class);
                z = args.getAs(Integer.class);
            }
            alto.startJob(Job.of(new baritone.alto.travel.VehicleTasks.MinecartTask(alto, x, z), "command"));
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) {
            return Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "Ride the nearest free minecart (Alto)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList("Boards the nearest free minecart within 16 blocks, rides it (holding forward like a player),",
                    "and gets off when it stops for 3 seconds or comes within 3 blocks of x z, then walks the rest.", "",
                    "Usage:", "> minecart", "> minecart <x> <z>");
        }
    }

    static final class BoatCommand extends Command {
        private final Alto alto;

        BoatCommand(IBaritone baritone, Alto alto) {
            super(baritone, "boat");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            AltoCommands.requireEnabled();
            vehiclesOn();
            args.requireExactly(2);
            int x = args.getAs(Integer.class);
            int z = args.getAs(Integer.class);
            alto.startJob(Job.of(new baritone.alto.travel.VehicleTasks.BoatTask(alto, x, z), "command"));
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) {
            return Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "Go by boat in a straight line towards x z (Alto)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList("Uses an empty boat nearby, or places one from your hotbar on water within 8 blocks, steers",
                    "straight towards x z, gets off at the target or the shore, picks a placed boat up again and walks",
                    "the rest. No path finding on water: plan routes over open water.", "",
                    "Usage:", "> boat <x> <z>");
        }
    }

    private static void vehiclesOn() throws CommandException {
        if (!Baritone.settings().altoVehicles.value) {
            throw new CommandInvalidStateException("Vehicles are switched off (altoVehicles)");
        }
    }

    static final class PortalsCommand extends Command {
        private final Alto alto;

        PortalsCommand(IBaritone baritone, Alto alto) {
            super(baritone, "portals");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            args.requireMax(1);
            if (args.hasAny() && args.getString().equalsIgnoreCase("forget")) {
                for (PortalBook.Pos p : alto.portals().book().portals()) {
                    alto.portals().book().forget(p);
                }
                alto.portals().save();
                logDirect("Forgot all portals in this world.");
                return;
            }
            PortalBook b = alto.portals().book();
            logDirect("Known portals: " + b.portals().size());
            for (PortalBook.Pos p : b.portals()) {
                String to = b.linkFrom(p).map(l -> " -> " + l.to()).orElse("");
                logDirect("  " + p + to);
            }
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) {
            return args.hasExactlyOne() ? Stream.of("forget") : Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "List the nether portals Alto knows (Alto)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList("Usage:", "> portals", "> portals forget - forget all portals of this world");
        }
    }
}
