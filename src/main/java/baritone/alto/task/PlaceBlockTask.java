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
import baritone.alto.station.SpotFinder;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Places one specific item (exact {@link ItemKey}, so "this shulker box" and not any shulker box) as a block at a
 * safe spot next to the player with a normal right-click, then waits until the block appears.
 */
public final class PlaceBlockTask extends AltoTask {

    private enum Phase { FIND_SPOT, HOTBAR, AIM, WAIT }

    private final ItemKey item;
    private final boolean airAbove;
    private final Predicate<BlockPos> forbidden;
    private final String label;
    private Phase phase = Phase.FIND_SPOT;
    private SpotFinder.Spot spot;
    private MenuGoalTask hotbar;
    private final Backoff backoff;
    private int ticks;
    private BlockPos placedAt;

    public PlaceBlockTask(Alto alto, ItemKey item, String label, boolean airAbove, Predicate<BlockPos> forbidden) {
        super(alto);
        this.item = item;
        this.label = label;
        this.airAbove = airAbove;
        this.forbidden = forbidden;
        this.backoff = new Backoff(10, 60, Math.max(1, Baritone.settings().altoMaxRetries.value));
    }

    @Override
    public String name() {
        return "Place " + label;
    }

    public BlockPos placedAt() {
        return placedAt;
    }

    @Override
    public Step tick(boolean calcFailed) {
        if (!(McItems.item(item) instanceof BlockItem bi)) {
            return Step.failure(item.shortName() + " cannot be placed");
        }
        switch (phase) {
            case FIND_SPOT: {
                Optional<SpotFinder.Spot> s = SpotFinder.find(ctx, forbidden, airAbove);
                if (s.isEmpty()) {
                    return Step.failure("no safe free spot within reach for the " + label);
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
                if (hotbar == null) {
                    hotbar = new MenuGoalTask(alto, "Put the " + label + " on the hotbar",
                            MenuGoals.toHotbar(item, k -> !(McItems.item(k) instanceof TieredItem)));
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
            case AIM: {
                if (backoff.waiting()) {
                    return Step.stay();
                }
                double reach = ctx.playerController().getBlockReachDistance();
                Vec3 top = new Vec3(spot.support().getX() + 0.5, spot.support().getY() + 1.0, spot.support().getZ() + 0.5);
                Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, spot.support(), top, reach, false);
                int slot = hotbarSlot();
                if (rot.isEmpty() || slot < 0) {
                    phase = slot < 0 ? Phase.HOTBAR : Phase.FIND_SPOT;
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
                AltoLog.debug("place " + label + " at " + spot.pos().toShortString() + " -> " + r);
                phase = Phase.WAIT;
                ticks = 0;
                return Step.stay();
            }
            case WAIT: {
                if (ctx.world().getBlockState(spot.pos()).is(bi.getBlock())) {
                    placedAt = spot.pos();
                    return Step.success("placed at " + spot.pos().toShortString());
                }
                if (++ticks > 20 + alto.lag().confirmTicks(Baritone.settings())) {
                    backoff.fail();
                    if (backoff.exhausted()) {
                        return Step.failure("the " + label + " did not appear after " + backoff.failures() + " tries");
                    }
                    phase = Phase.FIND_SPOT;
                }
                return Step.stay();
            }
            default:
                return Step.failure("unknown phase");
        }
    }

    private int hotbarSlot() {
        Inventory inv = ctx.player().getInventory();
        for (int i = 0; i < 9; i++) {
            if (McItems.key(inv.getItem(i)).equals(item)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void cancel() {
        if (hotbar != null) {
            hotbar.cancel();
        }
    }
}
