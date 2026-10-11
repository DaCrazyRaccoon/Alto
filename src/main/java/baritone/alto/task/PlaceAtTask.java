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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/**
 * Places a block from the inventory at one exact position, by right-clicking a face of a solid neighbour (a normal
 * placement). Used for portal frames. Not sneaking, so the neighbour must not be a block that opens a menu.
 */
public final class PlaceAtTask extends AltoTask {

    private final BlockPos pos;
    private final Predicate<Item> item;
    private final String label;
    private int ticks;
    private int tries;
    private MenuGoalTask hotbar;
    /** Where to stand while placing: blocks above head height are reached from the ground (never by pillaring). */
    private Integer standY;

    public PlaceAtTask(Alto alto, BlockPos pos, Predicate<Item> item, String label) {
        super(alto);
        this.pos = pos.immutable();
        this.item = item;
        this.label = label;
    }

    public PlaceAtTask standingAt(int y) {
        this.standY = y;
        return this;
    }

    @Override
    public String name() {
        return "Place " + label + " at " + pos.toShortString();
    }

    private int hotbarSlot() {
        var inv = ctx.player().getInventory();
        for (int i = 0; i < 9; i++) {
            if (!inv.getItem(i).isEmpty() && item.test(inv.getItem(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public Step tick(boolean calcFailed) {
        BlockState there = ctx.world().getBlockState(pos);
        if (!there.isAir() && !there.canBeReplaced()) {
            return there.getBlock().asItem() instanceof BlockItem bi && item.test(bi)
                    ? Step.success("placed") : Step.failure("something else is at " + pos.toShortString());
        }
        if (++ticks > 20 * 30) {
            return Step.failure("could not place " + label + " at " + pos.toShortString());
        }
        int slot = hotbarSlot();
        if (slot < 0) {
            // Bring it to the hotbar from the main inventory (number-key swap, paced like every click).
            if (hotbar == null) {
                var inv = ctx.player().getInventory();
                for (int i = 9; i < 36 && hotbar == null; i++) {
                    if (!inv.getItem(i).isEmpty() && item.test(inv.getItem(i).getItem())) {
                        hotbar = new MenuGoalTask(alto, "Put " + label + " on the hotbar", baritone.alto.container.MenuGoals.toHotbar(
                                McItems.key(inv.getItem(i).getItem()), k -> !(McItems.item(k) instanceof net.minecraft.world.item.TieredItem)));
                    }
                }
                if (hotbar == null) {
                    return Step.failure("no " + label + " in the inventory");
                }
            }
            Step h = hotbar.tick(calcFailed);
            if (h.finished()) {
                hotbar.cancel();
                hotbar = null;
                if (h.kind() == Step.Kind.FAILURE) {
                    return h;
                }
            }
            return Step.stay();
        }
        double reach = ctx.playerController().getBlockReachDistance();
        Vec3 eye = ctx.player().getEyePosition();
        for (Direction d : Direction.values()) {
            BlockPos against = pos.relative(d);
            BlockState s = ctx.world().getBlockState(against);
            if (s.isAir() || s.canBeReplaced() || !s.getFluidState().isEmpty()) {
                continue;
            }
            Direction face = d.getOpposite();
            Vec3 hitVec = Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
            if (eye.distanceTo(hitVec) > reach) {
                continue;
            }
            Rotation r = RotationUtils.calcRotationFromVec3d(eye, hitVec, ctx.playerRotations());
            HitResult ray = RayTraceUtils.rayTraceTowards(ctx.player(), r, reach);
            if (!(ray instanceof BlockHitResult b) || !b.getBlockPos().equals(against) || b.getDirection() != face) {
                continue;
            }
            ctx.player().getInventory().selected = slot;
            alto.baritone().getLookBehavior().updateTarget(r, true);
            HitResult now = ctx.objectMouseOver();
            if (now instanceof BlockHitResult nb && nb.getBlockPos().equals(against) && nb.getDirection() == face
                    && !ctx.player().isShiftKeyDown() && alto.pacer().tryAcquire(Pacer.Kind.BLOCK_USE)) {
                InteractionResult res = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, nb);
                if (res.shouldSwing()) {
                    ctx.player().swing(InteractionHand.MAIN_HAND);
                }
            }
            return Step.stay();
        }
        if (calcFailed && ++tries > 3) {
            return Step.failure("cannot get close to " + pos.toShortString());
        }
        return Step.walk(new GoalNear(standY == null ? pos : new BlockPos(pos.getX(), standY, pos.getZ()), 2));
    }

    @Override
    public void cancel() {
        if (hotbar != null) {
            hotbar.cancel();
        }
    }
}
