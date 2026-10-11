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
import baritone.alto.container.MenuGoals;
import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import baritone.alto.planner.Plan;
import baritone.alto.planner.PlanStep;
import baritone.alto.planner.StationKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.TieredItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Executes a {@link Plan} step by step, then optionally moves results to the hotbar, then checks that the
 * inventory really holds what was asked for.
 */
public final class PlanTask extends AltoTask {

    private final Plan plan;
    private final PlanContext pctx;
    /** Required final inventory counts (checked at the end). */
    private final ItemCounts required;
    private final List<AltoTask> tail = new ArrayList<>();
    private int index;
    private AltoTask current;
    private boolean tailBuilt;
    private boolean collecting;

    public PlanTask(Alto alto, Plan plan, PlanContext pctx, ItemCounts required) {
        super(alto);
        this.plan = plan;
        this.pctx = pctx;
        this.required = required.copy();
    }

    @Override
    public String name() {
        return "Get " + plan.targets();
    }

    public Plan plan() {
        return plan;
    }

    @Override
    public Step tick(boolean calcFailed) {
        if (!plan.isComplete()) {
            return Step.failure("cannot get everything; missing: " + plan.missing());
        }
        while (index < plan.steps().size()) {
            if (current == null) {
                PlanContext.PendingSmelt needed = pctx.pendingNeededBy(plan.steps().get(index));
                if (needed != null) {
                    current = new SmeltCollectTask(alto, needed, pctx);
                    collecting = true;
                } else {
                    current = taskFor(plan.steps().get(index));
                    if (current == null) {
                        index++;
                        continue;
                    }
                }
            }
            Step s = current.tick(calcFailed);
            detail("step " + (index + 1) + "/" + plan.steps().size() + ": " + current.name()
                    + (current.detail().isEmpty() ? "" : " - " + current.detail()));
            progress(index, plan.steps().size());
            if (s.kind() == Step.Kind.SUCCESS) {
                current.cancel();
                current = null;
                if (collecting) {
                    collecting = false; // the step that needed the output runs next
                } else {
                    index++;
                }
                return Step.stay();
            }
            if (s.kind() == Step.Kind.FAILURE) {
                return Step.failure("step " + (index + 1) + " (" + current.name() + "): " + s.message());
            }
            return s;
        }
        if (current != null || !pctx.pendingSmelts().isEmpty()) {
            // Collect whatever is still cooking before finishing.
            if (current == null) {
                current = new SmeltCollectTask(alto, pctx.pendingSmelts().get(0), pctx);
            }
            Step s = current.tick(calcFailed);
            detail(current.name() + (current.detail().isEmpty() ? "" : " - " + current.detail()));
            if (s.kind() == Step.Kind.SUCCESS) {
                current.cancel();
                current = null;
                return Step.stay();
            }
            if (s.kind() == Step.Kind.FAILURE) {
                return Step.failure(current.name() + ": " + s.message());
            }
            return s;
        }
        if (!tailBuilt) {
            tailBuilt = true;
            pctx.deliverToHotbar.forEach((item, n) -> tail.add(new MenuGoalTask(alto, "Put " + item.shortName() + " on the hotbar",
                    MenuGoals.toHotbar(item, k -> !(McItems.item(k) instanceof TieredItem) && !pctx.deliverToHotbar.asMap().containsKey(k)))));
        }
        if (!tail.isEmpty()) {
            AltoTask t = tail.get(0);
            Step s = t.tick(calcFailed);
            if (s.kind() == Step.Kind.SUCCESS || s.kind() == Step.Kind.FAILURE) {
                t.cancel();
                tail.remove(0); // a full hotbar is not fatal: the builder can still use Baritone's own hotbar moves
            }
            return Step.stay();
        }
        ItemCounts have = McItems.inventoryCounts(ctx.player().getInventory());
        for (var e : required.asMap().entrySet()) {
            if (have.get(e.getKey()) < e.getValue()) {
                return Step.failure("after the plan only " + have.get(e.getKey()) + "x " + e.getKey().shortName()
                        + " are in the inventory (wanted " + e.getValue() + ")");
            }
        }
        return Step.success("have " + required);
    }

    private AltoTask taskFor(PlanStep step) {
        if (step instanceof PlanStep.Craft c) {
            return new CraftTask(alto, c.recipe(), c.crafts(), c.station(), pctx);
        }
        if (step instanceof PlanStep.Smelt sm) {
            return new SmeltTask(alto, sm.recipe(), sm.operations(), sm.station(), sm.fuel(), sm.fuelCount(), pctx);
        }
        if (step instanceof PlanStep.Station s) {
            switch (s.action()) {
                case USE_EXISTING:
                    resolveExisting(s.kind());
                    return null;
                case PLACE:
                    return new PlaceStationTask(alto, s.kind(), pctx);
                case PICK_UP:
                    return new PickupStationTask(alto, s.kind(), pctx);
                default:
                    return null;
            }
        }
        return alto.tasksFor(step, pctx);
    }

    private void resolveExisting(StationKind kind) {
        Optional<BlockPos> pos = alto.stations().findNearest(kind, Baritone.settings().altoStationSearchRadius.value, p -> true);
        pos.ifPresent(p -> pctx.useStation(kind, p));
    }

    @Override
    public void cancel() {
        if (current != null) {
            current.cancel();
        }
        for (AltoTask t : tail) {
            t.cancel();
        }
    }

    /** The task running now (for the HUD). */
    public AltoTask current() {
        return current;
    }

    public static ItemCounts required(ItemKey item, int count) {
        ItemCounts c = new ItemCounts();
        c.add(item, count);
        return c;
    }
}
