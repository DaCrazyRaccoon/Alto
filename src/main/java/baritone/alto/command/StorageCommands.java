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
import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import baritone.alto.storage.ContainerRecord;
import baritone.alto.storage.DepositTask;
import baritone.alto.storage.FetchTask;
import baritone.alto.storage.KeepList;
import baritone.alto.storage.StorageIndex;
import baritone.alto.storage.Zones;
import baritone.alto.storage.ZoneScanTask;
import baritone.alto.task.ShulkerTask;
import baritone.alto.mc.RegistryExport;
import baritone.alto.task.AltoTask;
import baritone.alto.task.Job;
import baritone.alto.task.SequenceTask;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.ItemById;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.selection.ISelection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * #home (homes and storage zones; plain #home still walks to your home like upstream), #fetch and #deposit.
 */
final class StorageCommands {

    private StorageCommands() {
    }

    static final class HomeCommand extends AltoChatCommand {
        private final Alto alto;

        HomeCommand(IBaritone baritone, Alto alto) {
            super(baritone, "home");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            if (!args.hasAny()) {
                // Same as upstream: walk to the "home" waypoint (#sethome / #home set).
                baritone.getCommandManager().execute("waypoints goto home");
                return;
            }
            String sub = args.getString().toLowerCase(Locale.ROOT);
            Zones zones = alto.storage().zones();
            String dim = alto.storage().dimension();
            BlockPos feet = ctx.playerFeet();
            switch (sub) {
                case "set": {
                    String name = args.hasAny() ? args.getString() : "home";
                    zones.setHome(new Zones.Home(name, dim, feet.getX(), feet.getY(), feet.getZ()));
                    alto.storage().zonesChanged();
                    if (name.equalsIgnoreCase("home")) {
                        baritone.getCommandManager().execute("waypoints save home");
                    }
                    logDirect("Home '" + name + "' set to " + feet.toShortString() + " in " + dim);
                    return;
                }
                case "clear": {
                    String name = args.hasAny() ? args.getString() : "home";
                    logDirect(zones.clearHome(name) ? "Home '" + name + "' removed" : "No home named '" + name + "'");
                    alto.storage().zonesChanged();
                    return;
                }
                case "list": {
                    if (zones.homes().isEmpty() && zones.zones().isEmpty()) {
                        logDirect("No homes or storage zones yet. #home set, #home zone add <name>");
                    }
                    for (Zones.Home h : zones.homes()) {
                        logDirect("home " + h.name() + ": " + h.x() + " " + h.y() + " " + h.z() + " (" + h.dimension() + ")");
                    }
                    for (Zones.Zone z : zones.zones()) {
                        logDirect("zone " + z.describe());
                    }
                    return;
                }
                case "goto": {
                    String name = args.hasAny() ? args.getString() : "home";
                    Zones.Home h = zones.home(name).orElseThrow(() -> new CommandInvalidStateException("No home named '" + name + "'"));
                    if (!h.dimension().equals(dim)) {
                        throw new CommandInvalidStateException("Home '" + name + "' is in " + h.dimension());
                    }
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(h.x(), h.y(), h.z()));
                    return;
                }
                case "zone":
                    zone(args, zones, dim);
                    return;
                case "storage":
                    storage(args);
                    return;
                default:
                    getLongDesc().forEach(this::logDirect);
            }
        }

        private void zone(IArgConsumer args, Zones zones, String dim) throws CommandException {
            String op = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "list";
            switch (op) {
                case "add": {
                    String name = args.getString();
                    int[] box;
                    if (args.has(6)) {
                        box = new int[6];
                        for (int i = 0; i < 6; i++) {
                            box[i] = args.getAs(Integer.class);
                        }
                    } else {
                        ISelection[] sels = baritone.getSelectionManager().getSelections();
                        if (sels.length == 0) {
                            throw new CommandInvalidStateException("Give two corners (x1 y1 z1 x2 y2 z2) or make a selection with #sel first");
                        }
                        ISelection s = sels[sels.length - 1];
                        box = new int[]{s.min().x, s.min().y, s.min().z, s.max().x, s.max().y, s.max().z};
                    }
                    Zones.Zone z = new Zones.Zone(name, dim, Math.min(box[0], box[3]), Math.min(box[1], box[4]), Math.min(box[2], box[5]),
                            Math.max(box[0], box[3]), Math.max(box[1], box[4]), Math.max(box[2], box[5]), List.of());
                    zones.putZone(z);
                    alto.storage().zonesChanged();
                    logDirect("Storage zone " + z.describe() + " saved. Open its chests once (or let Alto) so they are indexed.");
                    return;
                }
                case "remove": {
                    String name = args.getString();
                    logDirect(zones.removeZone(name) ? "Zone '" + name + "' removed" : "No zone named '" + name + "'");
                    alto.storage().zonesChanged();
                    return;
                }
                case "scan": {
                    String name = args.getString();
                    Zones.Zone z = zones.zone(name).orElseThrow(() -> new CommandInvalidStateException("No zone named '" + name + "'"));
                    alto.startJob(Job.of(new ZoneScanTask(alto, z), "command"));
                    return;
                }
                case "rules": {
                    String name = args.getString();
                    Zones.Zone z = zones.zone(name).orElseThrow(() -> new CommandInvalidStateException("No zone named '" + name + "'"));
                    List<String> rules = new ArrayList<>();
                    while (args.hasAny()) {
                        Item item = args.getDatatypeFor(ItemById.INSTANCE);
                        rules.add(McItems.key(item).id());
                    }
                    zones.putZone(z.withRules(rules));
                    alto.storage().zonesChanged();
                    logDirect(rules.isEmpty() ? "Zone '" + name + "' accepts everything" : "Zone '" + name + "' accepts only " + rules);
                    return;
                }
                default:
                    for (Zones.Zone z : zones.zones()) {
                        logDirect("zone " + z.describe());
                    }
            }
        }

        private void storage(IArgConsumer args) throws CommandException {
            StorageIndex index = alto.storage().index();
            if (args.hasAny()) {
                ItemKey item = McItems.key(args.getDatatypeFor(ItemById.INSTANCE));
                List<ContainerRecord> found = index.containing(item, alto.storage().dimension(), ctx.player().getX(), ctx.player().getY(),
                        ctx.player().getZ(), r -> true);
                if (found.isEmpty()) {
                    logDirect("No known container holds " + item.shortName());
                }
                for (ContainerRecord r : found) {
                    logDirect(r.contents.get(item) + "x in " + r.describe() + (alto.storage().usable().test(r) ? "" : " (outside your zones)"));
                }
                return;
            }
            logDirect(index.size() + " containers known in this world. Totals in your zones:");
            ItemCounts totals = index.totals(alto.storage().usable());
            logDirect(totals.isEmpty() ? "(nothing)" : totals.toString());
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
            if (args.hasExactlyOne()) {
                return Stream.of("set", "clear", "list", "goto", "zone", "storage");
            }
            String sub = args.getString();
            if (sub.equalsIgnoreCase("zone") && args.hasExactlyOne()) {
                return Stream.of("add", "remove", "rules", "scan", "list");
            }
            if (sub.equalsIgnoreCase("storage") && args.hasExactlyOne()) {
                return args.tabCompleteDatatype(ItemById.INSTANCE);
            }
            return Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "Homes and storage zones (Alto); #home alone walks home";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList(
                    "Homes and storage zones. Alto only takes from / puts into containers inside storage zones.",
                    "",
                    "Usage:",
                    "> home - walk to your home waypoint (as before)",
                    "> home set [name] / clear [name] / goto [name] / list",
                    "> home zone add <name> [x1 y1 z1 x2 y2 z2] - zone from corners or the current #sel selection",
                    "> home zone remove <name>",
                    "> home zone rules <name> [item ...] - only these items are deposited there (none = everything)",
                    "> home zone scan <name> - open every container in the zone once so Alto knows what is inside",
                    "> home storage [item] - what is stored where"
            );
        }
    }

    static final class FetchCommand extends AltoChatCommand {
        private final Alto alto;

        FetchCommand(IBaritone baritone, Alto alto) {
            super(baritone, "fetch");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            AltoCommands.requireEnabled();
            args.requireMin(1);
            args.requireMax(2);
            Item item = args.getDatatypeFor(ItemById.INSTANCE);
            int count = args.hasAny() ? args.getAs(Integer.class) : 64;
            ItemKey key = McItems.key(item);
            List<ContainerRecord> found = alto.storage().index().containing(key, alto.storage().dimension(), ctx.player().getX(),
                    ctx.player().getY(), ctx.player().getZ(), alto.storage().usable());
            if (found.isEmpty()) {
                throw new CommandInvalidStateException("No known container in your storage zones holds " + key.shortName()
                        + " (see #home storage " + key.shortName() + ")");
            }
            List<AltoTask> steps = new ArrayList<>();
            int left = count;
            for (ContainerRecord r : found) {
                if (left <= 0) {
                    break;
                }
                int take = Math.min(left, r.contents.get(key));
                steps.add(new FetchTask(alto, r.key, key, take).exact()); // the count the user typed
                left -= take;
            }
            if (left > 0) {
                logDirect("Only " + (count - left) + " are known in storage; fetching those.");
            }
            alto.startJob(Job.of(new SequenceTask(alto, "Fetch " + (count - left) + "x " + key.shortName(), steps), "command"));
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
            return args.hasExactlyOne() ? args.tabCompleteDatatype(ItemById.INSTANCE) : Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "Fetch items from your storage zones (Alto)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList("Takes items from the known containers in your storage zones, nearest first.", "",
                    "Usage:", "> fetch <item> [count] - default 64");
        }
    }

    static final class DepositCommand extends AltoChatCommand {
        private final Alto alto;

        DepositCommand(IBaritone baritone, Alto alto) {
            super(baritone, "deposit");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            AltoCommands.requireEnabled();
            args.requireMax(0);
            if (alto.storage().zones().isEmpty() && !Alto.settings().altoStorageAnywhere.value) {
                throw new CommandInvalidStateException("Set a storage zone first: #home zone add <name>");
            }
            alto.startJob(Job.of(new DepositTask(alto, KeepList.keep(new ItemCounts())), "command"));
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) {
            return Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "Put everything except the keep-list into storage (Alto)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList("Deposits everything except the keep-list (altoKeepItems, tools, food) into your storage zones.",
                    "Zones with rules only receive matching items.", "", "Usage:", "> deposit");
        }
    }

    static final class ShulkerCommand extends AltoChatCommand {
        private final Alto alto;

        ShulkerCommand(IBaritone baritone, Alto alto) {
            super(baritone, "shulker");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            AltoCommands.requireEnabled();
            String op = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "";
            switch (op) {
                case "unpack": {
                    Item item = args.getDatatypeFor(ItemById.INSTANCE);
                    int count = args.hasAny() ? args.getAs(Integer.class) : 64;
                    ItemKey key = McItems.key(item);
                    alto.startJob(Job.of(new ShulkerTask(alto, ShulkerTask.Mode.UNPACK, key::equals, key.shortName(), count, p -> false), "command"));
                    return;
                }
                case "pack": {
                    java.util.function.Predicate<ItemKey> keep = KeepList.keep(new ItemCounts());
                    java.util.function.Predicate<ItemKey> match;
                    String what;
                    if (args.hasAny()) {
                        ItemKey key = McItems.key(args.getDatatypeFor(ItemById.INSTANCE));
                        match = key::equals;
                        what = key.shortName();
                    } else {
                        match = k -> !keep.test(k);
                        what = "everything except the keep-list";
                    }
                    alto.startJob(Job.of(new ShulkerTask(alto, ShulkerTask.Mode.PACK, match, what, -1, p -> false), "command"));
                    return;
                }
                default:
                    getLongDesc().forEach(this::logDirect);
            }
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
            if (args.hasExactlyOne()) {
                return Stream.of("unpack", "pack");
            }
            args.getString();
            return args.hasExactlyOne() ? args.tabCompleteDatatype(ItemById.INSTANCE) : Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "Use a carried shulker box (Alto)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList("Places a carried shulker box, takes items out or puts items in, and picks it up again.",
                    "Shulker boxes keep their contents when broken, so nothing is lost.", "",
                    "Usage:",
                    "> shulker unpack <item> [count] - take items out of the box that holds them",
                    "> shulker pack [item] - put that item (or everything except the keep-list) into the emptiest box");
        }
    }

    static final class ExportRegistryCommand extends AltoChatCommand {
        private final Alto alto;

        ExportRegistryCommand(IBaritone baritone, Alto alto) {
            super(baritone, "export-registry");
            this.alto = alto;
        }

        @Override
        public void execute(String label, IArgConsumer args) throws CommandException {
            args.requireMax(0);
            java.nio.file.Path file = alto.directory().resolve("alto-registry.json");
            try {
                int n = RegistryExport.write(file);
                logDirect("Wrote " + n + " items and blocks to " + file.toAbsolutePath()
                        + " - load it in the script editor (Load registry...) for item and block pickers.");
            } catch (java.io.IOException e) {
                throw new CommandInvalidStateException("Could not write " + file + ": " + e.getMessage());
            }
        }

        @Override
        public Stream<String> tabComplete(String label, IArgConsumer args) {
            return Stream.empty();
        }

        @Override
        public String getShortDesc() {
            return "Write all item/block ids and names for the script editor (Alto)";
        }

        @Override
        public List<String> getLongDesc() {
            return Arrays.asList("Writes baritone/alto/alto-registry.json with every item and block of your modpack and its",
                    "display name. The offline script editor loads it for its item and block pickers.", "", "Usage:", "> export-registry");
        }
    }
}
