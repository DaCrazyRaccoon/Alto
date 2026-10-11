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

package baritone.alto.travel;

import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.alto.core.Pacer;
import baritone.alto.task.AltoTask;
import baritone.alto.task.Step;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.Optional;

/**
 * Extra 7, vehicles: {@code #minecart} and {@code #boat}. Only normal input: right-click to board, the movement
 * keys to ride or steer, sneak to get off, left-clicks to pick a placed boat up again.
 */
public final class VehicleTasks {

    private VehicleTasks() {
    }

    /** Looks at {@code e} and right-clicks it once (paced). True when the click was sent. */
    static boolean aimAndUse(Alto alto, Entity e) {
        LocalPlayer p = alto.ctx().player();
        Vec3 center = e.getBoundingBox().getCenter();
        if (p.getEyePosition().distanceTo(center) > p.entityInteractionRange()) {
            return false;
        }
        alto.baritone().getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(p.getEyePosition(), center, alto.ctx().playerRotations()), true);
        HitResult hit = Minecraft.getInstance().hitResult;
        if (!(hit instanceof EntityHitResult ehr) || ehr.getEntity() != e || p.isShiftKeyDown()
                || !alto.pacer().tryAcquire(Pacer.Kind.BLOCK_USE)) {
            return false;
        }
        var gm = Minecraft.getInstance().gameMode;
        InteractionResult r = gm.interactAt(p, e, ehr, InteractionHand.MAIN_HAND);
        if (!r.consumesAction()) {
            r = gm.interact(p, e, InteractionHand.MAIN_HAND);
        }
        if (r.shouldSwing()) {
            p.swing(InteractionHand.MAIN_HAND);
        }
        return true;
    }

    /** Same measure as the click itself: eye to the middle of the entity, with a little margin. */
    static boolean inReach(LocalPlayer p, Entity e) {
        return p.getEyePosition().distanceTo(e.getBoundingBox().getCenter()) <= p.entityInteractionRange() - 0.3;
    }

    static void dismountKey(Alto alto, boolean down) {
        alto.baritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, down);
    }

    // ------------------------------------------------------------------------------------------ minecart

    /** {@code #minecart [x z]}: board the nearest free minecart, ride (holding forward), get off at the end. */
    public static final class MinecartTask extends AltoTask {
        private enum Phase { FIND, BOARD, RIDE, OFF, WALK }

        private final Integer x;
        private final Integer z;
        private Phase phase = Phase.FIND;
        private AbstractMinecart cart;
        private int still;
        private int ticks;
        private int tries;

        public MinecartTask(Alto alto, Integer x, Integer z) {
            super(alto);
            this.x = x;
            this.z = z;
        }

        @Override
        public String name() {
            return "Ride a minecart" + (x == null ? "" : " towards " + x + " " + z);
        }

        @Override
        public Step tick(boolean calcFailed) {
            LocalPlayer p = ctx.player();
            switch (phase) {
                case FIND: {
                    if (p.getVehicle() instanceof AbstractMinecart m) {
                        cart = m;
                        phase = Phase.RIDE;
                        return Step.stay();
                    }
                    Optional<AbstractMinecart> found = ctx.world().getEntitiesOfClass(AbstractMinecart.class, p.getBoundingBox().inflate(16),
                                    m -> m.getMinecartType() == AbstractMinecart.Type.RIDEABLE && m.getPassengers().isEmpty())
                            .stream().min(Comparator.comparingDouble(m -> m.distanceToSqr(p)));
                    if (found.isEmpty()) {
                        return Step.failure("no free minecart within 16 blocks");
                    }
                    cart = found.get();
                    phase = Phase.BOARD;
                    ticks = 0;
                    return Step.stay();
                }
                case BOARD: {
                    detail("boarding the minecart");
                    if (p.getVehicle() == cart) {
                        phase = Phase.RIDE;
                        still = 0;
                        ticks = 0;
                        return Step.stay();
                    }
                    if (cart.isRemoved() || !cart.getPassengers().isEmpty() || ++ticks > 20 * 30) {
                        return Step.failure("could not board the minecart");
                    }
                    if (!inReach(p, cart)) {
                        if (calcFailed && ++tries > 3) {
                            return Step.failure("cannot reach the minecart");
                        }
                        return Step.walk(new GoalNear(cart.blockPosition(), 1));
                    }
                    aimAndUse(alto, cart);
                    return Step.stay();
                }
                case RIDE: {
                    detail("riding");
                    if (p.getVehicle() != cart) {
                        phase = x == null ? Phase.OFF : Phase.WALK;
                        return Step.stay();
                    }
                    boolean arrived = x != null && Math.hypot(p.getX() - x - 0.5, p.getZ() - z - 0.5) < 3;
                    still = cart.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4 ? still + 1 : 0;
                    if (arrived || still > 60) { // destination, or the cart stopped for 3 seconds (end of the line)
                        alto.baritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                        phase = Phase.OFF;
                        ticks = 0;
                        return Step.stay();
                    }
                    alto.baritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                    return Step.stay();
                }
                case OFF: {
                    detail("getting off");
                    if (p.getVehicle() == null) {
                        dismountKey(alto, false);
                        if (x == null) {
                            return Step.success("got off at " + p.blockPosition().toShortString());
                        }
                        phase = Phase.WALK;
                        return Step.stay();
                    }
                    if (++ticks > 100) {
                        dismountKey(alto, false);
                        return Step.failure("could not get off the minecart");
                    }
                    dismountKey(alto, true);
                    return Step.stay();
                }
                case WALK: {
                    GoalXZ goal = new GoalXZ(x, z);
                    if (goal.isInGoal(ctx.playerFeet())) {
                        return Step.success("arrived");
                    }
                    if (calcFailed && ++tries > 5) {
                        return Step.failure("cannot walk to the destination");
                    }
                    return Step.walk(goal);
                }
                default:
                    return Step.failure("unknown phase");
            }
        }

        @Override
        public void cancel() {
            alto.baritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            dismountKey(alto, false);
        }
    }

    // ------------------------------------------------------------------------------------------ boat

    /**
     * {@code #boat <x> <z>}: use a nearby empty boat or place one from the inventory on water, steer in a straight
     * line towards the target, get off at the target or the shore, pick the boat up again if Alto placed it, and walk
     * the rest.
     */
    public static final class BoatTask extends AltoTask {
        private enum Phase { FIND, PLACE, BOARD, STEER, OFF, PICKUP, COLLECT, WALK }

        private final int x;
        private final int z;
        private Phase phase = Phase.FIND;
        private Boat boat;
        private boolean placed;
        private BlockPos water;
        private int ticks;
        private int blocked;
        private int tries;
        private int boatsBefore;

        public BoatTask(Alto alto, int x, int z) {
            super(alto);
            this.x = x;
            this.z = z;
        }

        @Override
        public String name() {
            return "Boat towards " + x + " " + z;
        }

        @Override
        public Step tick(boolean calcFailed) {
            LocalPlayer p = ctx.player();
            switch (phase) {
                case FIND: {
                    if (p.getVehicle() instanceof Boat b) {
                        boat = b;
                        phase = Phase.STEER;
                        return Step.stay();
                    }
                    Optional<Boat> near = ctx.world().getEntitiesOfClass(Boat.class, p.getBoundingBox().inflate(12), b -> b.getPassengers().isEmpty())
                            .stream().min(Comparator.comparingDouble(b -> b.distanceToSqr(p)));
                    if (near.isPresent()) {
                        boat = near.get();
                        phase = Phase.BOARD;
                        ticks = 0;
                        return Step.stay();
                    }
                    int slot = boatSlot(p);
                    if (slot < 0) {
                        return Step.failure("no boat nearby and none in the hotbar");
                    }
                // Open water: a boat placed next to the shore collides with it and the game refuses to place it.
                    water = BlockPos.betweenClosedStream(p.blockPosition().offset(-8, -3, -8), p.blockPosition().offset(8, 1, 8))
                            .filter(this::openWater)
                            .map(BlockPos::immutable)
                            .min(Comparator.comparingDouble(b -> b.distSqr(p.blockPosition())))
                            .orElse(null);
                    if (water == null) {
                        return Step.failure("no open water within 8 blocks");
                    }
                    phase = Phase.PLACE;
                    ticks = 0;
                    return Step.stay();
                }
                case PLACE: {
                    detail("placing a boat");
                    Optional<Boat> appeared = ctx.world().getEntitiesOfClass(Boat.class, p.getBoundingBox().inflate(6), b -> b.getPassengers().isEmpty())
                            .stream().min(Comparator.comparingDouble(b -> b.distanceToSqr(p)));
                    if (appeared.isPresent() && ticks > 0) {
                        boat = appeared.get();
                        placed = true;
                        phase = Phase.BOARD;
                        ticks = 0;
                        return Step.stay();
                    }
                    if (++ticks > 20 * 20) {
                        return Step.failure("could not place the boat");
                    }
                    Vec3 top = Vec3.atCenterOf(water).add(0, 0.4, 0);
                    if (p.getEyePosition().distanceTo(top) > 4) {
                        return Step.walk(new GoalNear(water, 2));
                    }
                    int slot = boatSlot(p);
                    if (slot < 0) {
                        return Step.failure("the boat is gone from the hotbar");
                    }
                    p.getInventory().selected = slot;
                    Rotation r = RotationUtils.calcRotationFromVec3d(p.getEyePosition(), top, ctx.playerRotations());
                    alto.baritone().getLookBehavior().updateTarget(r, true);
                    if (ticks % 10 == 5 && alto.pacer().tryAcquire(Pacer.Kind.BLOCK_USE)) {
                        Minecraft.getInstance().gameMode.useItem(p, InteractionHand.MAIN_HAND); // a boat is placed with a normal item use
                    }
                    return Step.stay();
                }
                case BOARD: {
                    detail("boarding the boat");
                    if (p.getVehicle() == boat) {
                        phase = Phase.STEER;
                        blocked = 0;
                        return Step.stay();
                    }
                    if (boat.isRemoved() || ++ticks > 20 * 30) {
                        return Step.failure("could not board the boat");
                    }
                    if (!inReach(p, boat)) {
                        if (calcFailed && ++tries > 3) {
                            return Step.failure("cannot reach the boat");
                        }
                        return Step.walk(new GoalNear(boat.blockPosition(), 1));
                    }
                    aimAndUse(alto, boat);
                    return Step.stay();
                }
                case STEER: {
                    detail("steering");
                    if (p.getVehicle() != boat) {
                        clearSteer();
                        phase = Phase.WALK;
                        return Step.stay();
                    }
                    double dx = x + 0.5 - boat.getX();
                    double dz = z + 0.5 - boat.getZ();
                    double dist = Math.hypot(dx, dz);
                    blocked = boat.getDeltaMovement().horizontalDistanceSqr() < 0.0004 ? blocked + 1 : 0;
                    if (dist < 2.5 || blocked > 60) { // arrived, or stuck on the shore for 3 seconds
                        clearSteer();
                        phase = Phase.OFF;
                        ticks = 0;
                        return Step.stay();
                    }
                    float want = (float) (Mth.atan2(dz, dx) * (180 / Math.PI)) - 90f;
                    float diff = Mth.wrapDegrees(want - boat.getYRot());
                    var in = alto.baritone().getInputOverrideHandler();
                    in.setInputForceState(Input.MOVE_LEFT, diff < -8);
                    in.setInputForceState(Input.MOVE_RIGHT, diff > 8);
                    in.setInputForceState(Input.MOVE_FORWARD, Math.abs(diff) < 60);
                    return Step.stay();
                }
                case OFF: {
                    detail("getting off");
                    if (p.getVehicle() == null) {
                        dismountKey(alto, false);
                        phase = placed ? Phase.PICKUP : Phase.WALK;
                        ticks = 0;
                        return Step.stay();
                    }
                    if (++ticks > 100) {
                        dismountKey(alto, false);
                        return Step.failure("could not get off the boat");
                    }
                    dismountKey(alto, true);
                    return Step.stay();
                }
                case PICKUP: {
                    detail("picking the boat up");
                    if (boat.isRemoved()) {
                        phase = Phase.COLLECT; // it dropped as an item: go and get it
                        ticks = 0;
                        return Step.stay();
                    }
                    if (ticks == 0) {
                        boatsBefore = boatItems(p);
                    }
                    if (++ticks > 20 * 20) {
                        AltoLog.chat("Could not pick the boat up again; it is left at " + boat.blockPosition().toShortString() + ".");
                        phase = Phase.WALK;
                        return Step.stay();
                    }
                    Vec3 center = boat.getBoundingBox().getCenter();
                    if (p.getEyePosition().distanceTo(center) > p.entityInteractionRange() - 0.5) {
                        return Step.walk(new GoalNear(boat.blockPosition(), 1));
                    }
                    alto.baritone().getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(p.getEyePosition(), center, ctx.playerRotations()), true);
                    if (Minecraft.getInstance().hitResult instanceof EntityHitResult ehr && ehr.getEntity() == boat
                            && p.getAttackStrengthScale(0f) >= 1f && alto.pacer().tryAcquire(Pacer.Kind.BLOCK_USE)) {
                        Minecraft.getInstance().gameMode.attack(p, boat); // a few hits break a boat into its item, as for any player
                        p.swing(InteractionHand.MAIN_HAND);
                    }
                    return Step.stay();
                }
                case COLLECT: {
                    detail("collecting the boat");
                    if (boatItems(p) > boatsBefore) {
                        phase = Phase.WALK;
                        return Step.stay();
                    }
                    Optional<net.minecraft.world.entity.item.ItemEntity> drop = ctx.world().getEntitiesOfClass(
                                    net.minecraft.world.entity.item.ItemEntity.class, p.getBoundingBox().inflate(8),
                                    e -> e.getItem().getItem() instanceof BoatItem)
                            .stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
                    if (++ticks > 20 * 20 || drop.isEmpty() && ticks > 40) {
                        AltoLog.chat("Could not collect the boat item.");
                        phase = Phase.WALK;
                        return Step.stay();
                    }
                    if (drop.isEmpty() || drop.get().distanceToSqr(p) < 1.0) {
                        return Step.stay(); // the server hands it over when we touch it
                    }
                    return Step.walk(new baritone.api.pathing.goals.GoalBlock(drop.get().blockPosition()));
                }
                case WALK: {
                    GoalXZ goal = new GoalXZ(x, z);
                    if (goal.isInGoal(ctx.playerFeet())) {
                        return Step.success("arrived");
                    }
                    if (calcFailed && ++tries > 5) {
                        return Step.failure("cannot walk the rest of the way");
                    }
                    return Step.walk(goal);
                }
                default:
                    return Step.failure("unknown phase");
            }
        }

        private boolean openWater(BlockPos b) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos n = b.offset(dx, 0, dz);
                    if (!ctx.world().getBlockState(n).is(Blocks.WATER) || !ctx.world().getBlockState(n.above()).isAir()) {
                        return false;
                    }
                }
            }
            return true;
        }

        private void clearSteer() {
            var in = alto.baritone().getInputOverrideHandler();
            in.setInputForceState(Input.MOVE_LEFT, false);
            in.setInputForceState(Input.MOVE_RIGHT, false);
            in.setInputForceState(Input.MOVE_FORWARD, false);
        }

        private static int boatItems(LocalPlayer p) {
            int n = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                if (p.getInventory().getItem(i).getItem() instanceof BoatItem) {
                    n += p.getInventory().getItem(i).getCount();
                }
            }
            return n;
        }

        private static int boatSlot(LocalPlayer p) {
            for (int i = 0; i < 9; i++) {
                if (p.getInventory().getItem(i).getItem() instanceof BoatItem) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public void cancel() {
            clearSteer();
            dismountKey(alto, false);
        }
    }
}
