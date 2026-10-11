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
import baritone.alto.container.MenuGoal;
import baritone.alto.container.MenuGoals;
import baritone.alto.container.SlotRole;
import baritone.alto.container.mc.ContainerSession;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * Walks to your own corpse (Corpse mod), opens it with a normal right-click and shift-clicks the items back into
 * your inventory, paced like every other container click. Stops when your inventory is full. Clients are not told
 * what a corpse holds, so an older, already emptied corpse is opened, seen to be empty and skipped for the next one.
 */
public final class RecoverCorpseTask extends AltoTask {

    private enum Phase { FIND, APPROACH, OPEN, TAKE, CLOSE }

    private static final int MAX_CORPSES = 8;

    private final ContainerSession session;
    private final java.util.Set<Integer> checked = new java.util.HashSet<>();
    private Phase phase = Phase.FIND;
    private Entity corpse;
    private MenuGoal goal;
    private String result = "";
    private boolean empty;
    private int recovered;
    private int tries;

    public RecoverCorpseTask(Alto alto) {
        super(alto);
        this.session = new ContainerSession(alto);
    }

    @Override
    public String name() {
        return "Recover your items from your corpse";
    }

    @Override
    public Step tick(boolean calcFailed) {
        if (!Baritone.settings().altoContainers.value) {
            return Step.failure("container automation is switched off (altoContainers)");
        }
        switch (phase) {
            case FIND: {
                corpse = checked.size() >= MAX_CORPSES ? null : alto.nearestOwnCorpse(checked).orElse(null);
                if (corpse == null) {
                    if (recovered > 0) {
                        return Step.success("recovered " + recovered + " item(s)");
                    }
                    return Step.failure(checked.isEmpty() ? "no corpse of yours is loaded nearby" : "your corpses nearby are empty");
                }
                tries = 0;
                goal = null;
                empty = false;
                phase = Phase.APPROACH;
                return Step.stay();
            }
            case APPROACH: {
                if (corpse.isRemoved()) {
                    checked.add(corpse.getId());
                    phase = Phase.FIND;
                    return Step.stay();
                }
                detail("walking to the corpse at " + corpse.blockPosition().toShortString());
                double range = ctx.player().entityInteractionRange() - 0.5;
                if (ctx.player().getEyePosition().distanceTo(corpse.getBoundingBox().getCenter()) <= range) {
                    session.openEntity(corpse, "corpse", m -> !(m instanceof InventoryMenu));
                    phase = Phase.OPEN;
                    return Step.stay();
                }
                if (calcFailed && ++tries > 3) {
                    return Step.failure("cannot reach the corpse");
                }
                return Step.walk(new GoalNear(corpse.blockPosition(), 1));
            }
            case OPEN: {
                detail("opening the corpse");
                ContainerSession.Status st = session.tickOpen();
                if (st == ContainerSession.Status.FAILED) {
                    return Step.failure(session.failure());
                }
                if (st == ContainerSession.Status.READY) {
                    phase = Phase.TAKE;
                }
                return Step.stay();
            }
            case TAKE: {
                detail("taking the items back");
                if (goal == null) {
                    int inside = session.view().contents(java.util.EnumSet.of(SlotRole.STORAGE)).total();
                    if (inside == 0) {
                        empty = true; // an older corpse that was emptied before
                        phase = Phase.CLOSE;
                        return Step.stay();
                    }
                    recovered += inside;
                    goal = MenuGoals.clear(MenuGoals.roles(SlotRole.STORAGE), "corpse");
                }
                ContainerSession.Status st = session.run(goal);
                if (st == ContainerSession.Status.FAILED) {
                    result = session.failure();
                    phase = Phase.CLOSE;
                } else if (st == ContainerSession.Status.DONE) {
                    phase = Phase.CLOSE;
                }
                return Step.stay();
            }
            case CLOSE: {
                if (session.close() != ContainerSession.Status.DONE) {
                    return Step.stay();
                }
                if (empty) {
                    checked.add(corpse.getId());
                    phase = Phase.FIND;
                    return Step.stay();
                }
                return result.isEmpty() ? Step.success("all items recovered (" + recovered + ")") : Step.failure(result);
            }
            default:
                return Step.failure("unknown phase");
        }
    }

    @Override
    public void cancel() {
        session.closeNow();
    }
}
