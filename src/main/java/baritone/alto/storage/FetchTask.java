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

package baritone.alto.storage;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.container.MenuGoal;
import baritone.alto.container.MenuGoals;
import baritone.alto.container.MenuView;
import baritone.alto.container.SlotRole;
import baritone.alto.container.mc.ContainerAdapters;
import baritone.alto.container.mc.ContainerSession;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import baritone.alto.task.AltoTask;
import baritone.alto.task.Approach;
import baritone.alto.task.Step;
import baritone.api.BaritoneAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Blocks;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Walks to a known container, takes items, and records what is left (feature F fetch / extra 2 restock).
 * If the container no longer has enough, the task fails with how many it got, and the plan is made again.
 */
public final class FetchTask extends AltoTask {

    private enum Phase { LOCATE, APPROACH, OPEN, TAKE, CLOSE }

    private final ContainerRecord.Key key;
    private final Predicate<ItemKey> match;
    private final String label;
    private final int count;
    private final ContainerSession session;
    private Phase phase = Phase.LOCATE;
    private BlockPos pos;
    private Approach approach;
    private int before;
    private int got;

    public FetchTask(Alto alto, ContainerRecord.Key key, ItemKey item, int count) {
        this(alto, key, item::equals, item.shortName(), count);
    }

    public FetchTask(Alto alto, ContainerRecord.Key key, Predicate<ItemKey> match, String label, int count) {
        super(alto);
        this.key = key;
        this.match = match;
        this.label = label;
        this.count = count;
        this.session = new ContainerSession(alto);
    }

    @Override
    public String name() {
        return "Fetch " + count + "x " + label;
    }

    @Override
    public Step tick(boolean calcFailed) {
        if (!Baritone.settings().altoContainers.value) {
            return Step.failure("container automation is switched off (altoContainers)");
        }
        switch (phase) {
            case LOCATE: {
                if (key.isEnder()) {
                    List<BlockPos> found = BaritoneAPI.getProvider().getWorldScanner()
                            .scanChunkRadius(ctx, List.of(Blocks.ENDER_CHEST), 16, -1, 4);
                    BlockPos feet = ctx.playerFeet();
                    pos = found.stream().min(Comparator.comparingDouble(p -> p.distSqr(feet))).orElse(null);
                    if (pos == null) {
                        return Step.failure("no ender chest nearby");
                    }
                } else {
                    if (!key.dimension().equals(alto.storage().dimension())) {
                        return Step.failure("the container is in another dimension (" + key.dimension() + ")");
                    }
                    pos = new BlockPos(key.x(), key.y(), key.z());
                }
                before = McItems.inventoryCounts(ctx.player().getInventory()).asMap().entrySet().stream()
                        .filter(e -> match.test(e.getKey())).mapToInt(java.util.Map.Entry::getValue).sum();
                approach = new Approach(pos);
                phase = Phase.APPROACH;
                return Step.stay();
            }
            case APPROACH: {
                detail("walking to " + pos.toShortString());
                if (!ctx.world().isLoaded(pos)) {
                    return Step.walk(new baritone.api.pathing.goals.GoalGetToBlock(pos));
                }
                if (!key.isEnder() && alto.storage().index().get(key) != null
                        && ctx.world().getBlockState(pos).isAir()) {
                    alto.storage().index().remove(key);
                    return Step.failure("the container at " + pos.toShortString() + " is gone");
                }
                Step s = approach.tick(ctx, calcFailed);
                if (s != null) {
                    return s;
                }
                session.openBlock(pos, "container", m -> !(m instanceof InventoryMenu) && ContainerAdapters.forMenu(m).indexable(m));
                phase = Phase.OPEN;
                return Step.stay();
            }
            case OPEN: {
                detail("opening");
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
                detail("taking " + label);
                MenuGoal goal = takeGoal == null ? (takeGoal = MenuGoals.take(match, label, count, MenuGoals.roles(SlotRole.STORAGE), false)) : takeGoal;
                ContainerSession.Status st = session.run(goal);
                if (st == ContainerSession.Status.FAILED) {
                    alto.storage().recorder().recordNow();
                    return Step.failure(session.failure());
                }
                if (st == ContainerSession.Status.DONE) {
                    MenuView v = session.view();
                    got = v.count(match, MenuView.PLAYER_STORAGE) - before;
                    alto.storage().recorder().recordNow();
                    phase = Phase.CLOSE;
                }
                return Step.stay();
            }
            case CLOSE: {
                if (session.close() != ContainerSession.Status.DONE) {
                    return Step.stay();
                }
                if (got < count) {
                    return Step.failure("only found " + Math.max(0, got) + "/" + count + " " + label + " there");
                }
                return Step.success("took " + got + "x " + label);
            }
            default:
                return Step.failure("unknown phase");
        }
    }

    private MenuGoal takeGoal;

    @Override
    public void cancel() {
        session.closeNow();
    }
}
