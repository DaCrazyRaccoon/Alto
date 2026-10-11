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

package baritone.alto.scriptmc;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import baritone.alto.planner.Plan;
import baritone.alto.planner.StationKind;
import baritone.alto.planner.WorldModel;
import baritone.alto.planner.mc.GatherTable;
import baritone.alto.script.lang.Face;
import baritone.alto.script.lang.Value;
import baritone.alto.script.lang.Where;
import baritone.alto.script.runtime.ActionCall;
import baritone.alto.script.runtime.ActionHandle;
import baritone.alto.script.runtime.ActionRequest;
import baritone.alto.script.runtime.GotoTarget;
import baritone.alto.script.runtime.QueryCall;
import baritone.alto.script.runtime.ScriptHost;
import baritone.alto.storage.ContainerRecord;
import baritone.alto.storage.DepositTask;
import baritone.alto.storage.FetchTask;
import baritone.alto.storage.Zones;
import baritone.alto.task.AltoTask;
import baritone.alto.task.Job;
import baritone.alto.task.PickupStationTask;
import baritone.alto.task.PlaceStationTask;
import baritone.alto.task.PlanContext;
import baritone.alto.task.PlanTask;
import baritone.alto.task.SequenceTask;
import baritone.alto.task.SetFilterTask;
import baritone.alto.task.UseItemOnBlockTask;
import baritone.alto.travel.PortalBook;
import baritone.alto.travel.TravelTask;
import baritone.alto.travel.VehicleTasks;
import baritone.alto.upkeep.EatTask;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.schematic.FillSchematic;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Connects the pure script interpreter ({@code baritone.alto.script}) to the game: every action block becomes an
 * Alto job or a Baritone process, every query reads the client's world. Runs on the client thread only.
 */
public final class AltoScriptHost implements ScriptHost {

    private final Alto alto;
    /** Stations placed by this run's place_station blocks (pickup_station only ever picks those up). */
    private final PlanContext stations = new PlanContext(new ItemCounts(), p -> false);

    public AltoScriptHost(Alto alto) {
        this.alto = alto;
    }

    // ------------------------------------------------------------------------------------------ handles

    /** An action done by an Alto job. */
    private final class JobHandle implements ActionHandle {
        private Boolean result;
        private String reason = "";
        private boolean started;

        JobHandle(AltoTask task) {
            alto.startJob(new Job(task, false, "script", (ok, msg) -> {
                result = ok;
                reason = msg == null ? "" : msg;
            }));
            started = true;
        }

        @Override
        public Status poll() {
            if (result == null) {
                return started && alto.process().busy() ? Status.RUNNING : fail("the job ended unexpectedly");
            }
            return result ? Status.SUCCEEDED : Status.FAILED;
        }

        private Status fail(String why) {
            reason = why;
            result = false;
            return Status.FAILED;
        }

        @Override
        public String failureReason() {
            return reason;
        }

        @Override
        public void cancel() {
            if (result == null) {
                result = false;
                reason = "cancelled";
                alto.process().cancelJob("script stopped or paused");
            }
        }
    }

    /** An action done by one of Baritone's own processes (mine, build, farm, goto, commands). */
    private final class ProcessHandle implements ActionHandle {
        private final BooleanSupplier active;
        private final Supplier<String> check;
        private int ticks;
        private String reason = "";
        private boolean cancelled;

        ProcessHandle(BooleanSupplier active, Supplier<String> check) {
            this.active = active;
            this.check = check;
        }

        @Override
        public Status poll() {
            if (cancelled) {
                return Status.FAILED;
            }
            if (++ticks < 5 || active.getAsBoolean()) {
                return Status.RUNNING;
            }
            String problem = check.get();
            if (problem == null) {
                return Status.SUCCEEDED;
            }
            reason = problem;
            return Status.FAILED;
        }

        @Override
        public String failureReason() {
            return cancelled ? "cancelled" : reason;
        }

        @Override
        public void cancel() {
            if (!cancelled) {
                cancelled = true;
                alto.baritone().getPathingBehavior().cancelEverything();
            }
        }
    }

    private static ActionHandle done() {
        return new ActionHandle() {
            @Override
            public Status poll() {
                return Status.SUCCEEDED;
            }

            @Override
            public String failureReason() {
                return "";
            }

            @Override
            public void cancel() {
            }
        };
    }

    private static ActionHandle failed(String why) {
        return new ActionHandle() {
            @Override
            public Status poll() {
                return Status.FAILED;
            }

            @Override
            public String failureReason() {
                return why;
            }

            @Override
            public void cancel() {
            }
        };
    }

    // ------------------------------------------------------------------------------------------ actions

    @Override
    public ActionHandle start(ActionCall call) {
        LocalPlayer p = alto.ctx().player();
        if (p == null) {
            return failed("not in a world");
        }
        AltoLog.debug("script " + call.path() + ": " + call.request());
        try {
            return switch (call.request()) {
                case ActionRequest.Goto g -> startGoto(g.target());
                case ActionRequest.Mine m -> startMine(m.blockOrItem(), m.count());
                case ActionRequest.Build b -> startBuild(b);
                case ActionRequest.Fill f -> startFill(f);
                case ActionRequest.Farm f -> startFarm(f);
                case ActionRequest.Craft c -> startPlan(c.item(), c.count(), "craft");
                case ActionRequest.Smelt s -> startPlan(s.item(), s.count(), "smelt");
                case ActionRequest.Fetch f -> startFetch(f.item(), f.count());
                case ActionRequest.Deposit d -> startDeposit(d);
                case ActionRequest.Eat e -> startEat();
                case ActionRequest.Command c -> startCommand(c.command());
                case ActionRequest.SendChat c -> sendChat(c.text());
                case ActionRequest.UseItemOnBlock u -> new JobHandle(new UseItemOnBlockTask(alto, new BlockPos(u.x(), u.y(), u.z()),
                        direction(u.face()), u.item().equals("minecraft:air") ? ItemKey.EMPTY : key(u.item())));
                case ActionRequest.SetFilter f -> new JobHandle(new SetFilterTask(alto, new BlockPos(f.x(), f.y(), f.z()),
                        key(f.item()), f.face() == null ? null : direction(f.face())));
                case ActionRequest.PlaceStation s -> new JobHandle(new PlaceStationTask(alto, station(s.kind()), stations));
                case ActionRequest.PickupStation s -> stations.placed(station(s.kind())) == null
                        ? failed("no " + s.kind().jsonName() + " placed by this script to pick up")
                        : new JobHandle(new PickupStationTask(alto, station(s.kind()), stations));
                case ActionRequest.BoardVehicle v -> startBoard(v);
                case ActionRequest.RideTrain t -> integration("ride_train", List.of(t.fromStation(), t.toStation()));
                case ActionRequest.LeaveVehicle l -> new JobHandle(new VehicleTasks.LeaveTask(alto));
                case ActionRequest.TakePortal t -> new JobHandle(TravelTask.throughNearestPortal(alto));
            };
        } catch (RuntimeException e) {
            AltoLog.exception("script action " + call.path() + " failed to start", e);
            return failed("could not start: " + e.getMessage());
        }
    }

    private ActionHandle integration(String name, List<String> args) {
        var factory = alto.scriptActions().get(name);
        if (factory == null) {
            return failed(name + " needs a mod that is not installed (Create)");
        }
        return new JobHandle(factory.apply(args));
    }

    private ActionHandle startGoto(GotoTarget target) {
        String dim = alto.storage().dimension();
        switch (target) {
            case GotoTarget.Coords c -> {
                if (c.dimension() != null && !c.dimension().equals(dim)) {
                    return new JobHandle(new TravelTask(alto, c.dimension(), c.x(), c.y(), c.z()));
                }
                return walk(new GoalBlock(c.x(), c.y(), c.z()), "the target");
            }
            case GotoTarget.Home h -> {
                Optional<Zones.Home> home = alto.storage().zones().homes().stream()
                        .filter(x -> h.name() == null || h.name().isEmpty() || x.name().equalsIgnoreCase(h.name())).findFirst();
                if (home.isEmpty()) {
                    return failed("no home " + (h.name() == null ? "" : "'" + h.name() + "'") + " set (#home set)");
                }
                Zones.Home x = home.get();
                if (!x.dimension().equals(dim)) {
                    return new JobHandle(new TravelTask(alto, x.dimension(), x.x(), x.y(), x.z()));
                }
                return walk(new GoalNear(new BlockPos(x.x(), x.y(), x.z()), 1), "home");
            }
            case GotoTarget.StorageZone z -> {
                Optional<Zones.Zone> zone = alto.storage().zones().zone(z.name());
                if (zone.isEmpty()) {
                    return failed("no storage zone '" + z.name() + "'");
                }
                Zones.Zone x = zone.get();
                BlockPos center = new BlockPos((x.minX() + x.maxX()) / 2, x.minY(), (x.minZ() + x.maxZ()) / 2);
                if (!x.dimension().equals(dim)) {
                    return new JobHandle(new TravelTask(alto, x.dimension(), center.getX(), center.getY(), center.getZ()));
                }
                return walk(new GoalNear(center, 2), "the storage zone");
            }
            case GotoTarget.Portal portal -> {
                var p = alto.ctx().player();
                Optional<PortalBook.Pos> nearest = alto.portals().book().portals().stream()
                        .filter(x -> x.dim().equals(dim))
                        .min(java.util.Comparator.comparingDouble(x -> p.distanceToSqr(x.x(), x.y(), x.z())));
                if (nearest.isEmpty()) {
                    return failed("no known portal in this dimension");
                }
                return walk(new GoalNear(new BlockPos(nearest.get().x(), nearest.get().y(), nearest.get().z()), 2), "the portal");
            }
        }
    }

    private ActionHandle walk(Goal goal, String what) {
        alto.baritone().getCustomGoalProcess().setGoalAndPath(goal);
        return new ProcessHandle(() -> alto.baritone().getCustomGoalProcess().isActive(),
                () -> goal.isInGoal(alto.ctx().playerFeet()) ? null : "could not reach " + what);
    }

    private ActionHandle startMine(String id, int count) {
        List<BlockOptionalMeta> blocks = new ArrayList<>();
        ResourceLocation rl = ResourceLocation.parse(id);
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(rl).filter(b -> b != Blocks.AIR);
        if (block.isPresent()) {
            blocks.add(new BlockOptionalMeta(block.get()));
        } else {
            WorldModel.GatherSource src = GatherTable.sources().get(ItemKey.of(id));
            if (src == null) {
                return failed("Alto does not know which blocks drop " + id);
            }
            for (String b : src.blocks()) {
                BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(b)).ifPresent(x -> blocks.add(new BlockOptionalMeta(x)));
            }
        }
        if (blocks.isEmpty()) {
            return failed("nothing to mine for " + id);
        }
        BlockOptionalMetaLookup lookup = new BlockOptionalMetaLookup(blocks.toArray(new BlockOptionalMeta[0]));
        Predicate<ItemStack> counted = st -> lookup.has(st) || McItems.key(st.getItem()).id().equals(id);
        int before = countInventory(counted);
        alto.baritone().getMineProcess().mine(before + count, lookup);
        return new ProcessHandle(() -> {
            if (countInventory(counted) - before >= count) {
                alto.baritone().getMineProcess().cancel(); // enough, even if Baritone cannot count these drops
                return false;
            }
            return alto.baritone().getMineProcess().isActive();
        }, () -> {
            int got = countInventory(counted) - before;
            return got >= count ? null : "mining stopped after " + Math.max(0, got) + "/" + count;
        });
    }

    private int countInventory(Predicate<ItemStack> match) {
        var inv = alto.ctx().player().getInventory();
        int n = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            if (!inv.items.get(i).isEmpty() && match.test(inv.items.get(i))) {
                n += inv.items.get(i).getCount();
            }
        }
        return n;
    }

    private ActionHandle builderHandle() {
        var builder = alto.baritone().getBuilderProcess();
        return new ProcessHandle(() -> builder.isActive() && !builder.isPaused(),
                () -> builder.isPaused() ? "the build is stuck (missing materials or no way to place)" : null);
    }

    private ActionHandle startBuild(ActionRequest.Build b) {
        File file = new File(new File(Minecraft.getInstance().gameDirectory, "schematics"), b.schematic());
        if (!file.isFile()) {
            return failed("schematic " + b.schematic() + " not found in the schematics folder");
        }
        if (!alto.baritone().getBuilderProcess().build(b.schematic(), file, new Vec3i(b.x(), b.y(), b.z()))) {
            return failed("could not read the schematic " + b.schematic());
        }
        return builderHandle();
    }

    private ActionHandle startFill(ActionRequest.Fill f) {
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(f.material()));
        if (block.isEmpty()) {
            return failed("unknown block " + f.material());
        }
        int x0 = Math.min(f.x1(), f.x2());
        int y0 = Math.min(f.y1(), f.y2());
        int z0 = Math.min(f.z1(), f.z2());
        int w = Math.abs(f.x1() - f.x2()) + 1;
        int h = Math.abs(f.y1() - f.y2()) + 1;
        int l = Math.abs(f.z1() - f.z2()) + 1;
        alto.baritone().getBuilderProcess().build("script fill", new FillSchematic(w, h, l, block.get().defaultBlockState()), new Vec3i(x0, y0, z0));
        return builderHandle();
    }

    private ActionHandle startFarm(ActionRequest.Farm f) {
        int range = f.range() <= 0 ? Baritone.settings().farmMaxScanSize.value : f.range();
        alto.baritone().getFarmProcess().farm(range, null);
        return new ProcessHandle(() -> alto.baritone().getFarmProcess().isActive(), () -> null);
    }

    private ActionHandle startPlan(String id, int count, String verb) {
        ItemKey k = key(id);
        Item item = McItems.item(k);
        if (item == net.minecraft.world.item.Items.AIR) {
            return failed("unknown item " + id);
        }
        int have = McItems.count(alto.ctx().player().getInventory(), item);
        ItemCounts target = ItemCounts.of(k, have + count);
        Plan plan = alto.plan(target, new ItemCounts());
        if (!plan.isComplete()) {
            return failed("cannot " + verb + " " + count + "x " + k.shortName() + ", missing " + plan.missing());
        }
        return new JobHandle(new PlanTask(alto, plan, new PlanContext(new ItemCounts(), x -> false), target));
    }

    private ActionHandle startFetch(String id, int count) {
        ItemKey k = key(id);
        var p = alto.ctx().player();
        List<AltoTask> steps = new ArrayList<>();
        int left = count;
        for (ContainerRecord r : alto.storage().index().containing(k, alto.storage().dimension(), p.getX(), p.getY(), p.getZ(), alto.storage().usable())) {
            if (left <= 0) {
                break;
            }
            int take = Math.min(left, r.contents.get(k));
            steps.add(new FetchTask(alto, r.key, k, take).exact());
            left -= take;
        }
        if (left > 0) {
            return failed("your storage only has " + (count - left) + "/" + count + " " + k.shortName());
        }
        return new JobHandle(new SequenceTask(alto, "Fetch " + count + "x " + k.shortName(), steps));
    }

    private ActionHandle startDeposit(ActionRequest.Deposit d) {
        List<ItemKey> except = d.except().stream().map(AltoScriptHost::key).toList();
        List<ItemKey> only = d.only().stream().map(AltoScriptHost::key).toList();
        Predicate<ItemKey> keep = k -> except.contains(k) || !only.isEmpty() && !only.contains(k);
        return new JobHandle(new DepositTask(alto, keep));
    }

    private ActionHandle startEat() {
        var p = alto.ctx().player();
        if (!p.getFoodData().needsFood()) {
            return done();
        }
        for (Item food : Baritone.settings().altoFoodItems.value) {
            if (McItems.count(p.getInventory(), food) > 0) {
                return new JobHandle(new EatTask(alto, food));
            }
        }
        return failed("no allowed food in the inventory");
    }

    private ActionHandle startCommand(String command) {
        if (!commandAllowed(command)) {
            return failed("command refused: '" + command + "' is not on the allowed list (altoScriptCommands)");
        }
        if (!alto.baritone().getCommandManager().execute(command)) {
            return failed("unknown Baritone command: " + command);
        }
        return new ProcessHandle(() -> alto.baritone().getPathingControlManager().mostRecentInControl().isPresent()
                || alto.process().busy(), () -> null);
    }

    private ActionHandle sendChat(String text) {
        if (!chatAllowed()) {
            return failed("send_chat refused (altoScriptChat is off)");
        }
        var connection = alto.ctx().player().connection;
        if (text.startsWith("/")) {
            connection.sendCommand(text.substring(1));
        } else {
            connection.sendChat(text);
        }
        return done();
    }

    private ActionHandle startBoard(ActionRequest.BoardVehicle v) {
        return switch (v.kind()) {
            case BOAT -> new JobHandle(new VehicleTasks.BoardTask(alto, VehicleTasks.BoardTask.Kind.BOAT));
            case MINECART -> new JobHandle(new VehicleTasks.BoardTask(alto, VehicleTasks.BoardTask.Kind.MINECART));
            case NEAREST -> new JobHandle(new VehicleTasks.BoardTask(alto, VehicleTasks.BoardTask.Kind.ANY));
            case TRAIN_SEAT -> integration("board_train_seat", List.of());
        };
    }

    // ------------------------------------------------------------------------------------------ queries

    @Override
    public Value query(QueryCall query) {
        LocalPlayer p = alto.ctx().player();
        var level = alto.ctx().world();
        return switch (query) {
            case QueryCall.ItemCount c -> Value.of(c.where() == Where.STORAGE ? storageCount(key(c.item()))
                    : McItems.count(p.getInventory(), McItems.item(key(c.item()))));
            case QueryCall.Health h -> Value.of(p.getHealth());
            case QueryCall.Hunger h -> Value.of(p.getFoodData().getFoodLevel());
            case QueryCall.Position pos -> Value.of(switch (pos.axis()) {
                case X -> p.blockPosition().getX();
                case Y -> p.blockPosition().getY();
                case Z -> p.blockPosition().getZ();
            });
            case QueryCall.TimeOfDay t -> Value.of(level.getDayTime() % 24000);
            case QueryCall.Dimension d -> Value.of(level.dimension().location().toString());
            case QueryCall.BlockAt b -> Value.of(BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(b.x(), b.y(), b.z())).getBlock()).toString());
            case QueryCall.InventoryFull f -> Value.of(McItems.freeSlots(p.getInventory()) == 0);
        };
    }

    private int storageCount(ItemKey k) {
        int n = 0;
        Predicate<ContainerRecord> usable = alto.storage().usable();
        for (ContainerRecord r : alto.storage().index().all()) {
            if (usable.test(r)) {
                n += r.contents.get(k);
            }
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------ policy

    @Override
    public boolean safetyStopsEnabled() {
        return Baritone.settings().altoSafetyStops.value;
    }

    @Override
    public boolean chatAllowed() {
        return Baritone.settings().altoScriptChat.value;
    }

    @Override
    public boolean commandAllowed(String command) {
        String c = command.trim().toLowerCase(Locale.ROOT);
        for (String allowed : Baritone.settings().altoScriptCommands.value) {
            if (allowed.trim().replaceFirst("^#", "").toLowerCase(Locale.ROOT).equals(c)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void showMessage(String text) {
        AltoLog.chat("[script] " + text);
    }

    @Override
    public long nowMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    // ------------------------------------------------------------------------------------------ helpers

    static ItemKey key(String id) {
        return McItems.key(McItems.item(ItemKey.of(id)));
    }

    static Direction direction(Face f) {
        return switch (f) {
            case UP -> Direction.UP;
            case DOWN -> Direction.DOWN;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
        };
    }

    static StationKind station(baritone.alto.script.lang.StationType t) {
        return switch (t) {
            case CRAFTING_TABLE -> StationKind.CRAFTING_TABLE;
            case FURNACE -> StationKind.FURNACE;
            case BLAST_FURNACE -> StationKind.BLAST_FURNACE;
            case SMOKER -> StationKind.SMOKER;
        };
    }

    static boolean isBlockItem(Item item) {
        return item instanceof BlockItem;
    }
}
