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
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Breaks a block Alto placed (with the best tool, through Baritone's own block breaking) and walks over its drop.
 * Refuses when the held tool would make the block drop nothing.
 */
public final class BreakAndCollectTask extends AltoTask {

    private enum Phase { APPROACH, BREAK, COLLECT }

    private final BlockPos pos;
    private final Block block;
    private final Predicate<Item> dropMatches;
    private final String label;
    private Phase phase = Phase.APPROACH;
    private Approach approach;
    private int before;
    private int ticks;

    public BreakAndCollectTask(Alto alto, BlockPos pos, Block block, Predicate<Item> dropMatches, String label) {
        super(alto);
        this.pos = pos;
        this.block = block;
        this.dropMatches = dropMatches;
        this.label = label;
        this.approach = new Approach(pos);
    }

    @Override
    public String name() {
        return "Pick up the " + label;
    }

    private int count() {
        int n = 0;
        for (var s : ctx.player().getInventory().items) {
            if (!s.isEmpty() && dropMatches.test(s.getItem())) {
                n += s.getCount();
            }
        }
        return n;
    }

    @Override
    public Step tick(boolean calcFailed) {
        switch (phase) {
            case APPROACH: {
                if (!ctx.world().getBlockState(pos).is(block)) {
                    return Step.success("already gone");
                }
                Step s = approach.tick(ctx, calcFailed);
                if (s != null) {
                    return s;
                }
                before = count();
                phase = Phase.BREAK;
                ticks = 0;
                return Step.stay();
            }
            case BREAK: {
                BlockState state = ctx.world().getBlockState(pos);
                if (!state.is(block)) {
                    alto.baritone().getInputOverrideHandler().clearAllKeys();
                    phase = Phase.COLLECT;
                    ticks = 0;
                    return Step.stay();
                }
                MovementHelper.switchToBestToolFor(ctx, state);
                if (state.requiresCorrectToolForDrops() && !ctx.player().hasCorrectToolForDrops(state)) {
                    return Step.failure("no tool that makes the " + label + " drop; it stays in place");
                }
                Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
                if (rot.isEmpty()) {
                    approach = new Approach(pos);
                    phase = Phase.APPROACH;
                    return Step.stay();
                }
                alto.baritone().getLookBehavior().updateTarget(rot.get(), true);
                if (ctx.isLookingAt(pos)) {
                    alto.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                }
                if (++ticks > 20 * 30) {
                    return Step.failure("could not break the " + label);
                }
                return Step.stay();
            }
            case COLLECT: {
                if (count() > before) {
                    return Step.success("collected");
                }
                if (++ticks > 20 * 15) {
                    return Step.failure("the dropped " + label + " was not picked up");
                }
                List<ItemEntity> drops = ctx.world().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4),
                        e -> dropMatches.test(e.getItem().getItem()));
                if (drops.isEmpty()) {
                    return Step.stay();
                }
                ItemEntity nearest = drops.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(ctx.player()))).orElseThrow();
                if (nearest.getY() < pos.getY() - 2) {
                    return Step.failure("the dropped " + label + " fell away; not digging after it");
                }
                if (nearest.distanceToSqr(ctx.player()) < 1.0) {
                    return Step.stay();
                }
                return Step.walk(new GoalBlock(nearest.blockPosition()));
            }
            default:
                return Step.failure("unknown phase");
        }
    }

    @Override
    public void cancel() {
        if (phase == Phase.BREAK) {
            alto.baritone().getInputOverrideHandler().clearAllKeys();
        }
    }
}
