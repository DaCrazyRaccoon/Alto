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

package baritone.alto.task;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.alto.container.MenuGoals;
import baritone.alto.core.Backoff;
import baritone.alto.core.ItemKey;
import baritone.alto.core.Pacer;
import baritone.alto.mc.McItems;
import baritone.alto.planner.StationKind;
import baritone.alto.station.SpotFinder;
import baritone.alto.station.StationManager;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Places a station block from the inventory at a safe spot next to the player (see {@link SpotFinder}), using a
 * normal right-click on the top face of the block below the spot, and records it as placed by Alto.
 */
public final class PlaceStationTask extends AltoTask {

    private enum Phase { FIND_SPOT, HOTBAR, AIM, WAIT }

    private final StationKind kind;
    private final PlanContext pctx;
    private final Item item;
    private Phase phase = Phase.FIND_SPOT;
    private SpotFinder.Spot spot;
    private MenuGoalTask hotbar;
    private final Backoff backoff;
    private int ticks;

    public PlaceStationTask(Alto alto, StationKind kind, PlanContext pctx) {
        super(alto);
        this.kind = kind;
        this.pctx = pctx;
        this.item = StationManager.blockFor(kind).asItem();
        this.backoff = new Backoff(10, 60, Math.max(1, Baritone.settings().altoMaxRetries.value));
    }

    @Override
    public String name() {
        return "Place a " + kind.displayName;
    }

    @Override
    public Step tick(boolean calcFailed) {
        if (!Baritone.settings().altoPlaceStations.value) {
            return Step.failure("placing stations is switched off (altoPlaceStations)");
        }
        switch (phase) {
            case FIND_SPOT: {
                Optional<SpotFinder.Spot> s = SpotFinder.find(ctx, pctx.forbiddenArea);
                if (s.isEmpty()) {
                    return Step.failure("no safe free spot within reach to place a " + kind.displayName);
                }
                spot = s.get();
                phase = Phase.HOTBAR;
                return Step.stay();
            }
            case HOTBAR: {
                if (hotbarSlot() >= 0) {
                    phase = Phase.AIM;
                    ticks = 0;
                    return Step.stay();
                }
                if (McItems.count(ctx.player().getInventory(), item) <= 0) {
                    return Step.failure("no " + kind.displayName + " in the inventory");
                }
                if (hotbar == null) {
                    ItemKey key = McItems.key(item);
                    hotbar = new MenuGoalTask(alto, "Put the " + kind.displayName + " on the hotbar",
                            MenuGoals.toHotbar(key, k -> !(McItems.item(k) instanceof TieredItem) && !pctx.isReserved(k)));
                }
                Step s = hotbar.tick(calcFailed);
                if (s.kind() == Step.Kind.FAILURE) {
                    return s;
                }
                if (s.kind() == Step.Kind.SUCCESS) {
                    hotbar.cancel();
                    hotbar = null;
                }
                return Step.stay();
            }
            case AIM:
                return aimAndPlace();
            case WAIT: {
                if (ctx.world().getBlockState(spot.pos()).is(StationManager.blockFor(kind))) {
                    alto.stations().markPlaced(kind, spot.pos());
                    pctx.placedStation(kind, spot.pos());
                    return Step.success("placed at " + spot.pos().toShortString());
                }
                if (++ticks > 20 + alto.lag().confirmTicks(Baritone.settings())) {
                    backoff.fail();
                    if (backoff.exhausted()) {
                        return Step.failure("the " + kind.displayName + " did not appear after " + backoff.failures() + " tries");
                    }
                    phase = Phase.FIND_SPOT;
                }
                return Step.stay();
            }
            default:
                return Step.failure("unknown phase");
        }
    }

    private Step aimAndPlace() {
        if (backoff.waiting()) {
            return Step.stay();
        }
        double reach = ctx.playerController().getBlockReachDistance();
        Vec3 top = new Vec3(spot.support().getX() + 0.5, spot.support().getY() + 1.0, spot.support().getZ() + 0.5);
        Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, spot.support(), top, reach, false);
        if (rot.isEmpty()) {
            phase = Phase.FIND_SPOT;
            return Step.stay();
        }
        int slot = hotbarSlot();
        if (slot < 0) {
            phase = Phase.HOTBAR;
            return Step.stay();
        }
        ctx.player().getInventory().selected = slot;
        alto.baritone().getLookBehavior().updateTarget(rot.get(), true);
        HitResult hit = ctx.objectMouseOver();
        if (!(hit instanceof BlockHitResult bhr) || !bhr.getBlockPos().relative(bhr.getDirection()).equals(spot.pos())) {
            if (++ticks > 40) {
                ticks = 0;
                phase = Phase.FIND_SPOT;
            }
            return Step.stay();
        }
        if (ctx.player().isShiftKeyDown() || !alto.pacer().tryAcquire(Pacer.Kind.BLOCK_USE)) {
            return Step.stay();
        }
        InteractionResult r = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, bhr);
        if (r.shouldSwing()) {
            ctx.player().swing(InteractionHand.MAIN_HAND);
        }
        AltoLog.debug("place " + kind + " at " + spot.pos().toShortString() + " -> " + r);
        phase = Phase.WAIT;
        ticks = 0;
        return Step.stay();
    }

    private int hotbarSlot() {
        Inventory inv = ctx.player().getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }
}
