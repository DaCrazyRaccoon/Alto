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

import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.alto.container.MenuGoals;
import baritone.alto.core.ItemKey;
import baritone.alto.core.Pacer;
import baritone.alto.mc.McItems;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Feature G "use item on block": hold an item (or an empty hand), look at a block face and right-click once, e.g.
 * put an item on a Create depot or into a basin. Not sneaking, one paced click, nothing else.
 */
public final class UseItemOnBlockTask extends AltoTask {

    private enum Phase { HOTBAR, APPROACH, AIM, DONE }

    private final BlockPos pos;
    private final Direction face;
    private final ItemKey item;
    private Phase phase = Phase.HOTBAR;
    private MenuGoalTask hotbar;
    private int ticks;
    private int tries;

    public UseItemOnBlockTask(Alto alto, BlockPos pos, Direction face, ItemKey item) {
        super(alto);
        this.pos = pos.immutable();
        this.face = face;
        this.item = item;
    }

    @Override
    public String name() {
        return "Use " + (item.isEmpty() ? "an empty hand" : item.shortName()) + " on " + pos.toShortString() + " (" + face.getName() + ")";
    }

    @Override
    public Step tick(boolean calcFailed) {
        switch (phase) {
            case HOTBAR: {
                if (slot() >= 0) {
                    phase = Phase.APPROACH;
                    return Step.stay();
                }
                if (item.isEmpty()) {
                    return Step.failure("no empty hotbar slot");
                }
                if (McItems.count(ctx.player().getInventory(), McItems.item(item)) <= 0) {
                    return Step.failure("no " + item.shortName() + " in the inventory");
                }
                if (hotbar == null) {
                    hotbar = new MenuGoalTask(alto, "Put " + item.shortName() + " on the hotbar",
                            MenuGoals.toHotbar(McItems.key(McItems.item(item)), k -> !(McItems.item(k) instanceof TieredItem)));
                }
                Step s = hotbar.tick(calcFailed);
                if (s.finished()) {
                    hotbar.cancel();
                    hotbar = null;
                    if (s.kind() == Step.Kind.FAILURE) {
                        return s;
                    }
                }
                return Step.stay();
            }
            case APPROACH: {
                if (aim() != null) {
                    phase = Phase.AIM;
                    ticks = 0;
                    return Step.stay();
                }
                if (calcFailed && ++tries > 3) {
                    return Step.failure("cannot reach the " + face.getName() + " side of " + pos.toShortString());
                }
                return Step.walk(new GoalNear(pos.relative(face, 2), 1));
            }
            case AIM: {
                Rotation r = aim();
                if (r == null) {
                    phase = Phase.APPROACH;
                    return Step.stay();
                }
                int slot = slot();
                if (slot < 0) {
                    phase = Phase.HOTBAR;
                    return Step.stay();
                }
                Inventory inv = ctx.player().getInventory();
                inv.selected = slot;
                alto.baritone().getLookBehavior().updateTarget(r, true);
                HitResult hit = ctx.objectMouseOver();
                if (!(hit instanceof BlockHitResult b) || !b.getBlockPos().equals(pos) || b.getDirection() != face) {
                    if (++ticks > 40) {
                        return Step.failure("could not aim at the " + face.getName() + " face of " + pos.toShortString());
                    }
                    return Step.stay();
                }
                if (ctx.player().isShiftKeyDown() || !alto.pacer().tryAcquire(Pacer.Kind.BLOCK_USE)) {
                    return Step.stay();
                }
                InteractionResult res = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, b);
                if (res.shouldSwing()) {
                    ctx.player().swing(InteractionHand.MAIN_HAND);
                }
                AltoLog.debug("use " + item + " on " + pos.toShortString() + " -> " + res);
                phase = Phase.DONE;
                return Step.stay();
            }
            default:
                return Step.success("used");
        }
    }

    private int slot() {
        Inventory inv = ctx.player().getInventory();
        for (int i = 0; i < 9; i++) {
            boolean match = item.isEmpty() ? inv.getItem(i).isEmpty() : inv.getItem(i).is(McItems.item(item));
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private Rotation aim() {
        double reach = ctx.playerController().getBlockReachDistance();
        Vec3 eye = ctx.player().getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
        if (eye.distanceTo(center) > reach) {
            return null;
        }
        Rotation r = RotationUtils.calcRotationFromVec3d(eye, center, ctx.playerRotations());
        HitResult hit = RayTraceUtils.rayTraceTowards(ctx.player(), r, reach);
        return hit instanceof BlockHitResult b && b.getBlockPos().equals(pos) && b.getDirection() == face ? r : null;
    }

    @Override
    public void cancel() {
        if (hotbar != null) {
            hotbar.cancel();
        }
    }
}
