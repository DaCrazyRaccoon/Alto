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
import baritone.alto.container.MenuView;
import baritone.alto.container.SlotRole;
import baritone.alto.container.SlotView;
import baritone.alto.container.mc.ContainerSession;
import baritone.alto.core.ItemKey;
import baritone.alto.planner.RecipeDef;
import baritone.alto.planner.StationKind;
import baritone.alto.station.StationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractFurnaceMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Feature E, part 1: loads input and fuel into one or more furnaces (blast furnace / smoker by recipe type), then
 * hands each furnace over as a {@link PlanContext.PendingSmelt}, so the plan can do other steps while it cooks.
 * {@link SmeltCollectTask} takes the output later. Busy furnaces (someone else's items inside) are never used.
 */
public final class SmeltTask extends AltoTask {

    private enum Phase { CHOOSE, APPROACH, OPEN, INPUT, FUEL, CLOSE }

    private final RecipeDef recipe;
    private final int operations;
    private final StationKind kind;
    private final ItemKey fuel;
    private final int fuelCount;
    private final PlanContext pctx;
    private final ContainerSession session;
    private final List<BlockPos> furnaces = new ArrayList<>();
    private Phase phase = Phase.CHOOSE;
    private int furnaceIndex;
    private int share;
    private int fuelShare;
    private Approach approach;
    private MenuGoal goal;
    private ItemKey inputItem;

    public SmeltTask(Alto alto, RecipeDef recipe, int operations, StationKind kind, ItemKey fuel, int fuelCount, PlanContext pctx) {
        super(alto);
        this.recipe = recipe;
        this.operations = operations;
        this.kind = kind;
        this.fuel = fuel;
        this.fuelCount = fuelCount;
        this.pctx = pctx;
        this.session = new ContainerSession(alto);
    }

    @Override
    public String name() {
        return "Smelt " + operations + "x " + recipe.inputs().get(0).displayLabel() + " into " + recipe.output().shortName();
    }

    @Override
    public Step tick(boolean calcFailed) {
        if (!Baritone.settings().altoSmelting.value) {
            return Step.failure("smelting is switched off (altoSmelting)");
        }
        switch (phase) {
            case CHOOSE:
                return choose();
            case APPROACH: {
                Step s = approach.tick(ctx, calcFailed);
                if (s != null) {
                    detail("walking to the " + kind.displayName);
                    return s;
                }
                session.openBlock(furnaces.get(furnaceIndex), kind.displayName, m -> m instanceof AbstractFurnaceMenu);
                phase = Phase.OPEN;
                return Step.stay();
            }
            case OPEN: {
                ContainerSession.Status st = session.tickOpen();
                if (st == ContainerSession.Status.FAILED) {
                    return Step.failure(session.failure());
                }
                if (st == ContainerSession.Status.READY) {
                    MenuView v = session.view();
                    String busy = busy(v);
                    if (busy != null) {
                        if (furnaces.size() > 1 && furnaceIndex + 1 < furnaces.size()) {
                            session.closeNow();
                            furnaces.remove(furnaceIndex);
                            share = split(operations, furnaces.size(), furnaceIndex);
                            approach = new Approach(furnaces.get(furnaceIndex));
                            phase = Phase.APPROACH;
                            return Step.stay();
                        }
                        return Step.failure("the " + kind.displayName + " is busy (" + busy + ")");
                    }
                    inputItem = pickInput(v);
                    if (inputItem == null) {
                        return Step.failure("no " + recipe.inputs().get(0).displayLabel() + " in the inventory");
                    }
                    goal = MenuGoals.insert(slot(v, SlotRole.FURNACE_INPUT).index(), inputItem, share);
                    phase = Phase.INPUT;
                }
                return Step.stay();
            }
            case INPUT: {
                detail("loading " + share + "x " + inputItem.shortName());
                ContainerSession.Status st = session.run(goal);
                if (st == ContainerSession.Status.FAILED) {
                    return Step.failure(session.failure());
                }
                if (st == ContainerSession.Status.DONE) {
                    MenuView v = session.view();
                    SlotView fuelSlot = slot(v, SlotRole.FURNACE_FUEL);
                    if (fuel.isEmpty() || fuelShare <= 0) {
                        phase = Phase.CLOSE;
                    } else {
                        goal = MenuGoals.insert(fuelSlot.index(), fuel, fuelShare);
                        phase = Phase.FUEL;
                    }
                }
                return Step.stay();
            }
            case FUEL: {
                detail("adding " + fuelShare + "x " + fuel.shortName());
                ContainerSession.Status st = session.run(goal);
                if (st == ContainerSession.Status.FAILED) {
                    return Step.failure(session.failure());
                }
                if (st == ContainerSession.Status.DONE) {
                    phase = Phase.CLOSE;
                }
                return Step.stay();
            }
            case CLOSE: {
                if (session.close() != ContainerSession.Status.DONE) {
                    return Step.stay();
                }
                pctx.addPendingSmelt(new PlanContext.PendingSmelt(furnaces.get(furnaceIndex), kind, recipe.output(),
                        share * recipe.outputCount(), (long) share * Math.max(1, recipe.cookTicks())));
                furnaceIndex++;
                if (furnaceIndex < furnaces.size()) {
                    share = split(operations, furnaces.size(), furnaceIndex);
                    fuelShare = split(fuelCount, furnaces.size(), furnaceIndex);
                    approach = new Approach(furnaces.get(furnaceIndex));
                    phase = Phase.APPROACH;
                    return Step.stay();
                }
                return Step.success("cooking in " + furnaces.size() + " " + kind.displayName + (furnaces.size() == 1 ? "" : "s"));
            }
            default:
                return Step.failure("unknown phase");
        }
    }

    private Step choose() {
        BlockPos main = pctx.station(kind);
        if (main == null || !ctx.world().getBlockState(main).is(StationManager.blockFor(kind))) {
            Optional<BlockPos> near = alto.stations().findNearest(kind, Baritone.settings().altoStationSearchRadius.value, p -> true);
            if (near.isEmpty()) {
                return Step.failure("no " + kind.displayName + " nearby");
            }
            main = near.get();
            pctx.useStation(kind, main);
        }
        furnaces.add(main);
        int max = Math.max(1, Math.min(Baritone.settings().altoMaxFurnaces.value, operations));
        if (max > 1) {
            BlockPos center = main;
            for (BlockPos p : alto.stations().scan(kind, 24)) {
                if (furnaces.size() >= max) {
                    break;
                }
                if (!p.equals(center) && p.distSqr(center) <= 16 * 16) {
                    furnaces.add(p);
                }
            }
        }
        share = split(operations, furnaces.size(), 0);
        fuelShare = split(fuelCount, furnaces.size(), 0);
        approach = new Approach(main);
        phase = Phase.APPROACH;
        progress(0, furnaces.size());
        return Step.stay();
    }

    /** Evenly split {@code total} over {@code parts}; earlier parts get the remainder. */
    static int split(int total, int parts, int index) {
        int base = total / parts;
        return base + (index < total % parts ? 1 : 0);
    }

    /** Why this furnace cannot be used now, or null. */
    private String busy(MenuView v) {
        SlotView in = slot(v, SlotRole.FURNACE_INPUT);
        SlotView out = slot(v, SlotRole.FURNACE_RESULT);
        if (!in.isEmpty() && !recipe.inputs().get(0).accepts(in.item())) {
            return in.count() + "x " + in.item().shortName() + " cooking";
        }
        if (!out.isEmpty() && !out.item().equals(recipe.output())) {
            return out.count() + "x " + out.item().shortName() + " waiting in the output";
        }
        SlotView f = slot(v, SlotRole.FURNACE_FUEL);
        if (!f.isEmpty() && !fuel.isEmpty() && !f.item().equals(fuel)) {
            return "other fuel (" + f.item().shortName() + ") in the fuel slot";
        }
        return null;
    }

    private ItemKey pickInput(MenuView v) {
        ItemKey best = null;
        int bestCount = 0;
        for (ItemKey o : recipe.inputs().get(0).options()) {
            int n = v.count(o, MenuView.PLAYER_STORAGE) - pctx.reserved.get(o);
            if (n > bestCount) {
                best = o;
                bestCount = n;
            }
        }
        return best;
    }

    static SlotView slot(MenuView v, SlotRole role) {
        for (SlotView s : v.slots()) {
            if (s.role() == role) {
                return s;
            }
        }
        throw new IllegalStateException("menu has no " + role + " slot");
    }

    @Override
    public void cancel() {
        session.closeNow();
    }
}
