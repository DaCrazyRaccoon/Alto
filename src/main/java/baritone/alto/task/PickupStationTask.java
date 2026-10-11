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
import baritone.alto.container.SlotRole;
import baritone.alto.container.mc.ContainerSession;
import baritone.alto.mc.McItems;
import baritone.alto.planner.StationKind;
import baritone.alto.station.StationManager;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Picks up a station Alto placed: empties it first if it holds items (never loses furnace contents), breaks it
 * with the best tool (a furnace needs a pickaxe to drop, otherwise it stays), then walks over the drop.
 * Stations Alto did not place are never touched.
 */
public final class PickupStationTask extends AltoTask {

    private enum Phase { CHECK, APPROACH, OPEN, EMPTY, CLOSE, BREAK, COLLECT }

    private final StationKind kind;
    private final PlanContext pctx;
    private final Item item;
    private Phase phase = Phase.CHECK;
    private BlockPos pos;
    private Approach approach;
    private ContainerSession session;
    private int before;
    private int ticks;

    public PickupStationTask(Alto alto, StationKind kind, PlanContext pctx) {
        super(alto);
        this.kind = kind;
        this.pctx = pctx;
        this.item = StationManager.blockFor(kind).asItem();
    }

    @Override
    public String name() {
        return "Pick up the " + kind.displayName;
    }

    @Override
    public Step tick(boolean calcFailed) {
        switch (phase) {
            case CHECK: {
                pos = pctx.placed(kind);
                if (pos == null || !Baritone.settings().altoPickUpStations.value) {
                    return Step.success("nothing to pick up");
                }
                if (!alto.stations().placedByAlto(pos)) {
                    return Step.success("left in place (not placed by Alto)");
                }
                if (!ctx.world().getBlockState(pos).is(StationManager.blockFor(kind))) {
                    alto.stations().forget(pos);
                    pctx.forgetStation(kind);
                    return Step.success("already gone");
                }
                approach = new Approach(pos);
                phase = Phase.APPROACH;
                return Step.stay();
            }
            case APPROACH: {
                Step s = approach.tick(ctx, calcFailed);
                if (s != null) {
                    return s;
                }
                if (kind.isFurnaceLike()) {
                    session = new ContainerSession(alto);
                    session.openBlock(pos, kind.displayName, m -> m instanceof AbstractFurnaceMenu);
                    phase = Phase.OPEN;
                } else {
                    startBreak();
                }
                return Step.stay();
            }
            case OPEN: {
                ContainerSession.Status st = session.tickOpen();
                if (st == ContainerSession.Status.FAILED) {
                    return Step.failure(session.failure() + "; the " + kind.displayName + " stays in place");
                }
                if (st == ContainerSession.Status.READY) {
                    phase = Phase.EMPTY;
                }
                return Step.stay();
            }
            case EMPTY: {
                ContainerSession.Status st = session.run(MenuGoals.clear(
                        MenuGoals.roles(SlotRole.FURNACE_INPUT, SlotRole.FURNACE_FUEL, SlotRole.FURNACE_RESULT), kind.displayName));
                if (st == ContainerSession.Status.FAILED) {
                    session.closeNow();
                    return Step.failure(session.failure() + "; the " + kind.displayName + " stays in place with its items");
                }
                if (st == ContainerSession.Status.DONE) {
                    phase = Phase.CLOSE;
                }
                return Step.stay();
            }
            case CLOSE: {
                if (session.close() == ContainerSession.Status.DONE) {
                    startBreak();
                }
                return Step.stay();
            }
            case BREAK:
                return tickBreak();
            case COLLECT:
                return tickCollect();
            default:
                return Step.failure("unknown phase");
        }
    }

    private void startBreak() {
        before = McItems.count(ctx.player().getInventory(), item);
        phase = Phase.BREAK;
        ticks = 0;
    }

    private Step tickBreak() {
        BlockState state = ctx.world().getBlockState(pos);
        if (!state.is(StationManager.blockFor(kind))) {
            alto.baritone().getInputOverrideHandler().clearAllKeys();
            alto.stations().forget(pos);
            phase = Phase.COLLECT;
            ticks = 0;
            return Step.stay();
        }
        MovementHelper.switchToBestToolFor(ctx, state);
        if (state.requiresCorrectToolForDrops() && !ctx.player().hasCorrectToolForDrops(state)) {
            return Step.failure("no tool that makes the " + kind.displayName + " drop (needs a pickaxe); it stays in place");
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
            return Step.failure("could not break the " + kind.displayName);
        }
        return Step.stay();
    }

    private Step tickCollect() {
        int now = McItems.count(ctx.player().getInventory(), item);
        if (now > before) {
            pctx.forgetStation(kind);
            return Step.success("collected");
        }
        if (++ticks > 20 * 15) {
            pctx.forgetStation(kind);
            AltoLog.warn("Did not see the dropped " + kind.displayName + " get picked up.");
            return Step.success("broken, drop not collected");
        }
        List<ItemEntity> drops = ctx.world().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4),
                e -> e.getItem().is(item));
        if (drops.isEmpty()) {
            return Step.stay(); // the drop may not have arrived on the client yet
        }
        ItemEntity nearest = drops.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(ctx.player()))).orElseThrow();
        if (nearest.getY() < pos.getY() - 2) {
            pctx.forgetStation(kind);
            AltoLog.warn("The dropped " + kind.displayName + " fell away; not digging after it.");
            return Step.success("broken, drop fell away");
        }
        if (nearest.distanceToSqr(ctx.player()) < 1.0) {
            return Step.stay(); // pickup happens on the server; wait for the inventory update
        }
        return Step.walk(new GoalBlock(nearest.blockPosition()));
    }

    @Override
    public void cancel() {
        if (phase == Phase.BREAK) {
            alto.baritone().getInputOverrideHandler().clearAllKeys();
        }
        if (session != null) {
            session.closeNow();
        }
    }
}
