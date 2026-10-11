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
import baritone.alto.task.AltoTask;
import baritone.alto.task.Step;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

/**
 * {@code #travel <dimension> <x> [y] <z>} / {@code #goto <x> [y] <z> <dimension>}: walk to the best known nether
 * portal, step in, wait for the normal portal teleport, then walk to the target in the other dimension.
 */
public final class TravelTask extends AltoTask {

    private enum Phase { ROUTE, LEAVE_PORTAL, TO_PORTAL, IN_PORTAL, FINAL }

    private final String targetDim;
    private final int x;
    private final Integer y;
    private final int z;
    private Phase phase = Phase.ROUTE;
    private PortalBook.Route route;
    private BlockPos portalBlock;
    private String startDim;
    private int waited;
    private int failures;
    private int hops;

    public TravelTask(Alto alto, String targetDim, int x, Integer y, int z) {
        super(alto);
        this.targetDim = targetDim;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public String name() {
        return "Travel to " + x + (y == null ? "" : " " + y) + " " + z + " in the " + PortalBook.shortDim(targetDim);
    }

    private String dim() {
        return ctx.world().dimension().location().toString();
    }

    @Override
    public Step tick(boolean calcFailed) {
        switch (phase) {
            case ROUTE: {
                if (dim().equals(targetDim)) {
                    phase = Phase.FINAL;
                    return Step.stay();
                }
                if (++hops > 3) {
                    return Step.failure("still not in the " + PortalBook.shortDim(targetDim) + " after 3 portals");
                }
                Optional<PortalBook.Route> r = alto.portals().book().best(dim(), ctx.player().getX(), ctx.player().getY(),
                        ctx.player().getZ(), targetDim, x, z);
                if (r.isEmpty()) {
                    return Step.failure(Double.isNaN(PortalBook.scale(dim(), targetDim))
                            ? "Alto can only travel between the overworld and the nether"
                            : "no known nether portal here. Walk through one once (or come within a few chunks of it) so Alto learns it");
                }
                route = r.get();
                startDim = dim();
                detail("via the portal at " + route.portal());
                // Just arrived (standing in the arrival portal): vanilla keeps the portal cooldown running while you
                // stay inside, so step out first and come back in.
                phase = inPortal() ? Phase.LEAVE_PORTAL : Phase.TO_PORTAL;
                waited = 0;
                return Step.stay();
            }
            case LEAVE_PORTAL: {
                detail("stepping out of the portal");
                if (!inPortal()) {
                    if (++waited > 30) {
                        phase = Phase.TO_PORTAL;
                    }
                    return Step.stay();
                }
                waited = 0;
                if (calcFailed && ++failures > 3) {
                    return Step.failure("cannot step out of the portal");
                }
                return Step.walk(new baritone.api.pathing.goals.GoalRunAway(2, ctx.player().blockPosition()));
            }
            case TO_PORTAL: {
                if (!dim().equals(startDim)) {
                    phase = Phase.ROUTE; // teleported early (walked through another portal)
                    return Step.stay();
                }
                portalBlock = alto.portals().standingSpot(ctx.world(), route.portal());
                if (portalBlock == null) {
                    phase = Phase.ROUTE; // that portal is gone: try another one
                    return Step.stay();
                }
                if (inPortal()) {
                    phase = Phase.IN_PORTAL;
                    waited = 0;
                    return Step.stay();
                }
                if (calcFailed && ++failures > 3) {
                    return Step.failure("cannot reach the portal at " + route.portal());
                }
                return Step.walk(new GoalBlock(portalBlock));
            }
            case IN_PORTAL: {
                detail("waiting for the portal");
                if (!dim().equals(startDim)) {
                    phase = Phase.ROUTE; // arrived; the next tick plans the rest (or another portal)
                    return Step.stay();
                }
                if (!inPortal()) {
                    phase = Phase.TO_PORTAL;
                    return Step.stay();
                }
                if (++waited > 20 * 8) {
                    if (++failures > 2) {
                        return Step.failure("the portal did not teleport (tried 3 times)");
                    }
                    phase = Phase.LEAVE_PORTAL; // still on cooldown: step out and in again
                    waited = 0;
                    return Step.stay();
                }
                return Step.stay(); // stand still; the normal portal timer does the rest
            }
            case FINAL: {
                detail("walking to the target");
                Goal goal = y == null ? new GoalXZ(x, z) : new GoalBlock(x, y, z);
                if (goal.isInGoal(ctx.playerFeet())) {
                    return Step.success("arrived");
                }
                if (calcFailed && ++failures > 5) {
                    return Step.failure("cannot find a path to the target");
                }
                return Step.walk(goal);
            }
            default:
                return Step.failure("unknown phase");
        }
    }

    private boolean inPortal() {
        BlockPos feet = ctx.player().blockPosition();
        return ctx.world().getBlockState(feet).is(Blocks.NETHER_PORTAL);
    }
}
