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

import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.RotationUtils;
import net.minecraft.core.BlockPos;

/**
 * Walks until a block is within reach and visible, then reports arrival. Fails after repeated path failures.
 */
public final class Approach {

    private final BlockPos target;
    private int calcFailures;
    private int stillTicks;

    public Approach(BlockPos target) {
        this.target = target;
    }

    /**
     * @return {@code null} when the block is in reach and the player stands still; otherwise the step to return
     */
    public Step tick(IPlayerContext ctx, boolean calcFailed) {
        double reach = ctx.playerController().getBlockReachDistance();
        boolean inReach = ctx.playerFeet().distSqr(target) <= (reach + 1) * (reach + 1)
                && RotationUtils.reachable(ctx, target, reach).isPresent();
        if (inReach) {
            if (ctx.player().getDeltaMovement().horizontalDistanceSqr() < 1.0E-4 || ++stillTicks > 10) {
                return null;
            }
            return Step.stay();
        }
        stillTicks = 0;
        if (calcFailed && ++calcFailures >= 3) {
            return Step.failure("cannot find a path to " + target.toShortString());
        }
        return Step.walk(new GoalGetToBlock(target));
    }

    public BlockPos target() {
        return target;
    }
}
