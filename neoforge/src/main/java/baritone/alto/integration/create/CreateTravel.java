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
import baritone.alto.AltoLog;
import baritone.alto.core.Pacer;
import baritone.alto.task.AltoTask;
import baritone.alto.task.Step;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ContraptionHandler;
import com.simibubi.create.content.contraptions.ContraptionHandlerClient;
import com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsBlockEntity;
import com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsMovement;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.elevator.ElevatorControlsHandler;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.station.GlobalStation;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Create travel and logistics (extras 7 and 8, stock keeper ordering). Everything happens through normal input:
 * right-clicks that Create's own client turns into its packets (seats, elevator controls), the scroll wheel on the
 * elevator controls, the stock keeper screen's own send action, and sneaking to get off. Never touches train
 * controls, conductors or schedules, and never clicks the stock ticker block itself.
 */
final class CreateTravel {

    private CreateTravel() {
    }

    static List<AbstractContraptionEntity> contraptions(Level level) {
        List<AbstractContraptionEntity> out = new ArrayList<>();
        Map<Integer, WeakReference<AbstractContraptionEntity>> map = ContraptionHandler.loadedContraptions.get(level);
        if (map == null) {
            return out;
        }
        for (WeakReference<AbstractContraptionEntity> ref : List.copyOf(map.values())) {
            AbstractContraptionEntity e = ref.get();
            if (e != null && e.isAlive() && e.getContraption() != null) {
                out.add(e);
            }
        }
        return out;
    }

    static boolean isBlock(BlockState state, String id) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(id);
    }

    /** The real right-click (Minecraft.startUseItem through the use key), so Create's contraption handler sees it. */
    static void pressUse() {
        KeyMapping.click(Minecraft.getInstance().options.keyUse.getKey());
    }

    /** Local position of the contraption block your view ray hits (Create's own ray test), or null. */
    static BlockPos lookedAtLocal(AbstractContraptionEntity e) {
        LocalPlayer p = Minecraft.getInstance().player;
        var ray = ContraptionHandlerClient.getRayInputs(p);
        BlockHitResult hit = ContraptionHandlerClient.rayTraceContraption(ray.getFirst(), ray.getSecond(), e);
        return hit == null ? null : hit.getBlockPos();
    }

    // ------------------------------------------------------------------------------------------ moving platforms

    /** Two fixed points of each contraption, in the world, as seen on the last tick (translation and rotation). */
    private static final Map<AbstractContraptionEntity, Vec3[]> LAST_POSE = new java.util.WeakHashMap<>();
    private static final Vec3 REF_A = new Vec3(0.5, 0.5, 0.5);
    private static final Vec3 REF_B = new Vec3(8.5, 0.5, 8.5);

    /**
     * Did this contraption move since the last tick? Compares where two fixed contraption points are now with where
     * they were a tick ago (Create's own partial-tick helpers only interpolate rotation, not sliding contraptions).
     */
    static boolean movedSinceLastTick(AbstractContraptionEntity e, Map<AbstractContraptionEntity, Vec3[]> now) {
        Vec3[] pose = {e.toGlobalVector(REF_A, 1), e.toGlobalVector(REF_B, 1)};
        now.put(e, pose);
        Vec3[] before = LAST_POSE.get(e);
        return before != null && (before[0].distanceToSqr(pose[0]) > 1.0E-6 || before[1].distanceToSqr(pose[1]) > 1.0E-6);
    }

    /** Every tick: are you standing on a contraption that is moving? */
    static void tickPlatform(Alto alto) {
        LocalPlayer p = Minecraft.getInstance().player;
        Level level = Minecraft.getInstance().level;
        if (p == null || level == null || !Baritone.settings().altoMovingPlatforms.value) {
            LAST_POSE.clear();
            alto.platformPause().setOnMovingPlatform(false);
            return;
        }
        Vec3 under = p.position().add(0, -0.2, 0);
        AABB feet = new AABB(under, under).inflate(0.35, 0.3, 0.35);
        boolean moving = false;
        Map<AbstractContraptionEntity, Vec3[]> poses = new java.util.HashMap<>();
        for (AbstractContraptionEntity e : contraptions(level)) {
            boolean moved = movedSinceLastTick(e, poses); // every contraption, so a pose is always one tick old
            if (moving || p.isPassenger() || !e.getBoundingBox().inflate(1).intersects(feet)) {
                continue;
            }
            Vec3 local = e.toLocalVector(under, 1);
            if (e.getContraption().getBlocks().containsKey(BlockPos.containing(local)) && moved) {
                moving = true; // the floor under your feet moves (lifts, pistons, gantries, bearings)
            }
        }
        LAST_POSE.clear();
        LAST_POSE.putAll(poses);
        alto.platformPause().setOnMovingPlatform(moving);
    }

    // ------------------------------------------------------------------------------------------ trains

    /** Station name -> station block, for this dimension (from the track network Create syncs to every client). */
    static Map<String, BlockPos> stations(Level level) {
        Map<String, BlockPos> out = new LinkedHashMap<>();
        for (TrackGraph graph : List.copyOf(CreateClient.RAILWAYS.trackNetworks.values())) {
            for (GlobalStation st : graph.getPoints(com.simibubi.create.content.trains.graph.EdgePointType.STATION)) {
                if (st.name != null && st.blockEntityPos != null
                        && (st.blockEntityDimension == null || st.blockEntityDimension == level.dimension())) {
                    out.put(st.name, st.blockEntityPos);
                }
            }
        }
        return out;
    }

    static Optional<Map.Entry<String, BlockPos>> station(Level level, String name) {
        Map<String, BlockPos> all = stations(level);
        for (Map.Entry<String, BlockPos> e : all.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name.trim())) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /** {@code #train <from> -> <to>}: walk to the station, wait, board a free seat, ride, get off at the destination. */
    static final class TrainTask extends AltoTask {
        private enum Phase { TO_STATION, WAIT, BOARD, RIDE, OFF, WALK }

        private final String fromName;
        private final String toName;
        private final BlockPos from;
        private final BlockPos to;
        private Phase phase = Phase.TO_STATION;
        private CarriageContraptionEntity carriage;
        private int seat = -1;
        private int ticks;
        private int still;
        private int tries;
        private boolean leftStart;
        private boolean boardOnly;

        /** Script block board_vehicle train_seat: sit on a free seat of a train stopped near you. */
        static TrainTask boardOnly(Alto alto) {
            BlockPos here = alto.ctx().player().blockPosition();
            TrainTask t = new TrainTask(alto, "here", here, "here", here);
            t.boardOnly = true;
            t.phase = Phase.WAIT;
            return t;
        }

        TrainTask(Alto alto, String fromName, BlockPos from, String toName, BlockPos to) {
            super(alto);
            this.fromName = fromName;
            this.from = from;
            this.toName = toName;
            this.to = to;
        }

        @Override
        public String name() {
            return "Train from " + fromName + " to " + toName;
        }

        private Optional<CarriageContraptionEntity> stoppedCarriageNear(BlockPos pos, double radius) {
            return contraptions(ctx.world()).stream()
                    .filter(e -> e instanceof CarriageContraptionEntity)
                    .map(e -> (CarriageContraptionEntity) e)
                    .filter(e -> e.position().distanceTo(Vec3.atCenterOf(pos)) < radius && e.getDeltaMovement().lengthSqr() < 1.0E-4)
                    .min(Comparator.comparingDouble(e -> e.distanceToSqr(ctx.player())));
        }

        /** A free passenger seat of this carriage: not taken by anyone (players, conductors or other mobs). */
        private int freeSeat(CarriageContraptionEntity e) {
            Contraption c = e.getContraption();
            List<BlockPos> seats = c.getSeats();
            double best = Double.MAX_VALUE;
            int bestIndex = -1;
            for (int i = 0; i < seats.size(); i++) {
                if (c.getSeatMapping().containsValue(i)) {
                    continue;
                }
                double d = e.toGlobalVector(Vec3.atCenterOf(seats.get(i)), 1).distanceToSqr(ctx.player().getEyePosition());
                if (d < best) {
                    best = d;
                    bestIndex = i;
                }
            }
            return bestIndex;
        }

        @Override
        public Step tick(boolean calcFailed) {
            LocalPlayer p = ctx.player();
            switch (phase) {
                case TO_STATION: {
                    detail("walking to " + fromName);
                    if (p.blockPosition().distSqr(from) < 5 * 5) {
                        phase = Phase.WAIT;
                        ticks = 0;
                        return Step.stay();
                    }
                    if (calcFailed && ++tries > 5) {
                        return Step.failure("cannot reach the station " + fromName);
                    }
                    return Step.walk(new GoalNear(from, 3));
                }
                case WAIT: {
                    detail("waiting for a train at " + fromName);
                    if (++ticks > (boardOnly ? 20 * 10 : Baritone.settings().altoTrainWaitSeconds.value * 20)) {
                        return Step.failure(boardOnly ? "no stopped train with a free seat near you" : "no train stopped at " + fromName + " in time");
                    }
                    Optional<CarriageContraptionEntity> c = stoppedCarriageNear(from, 16);
                    if (c.isPresent() && freeSeat(c.get()) >= 0) {
                        carriage = c.get();
                        phase = Phase.BOARD;
                        ticks = 0;
                        tries = 0;
                    }
                    return Step.stay();
                }
                case BOARD: {
                    detail("boarding");
                    if (p.getVehicle() != null) {
                        if (boardOnly) {
                            return Step.success("seated");
                        }
                        phase = Phase.RIDE;
                        ticks = 0;
                        still = 0;
                        return Step.stay();
                    }
                    if (!carriage.isAlive() || carriage.getDeltaMovement().lengthSqr() > 1.0E-4 || ++ticks > 20 * 30) {
                        phase = Phase.WAIT; // the train left: wait for the next one
                        ticks = 0;
                        return Step.stay();
                    }
                    seat = freeSeat(carriage);
                    if (seat < 0) {
                        phase = Phase.WAIT;
                        return Step.stay();
                    }
                    BlockPos seatLocal = carriage.getContraption().getSeats().get(seat);
                    Vec3 seatPos = carriage.toGlobalVector(Vec3.atCenterOf(seatLocal), 1);
                    if (p.getEyePosition().distanceTo(seatPos) > p.blockInteractionRange() - 0.5) {
                        if (calcFailed && ++tries > 3) {
                            return Step.failure("cannot reach a free seat");
                        }
                        return Step.walk(new GoalNear(BlockPos.containing(seatPos), 1));
                    }
                    alto.baritone().getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(p.getEyePosition(), seatPos, ctx.playerRotations()), true);
                    // Click only when Create's own ray test says the seat (and nothing else, e.g. controls) is under the crosshair.
                    if (seatLocal.equals(lookedAtLocal(carriage)) && !p.isShiftKeyDown() && alto.pacer().tryAcquire(Pacer.Kind.BLOCK_USE)) {
                        pressUse();
                    }
                    return Step.stay();
                }
                case RIDE: {
                    detail("riding to " + toName);
                    Entity v = p.getVehicle();
                    if (!(v instanceof CarriageContraptionEntity c)) {
                        phase = Phase.WALK;
                        return Step.stay();
                    }
                    if (p.blockPosition().distSqr(from) > 48 * 48) {
                        leftStart = true;
                    }
                    still = c.getDeltaMovement().lengthSqr() < 1.0E-4 ? still + 1 : 0;
                    if (still > 40 && p.blockPosition().distSqr(to) < 24 * 24) {
                        phase = Phase.OFF;
                        ticks = 0;
                        return Step.stay();
                    }
                    if (still > 40 && leftStart && p.blockPosition().distSqr(from) < 24 * 24) {
                        phase = Phase.OFF; // back at the start without passing the destination
                        ticks = -1;
                        return Step.stay();
                    }
                    if (++ticks > 20 * 60 * 30) {
                        phase = Phase.OFF;
                        ticks = -1;
                    }
                    return Step.stay();
                }
                case OFF: {
                    boolean failed = ticks < 0;
                    if (p.getVehicle() == null) {
                        alto.baritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
                        if (failed) {
                            return Step.failure("this train did not stop at " + toName + "; got off");
                        }
                        phase = Phase.WALK;
                        tries = 0;
                        return Step.stay();
                    }
                    alto.baritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                    return Step.stay();
                }
                case WALK: {
                    if (p.blockPosition().distSqr(to) < 4 * 4) {
                        return Step.success("arrived at " + toName);
                    }
                    if (calcFailed && ++tries > 5) {
                        return Step.failure("got off, but cannot walk to " + toName);
                    }
                    return Step.walk(new GoalNear(to, 3));
                }
                default:
                    return Step.failure("unknown phase");
            }
        }

        @Override
        public void cancel() {
            alto.baritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        }
    }

    // ------------------------------------------------------------------------------------------ elevators

    static Optional<AbstractContraptionEntity> nearestElevator(Level level, Player p) {
        return contraptions(level).stream()
                .filter(e -> e.getContraption() instanceof ElevatorContraption)
                .filter(e -> e.getBoundingBox().inflate(12).contains(p.position()))
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
    }

    /** {@code #elevator <floor>}: select the floor on the cab's controls (scroll wheel), right-click, ride. */
    static final class ElevatorTask extends AltoTask {
        private enum Phase { FIND, SELECT, CALL, RIDE }

        private final String floor;
        private Phase phase = Phase.FIND;
        private AbstractContraptionEntity cab;
        private BlockPos controls;
        private int targetIndex;
        private int targetY;
        private int ticks;
        private int still;
        private double lastCabY = Double.NaN;

        ElevatorTask(Alto alto, String floor) {
            super(alto);
            this.floor = floor;
        }

        @Override
        public String name() {
            return "Elevator to " + floor;
        }

        /** The floor's elevator contact shows "powering" once the cab has arrived there (synced block state). */
        private boolean arrivedAtContact(ElevatorContraption ec) {
            var column = ec.getGlobalColumn();
            if (column == null) {
                return false;
            }
            BlockState contact = ctx.world().getBlockState(new BlockPos(column.x(), targetY, column.z()));
            return contact.hasProperty(com.simibubi.create.content.contraptions.elevator.ElevatorContactBlock.POWERING)
                    && contact.getValue(com.simibubi.create.content.contraptions.elevator.ElevatorContactBlock.POWERING);
        }

        private MovementContext controlsContext() {
            var actor = cab.getContraption().getActorAt(controls);
            return actor == null ? null : actor.right;
        }

        private boolean aimAtControls() {
            LocalPlayer p = ctx.player();
            BlockState state = cab.getContraption().getBlocks().get(controls).state();
            Vec3 offset = new ContraptionControlsBlockEntity.ControlsSlot().getLocalOffset(ctx.world(), controls, state);
            Vec3 local = offset == null ? Vec3.atCenterOf(controls) : Vec3.atLowerCornerOf(controls).add(offset);
            Vec3 target = cab.toGlobalVector(local, 1);
            if (p.getEyePosition().distanceTo(target) > p.blockInteractionRange()) {
                return false;
            }
            alto.baritone().getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(p.getEyePosition(), target, ctx.playerRotations()), true);
            return controls.equals(lookedAtLocal(cab));
        }

        @Override
        public Step tick(boolean calcFailed) {
            switch (phase) {
                case FIND: {
                    cab = nearestElevator(ctx.world(), ctx.player()).orElse(null);
                    if (cab == null) {
                        return Step.failure("no Create elevator within 12 blocks");
                    }
                    ElevatorContraption ec = (ElevatorContraption) cab.getContraption();
                    targetIndex = -1;
                    for (int i = 0; i < ec.namesList.size(); i++) {
                        var e = ec.namesList.get(i);
                        String shortName = e.getSecond().getFirst();
                        String longName = e.getSecond().getSecond();
                        if (floor.equalsIgnoreCase(shortName) || floor.equalsIgnoreCase(longName) || floor.equals(String.valueOf(e.getFirst()))) {
                            targetIndex = i;
                            targetY = e.getFirst();
                        }
                    }
                    if (targetIndex < 0) {
                        List<String> names = new ArrayList<>();
                        ec.namesList.forEach(e -> names.add(e.getSecond().getFirst() + " (" + e.getSecond().getSecond() + ")"));
                        return Step.failure("no floor '" + floor + "'; floors: " + names);
                    }
                    controls = cab.getContraption().getBlocks().entrySet().stream()
                            .filter(en -> isBlock(en.getValue().state(), "create:contraption_controls"))
                            .map(Map.Entry::getKey).findFirst().orElse(null);
                    if (controls == null) {
                        return Step.failure("this elevator cab has no contraption controls to select a floor");
                    }
                    phase = Phase.SELECT;
                    ticks = 0;
                    return Step.stay();
                }
                case SELECT: {
                    detail("selecting " + floor);
                    if (++ticks > 20 * 20) {
                        return Step.failure("could not select the floor on the controls");
                    }
                    if (!aimAtControls()) {
                        Vec3 c = cab.toGlobalVector(Vec3.atCenterOf(controls), 1);
                        return Step.walk(new GoalNear(BlockPos.containing(c), 2));
                    }
                    MovementContext mc = controlsContext();
                    int current = mc != null && mc.temporaryData instanceof ContraptionControlsMovement.ElevatorFloorSelection efs ? efs.currentIndex : -1;
                    if (current == targetIndex) {
                        phase = Phase.CALL;
                        ticks = 0;
                        return Step.stay();
                    }
                    if (ticks % 4 == 0 && alto.pacer().tryAcquire(Pacer.Kind.OTHER)) {
                        // The same call the mouse wheel makes while looking at the controls.
                        ElevatorControlsHandler.onScroll(current < 0 || current < targetIndex ? 1 : -1);
                    }
                    return Step.stay();
                }
                case CALL: {
                    ElevatorContraption ec = (ElevatorContraption) cab.getContraption();
                    if (ec.clientYTarget == targetY) {
                        phase = Phase.RIDE;
                        ticks = 0;
                        return Step.stay();
                    }
                    if (++ticks > 20 * 10) {
                        return Step.failure("the elevator did not accept the floor");
                    }
                    if (aimAtControls() && ticks % 20 == 1 && alto.pacer().tryAcquire(Pacer.Kind.BLOCK_USE)) {
                        pressUse();
                    }
                    return Step.stay();
                }
                case RIDE: {
                    detail("riding to " + floor);
                    ElevatorContraption ec = (ElevatorContraption) cab.getContraption();
                    // The cab's own movement (its entity motion is not kept up to date while a pulley moves it).
                    still = Math.abs(cab.getY() - lastCabY) < 1.0E-4 ? still + 1 : 0;
                    lastCabY = cab.getY();
                    int y = (int) Math.round(cab.getY() + ec.getContactYOffset());
                    if ((y == targetY || arrivedAtContact(ec)) && still > 20) {
                        return Step.success("arrived at " + floor);
                    }
                    if (!cab.isAlive()) {
                        return Step.failure("the elevator was taken apart");
                    }
                    if (++ticks > 20 * 120) {
                        return Step.failure("the elevator did not arrive");
                    }
                    return Step.stay(); // the moving-platform pause keeps everything still
                }
                default:
                    return Step.failure("unknown phase");
            }
        }
    }

    // ------------------------------------------------------------------------------------------ stock keepers

    static Optional<LivingEntity> nearestKeeper(Level level, Player p) {
        return level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(24),
                        e -> !(e instanceof Player) && e.getVehicle() instanceof SeatEntity && tickerNextTo(level, e.getVehicle().blockPosition()))
                .stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
    }

    private static boolean tickerNextTo(Level level, BlockPos seat) {
        int n = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int dy = 0; dy <= 1; dy++) {
                if (isBlock(level.getBlockState(seat.relative(d).above(dy)), "create:stock_ticker")) {
                    n++;
                }
            }
        }
        return n == 1;
    }

    /**
     * {@code #order <item> <count> [address]}: walk to the nearest stock keeper, right-click it (the server decides
     * whether you may use it and opens its screen), put the item in the screen's order list and use the screen's
     * own send action. Ordering only ever happens in a screen the server opened for you.
     */
    static final class OrderTask extends AltoTask {
        private enum Phase { FIND, OPEN, STOCK, SEND, CLOSE }

        private final Item item;
        private final int count;
        private final String address;
        private Phase phase = Phase.FIND;
        private LivingEntity keeper;
        private int ticks;
        private int ordered;
        private int tries;

        OrderTask(Alto alto, Item item, int count, String address) {
            super(alto);
            this.item = item;
            this.count = count;
            this.address = address;
        }

        @Override
        public String name() {
            return "Order " + count + "x " + BuiltInRegistries.ITEM.getKey(item).getPath() + (address == null ? "" : " to " + address);
        }

        private StockTickerBlockEntity ticker(StockKeeperRequestScreen s) throws ReflectiveOperationException {
            var f = StockKeeperRequestScreen.class.getDeclaredField("blockEntity");
            f.setAccessible(true);
            return (StockTickerBlockEntity) f.get(s);
        }

        @Override
        public Step tick(boolean calcFailed) {
            LocalPlayer p = ctx.player();
            Minecraft mc = Minecraft.getInstance();
            switch (phase) {
                case FIND: {
                    keeper = nearestKeeper(ctx.world(), p).orElse(null);
                    if (keeper == null) {
                        return Step.failure("no stock keeper (a seated mob next to a stock ticker) within 24 blocks");
                    }
                    phase = Phase.OPEN;
                    ticks = 0;
                    return Step.stay();
                }
                case OPEN: {
                    detail("opening the stock keeper");
                    if (mc.screen instanceof StockKeeperRequestScreen) {
                        phase = Phase.STOCK;
                        ticks = 0;
                        return Step.stay();
                    }
                    if (++ticks > 20 * 30) {
                        return Step.failure("the stock keeper did not open (locked network, or no permission)");
                    }
                    Vec3 c = keeper.getBoundingBox().getCenter();
                    if (p.getEyePosition().distanceTo(c) > p.entityInteractionRange() - 0.3) {
                        if (calcFailed && ++tries > 3) {
                            return Step.failure("cannot reach the stock keeper");
                        }
                        return Step.walk(new GoalNear(keeper.blockPosition(), 2));
                    }
                    alto.baritone().getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(p.getEyePosition(), c, ctx.playerRotations()), true);
                    if (mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() == keeper && ticks % 20 == 2
                            && alto.pacer().tryAcquire(Pacer.Kind.CONTAINER_OPEN)) {
                        InteractionHelper.interact(p, keeper);
                    }
                    return Step.stay();
                }
                case STOCK: {
                    detail("reading the stock");
                    if (!(mc.screen instanceof StockKeeperRequestScreen s)) {
                        return Step.failure("the stock keeper screen closed");
                    }
                    try {
                        int available = ticker(s).getLastClientsideStockSnapshotAsSummary().getCountOf(new ItemStack(item));
                        if (available > 0) {
                            ordered = Math.min(count, available);
                            phase = Phase.SEND;
                            return Step.stay();
                        }
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        return Step.failure("cannot read this stock keeper (" + e + ")");
                    }
                    if (++ticks > 20 * 5) {
                        mc.setScreen(null);
                        return Step.failure(BuiltInRegistries.ITEM.getKey(item).getPath() + " is not in stock");
                    }
                    return Step.stay();
                }
                case SEND: {
                    if (!(mc.screen instanceof StockKeeperRequestScreen s)) {
                        return Step.failure("the stock keeper screen closed");
                    }
                    if (!alto.pacer().tryAcquire(Pacer.Kind.OTHER)) {
                        return Step.stay();
                    }
                    try {
                        // What clicking the item and then "Send" does in the screen.
                        s.itemsToOrder = new ArrayList<>(List.of(new BigItemStack(new ItemStack(item), ordered)));
                        if (address != null) {
                            s.addressBox.setValue(address);
                        }
                        var send = StockKeeperRequestScreen.class.getDeclaredMethod("sendIt");
                        send.setAccessible(true);
                        send.invoke(s);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        return Step.failure("could not use the stock keeper screen (" + e + ")");
                    }
                    phase = Phase.CLOSE;
                    ticks = 0;
                    return Step.stay();
                }
                case CLOSE: {
                    if (++ticks < 10) {
                        return Step.stay();
                    }
                    if (mc.screen instanceof StockKeeperRequestScreen) {
                        p.closeContainer();
                    }
                    return Step.success("ordered " + ordered + "x " + BuiltInRegistries.ITEM.getKey(item).getPath()
                            + (ordered < count ? " (all that was in stock)" : ""));
                }
                default:
                    return Step.failure("unknown phase");
            }
        }
    }

    /** Right-click an entity like the use key does (interactAt, then interact). */
    static final class InteractionHelper {
        static void interact(LocalPlayer p, Entity e) {
            var gm = Minecraft.getInstance().gameMode;
            var hit = Minecraft.getInstance().hitResult instanceof EntityHitResult ehr ? ehr : new EntityHitResult(e);
            var r = gm.interactAt(p, e, hit, net.minecraft.world.InteractionHand.MAIN_HAND);
            if (!r.consumesAction()) {
                r = gm.interact(p, e, net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            if (r.shouldSwing()) {
                p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            AltoLog.debug("interact " + e.getType() + " -> " + r);
        }
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
