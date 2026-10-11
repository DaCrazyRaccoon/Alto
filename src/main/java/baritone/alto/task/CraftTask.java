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
import baritone.alto.container.MenuGoal;
import baritone.alto.container.MenuGoals;
import baritone.alto.container.MenuView;
import baritone.alto.container.SlotRole;
import baritone.alto.container.SlotView;
import baritone.alto.container.mc.ContainerSession;
import baritone.alto.core.ItemKey;
import baritone.alto.planner.Ingredient;
import baritone.alto.planner.RecipeDef;
import baritone.alto.planner.StationKind;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Crafts a recipe {@code crafts} times in the 2x2 inventory grid or at a crafting table.
 * <p>
 * Per batch: empty the grid, fill it (recipe book: one packet per set, or "all" when every available set is wanted;
 * otherwise slot by slot with exact counts), wait until the server shows the expected result (a mod like Polymorph
 * may choose another recipe: then stop), shift-click the result, empty the grid again. The inventory is re-counted
 * after every batch, so lag or another mod moving items cannot make it over- or under-count.
 */
public final class CraftTask extends AltoTask {

    private enum Phase { FIND, APPROACH, OPEN, CLEAR_BEFORE, BATCH, PLACE, WAIT_RESULT, TAKE, CLEAR_AFTER, CLOSE }

    private static final Set<SlotRole> GRID = MenuGoals.roles(SlotRole.CRAFT_GRID);

    private final RecipeDef recipe;
    private final int crafts;
    private final StationKind station;
    private final PlanContext pctx;
    private final ContainerSession session;
    private Phase phase = Phase.FIND;
    private Approach approach;
    private BlockPos table;
    private int remaining;
    private int batch;
    private int beforeCount;
    private int placements;
    private boolean manual;
    private boolean placeAll;
    private boolean triedRecipeBook;
    private MenuGoal goal;
    private int waitTicks;
    private int mismatchTicks;
    private int made;
    /** Output count in the inventory before the first craft (what is still needed is recomputed from it). */
    private int startCount = -1;

    public CraftTask(Alto alto, RecipeDef recipe, int crafts, StationKind station, PlanContext pctx) {
        super(alto);
        this.recipe = recipe;
        this.crafts = crafts;
        this.station = station;
        this.pctx = pctx;
        this.session = new ContainerSession(alto);
        this.remaining = crafts;
    }

    @Override
    public String name() {
        return "Craft " + (crafts * recipe.outputCount()) + "x " + recipe.output().shortName();
    }

    @Override
    public Step tick(boolean calcFailed) {
        if (!Baritone.settings().altoCrafting.value || !Baritone.settings().altoContainers.value) {
            return Step.failure("crafting is switched off (altoCrafting / altoContainers)");
        }
        progress(crafts - remaining, crafts);
        switch (phase) {
            case FIND:
                return find();
            case APPROACH: {
                Step s = approach.tick(ctx, calcFailed);
                if (s != null) {
                    detail("walking to the crafting table");
                    return s;
                }
                session.openBlock(table, "crafting table", m -> m instanceof CraftingMenu);
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
                    phase = Phase.CLEAR_BEFORE;
                    goal = MenuGoals.clear(GRID, "crafting grid");
                }
                return Step.stay();
            }
            case CLEAR_BEFORE:
                return runGoal(Phase.BATCH);
            case BATCH:
                return startBatch();
            case PLACE:
                return place();
            case WAIT_RESULT:
                return waitResult();
            case TAKE:
                return take();
            case CLEAR_AFTER:
                return runGoal(Phase.BATCH);
            case CLOSE: {
                detail("closing");
                if (session.close() == ContainerSession.Status.DONE) {
                    return Step.success("crafted " + made + "x " + recipe.output().shortName());
                }
                return Step.stay();
            }
            default:
                return Step.failure("unknown phase");
        }
    }

    private Step find() {
        if (station == StationKind.INVENTORY_GRID) {
            session.openInventory();
            phase = Phase.CLEAR_BEFORE;
            goal = MenuGoals.clear(GRID, "crafting grid");
            return Step.stay();
        }
        table = pctx.station(StationKind.CRAFTING_TABLE);
        if (table != null && !ctx.world().isLoaded(table)) {
            return Step.walk(new baritone.api.pathing.goals.GoalGetToBlock(table)); // remembered table near home
        }
        if (table == null || !ctx.world().getBlockState(table).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
            Optional<BlockPos> near = alto.stations().findNearest(StationKind.CRAFTING_TABLE,
                    Baritone.settings().altoStationSearchRadius.value, p -> true);
            if (near.isEmpty()) {
                return Step.failure("no crafting table nearby");
            }
            table = near.get();
            pctx.useStation(StationKind.CRAFTING_TABLE, table);
        }
        approach = new Approach(table);
        phase = Phase.APPROACH;
        return Step.stay();
    }

    private Step runGoal(Phase next) {
        ContainerSession.Status st = session.run(goal);
        if (st == ContainerSession.Status.FAILED) {
            return Step.failure(session.failure());
        }
        if (st == ContainerSession.Status.DONE) {
            phase = next;
        }
        return Step.stay();
    }

    private Step startBatch() {
        if (remaining <= 0) {
            phase = Phase.CLOSE;
            return Step.stay();
        }
        if (session.confirming()) {
            return Step.stay();
        }
        MenuView v = session.view();
        // What is still needed comes from the real output count: one shift-click can craft several times, and the
        // server's update may arrive after the click was counted.
        int nowCount = v.count(recipe.output(), MenuView.PLAYER_STORAGE);
        if (startCount < 0) {
            startCount = nowCount;
        } else {
            int produced = Math.max(0, nowCount - startCount);
            made = Math.max(made, produced);
            remaining = Math.max(0, crafts - (produced + recipe.outputCount() - 1) / recipe.outputCount());
            if (remaining <= 0) {
                phase = Phase.CLOSE;
                return Step.stay();
            }
        }
        int available = availableSets(v);
        if (available <= 0) {
            return Step.failure("missing ingredients for " + recipe.output().shortName() + ": need " + recipe.aggregatedInputs());
        }
        batch = Math.min(Math.min(remaining, 64), available);
        int out = recipe.outputCount();
        int space = v.spaceFor(recipe.output(), 64, MenuView.PLAYER_STORAGE);
        if (space < out) {
            return Step.failure("inventory full (no room for " + recipe.output().shortName() + ")");
        }
        batch = Math.min(batch, space / out);
        beforeCount = v.count(recipe.output(), MenuView.PLAYER_STORAGE);
        Optional<RecipeHolder<?>> holder = holder();
        boolean bookUsable = Baritone.settings().altoUseRecipeBook.value && holder.isPresent() && !triedRecipeBook
                && ctx.player().getRecipeBook().contains(holder.get()) && !reservedConflict(v);
        manual = !bookUsable;
        if (manual) {
            batch = Math.min(batch, MenuGoals.EXACT_CLICK_LIMIT);
            goal = manualFill(v, batch);
            if (goal == null) {
                return Step.failure("could not lay out the recipe " + recipe.id() + " in the grid");
            }
        }
        placeAll = !manual && batch >= available && available > 1;
        placements = 0;
        waitTicks = 0;
        mismatchTicks = 0;
        phase = Phase.PLACE;
        detail("batch of " + batch + (manual ? " (slot by slot)" : " (recipe book)"));
        return Step.stay();
    }

    private Step place() {
        if (manual) {
            ContainerSession.Status st = session.run(goal);
            if (st == ContainerSession.Status.FAILED) {
                return Step.failure(session.failure());
            }
            if (st == ContainerSession.Status.DONE) {
                phase = Phase.WAIT_RESULT;
            }
            return Step.stay();
        }
        RecipeHolder<?> h = holder().orElseThrow();
        if (placeAll) {
            if (placements == 0 && session.placeRecipe(h, true)) {
                placements = 1;
                phase = Phase.WAIT_RESULT;
            }
            return Step.stay();
        }
        if (placements < batch) {
            if (session.placeRecipe(h, false)) {
                placements++;
            }
            detail("placing set " + placements + "/" + batch);
            return Step.stay();
        }
        phase = Phase.WAIT_RESULT;
        return Step.stay();
    }

    private Step waitResult() {
        if (session.confirming()) {
            return Step.stay();
        }
        MenuView v = session.view();
        SlotView result = resultSlot(v);
        if (result != null && !result.isEmpty()) {
            if (!result.item().equals(recipe.output())) {
                // The server sends the result after the grid: under lag it can still show what a part of the grid
                // makes (one plank: a button). Only a result that stays wrong means another mod chose the recipe.
                if (++mismatchTicks <= 20 + alto.lag().confirmTicks(Baritone.settings())) {
                    return Step.stay();
                }
                return Step.failure("the grid makes " + result.item().shortName() + " instead of " + recipe.output().shortName()
                        + " (another mod, e.g. Polymorph, chose a different recipe)");
            }
            mismatchTicks = 0;
            goal = MenuGoals.takeOutput(result.index(), recipe.output(), 4);
            phase = Phase.TAKE;
            return Step.stay();
        }
        if (++waitTicks > 40 + alto.lag().confirmTicks(Baritone.settings())) {
            if (!manual) {
                AltoLog.debug("recipe book placement showed no result; switching to slot-by-slot");
                triedRecipeBook = true;
                goal = MenuGoals.clear(GRID, "crafting grid");
                phase = Phase.CLEAR_AFTER;
                return Step.stay();
            }
            return Step.failure("the crafting grid shows no result (missing ingredients or the server refused)");
        }
        return Step.stay();
    }

    private Step take() {
        ContainerSession.Status st = session.run(goal);
        if (st == ContainerSession.Status.FAILED) {
            return Step.failure(session.failure());
        }
        if (st == ContainerSession.Status.DONE) {
            int now = session.view().count(recipe.output(), MenuView.PLAYER_STORAGE);
            int got = now - beforeCount;
            if (got <= 0) {
                return Step.failure("crafting produced nothing");
            }
            made += got;
            remaining -= (got + recipe.outputCount() - 1) / recipe.outputCount();
            goal = MenuGoals.clear(GRID, "crafting grid");
            phase = Phase.CLEAR_AFTER;
        }
        return Step.stay();
    }

    @Override
    public void cancel() {
        session.closeNow();
    }

    // ------------------------------------------------------------------------------------------ helpers

    private Optional<RecipeHolder<?>> holder() {
        ResourceLocation id = ResourceLocation.tryParse(recipe.id());
        if (id == null || ctx.world() == null) {
            return Optional.empty();
        }
        return ctx.world().getRecipeManager().byKey(id);
    }

    private static SlotView resultSlot(MenuView v) {
        for (SlotView s : v.slots()) {
            if (s.role() == SlotRole.CRAFT_RESULT) {
                return s;
            }
        }
        return null;
    }

    /** How many full sets the inventory holds, counting only unreserved items. */
    private int availableSets(MenuView v) {
        int sets = Integer.MAX_VALUE;
        for (Ingredient ing : recipe.aggregatedInputs()) {
            int have = 0;
            for (ItemKey o : ing.options()) {
                have += Math.max(0, v.count(o, MenuView.PLAYER_STORAGE) - pctx.reserved.get(o));
            }
            sets = Math.min(sets, have / ing.count());
        }
        return sets == Integer.MAX_VALUE ? 0 : Math.min(64, sets);
    }

    /** The recipe book picks items itself; if a reserved item could be picked, place by hand instead. */
    private boolean reservedConflict(MenuView v) {
        for (Ingredient ing : recipe.inputs()) {
            for (ItemKey o : ing.options()) {
                if (pctx.reserved.get(o) > 0 && v.count(o, MenuView.PLAYER_STORAGE) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Fill each used grid slot with {@code sets} items, choosing unreserved options with the most items. */
    private MenuGoal manualFill(MenuView v, int sets) {
        List<SlotView> grid = v.withRole(SlotRole.CRAFT_GRID);
        int width = grid.size() == 4 ? 2 : 3;
        Map<ItemKey, Integer> left = new HashMap<>();
        // slot -> item, filled item by item so one picked-up stack serves every slot that needs it
        Map<ItemKey, List<Integer>> slotsByItem = new java.util.LinkedHashMap<>();
        for (int i = 0; i < recipe.inputs().size(); i++) {
            Ingredient ing = recipe.inputs().get(i);
            int gridIndex;
            if (recipe.isShaped()) {
                int s = recipe.gridSlots().get(i);
                int row = s / 3;
                int col = s % 3;
                if (row >= width || col >= width) {
                    return null;
                }
                gridIndex = row * width + col;
            } else {
                gridIndex = i;
            }
            if (gridIndex >= grid.size()) {
                return null;
            }
            ItemKey best = null;
            int bestLeft = -1;
            for (ItemKey o : ing.options()) {
                int avail = left.computeIfAbsent(o, k -> Math.max(0, v.count(k, MenuView.PLAYER_STORAGE) - pctx.reserved.get(k)));
                if (avail >= sets && avail > bestLeft) {
                    best = o;
                    bestLeft = avail;
                }
            }
            if (best == null) {
                return null;
            }
            left.put(best, bestLeft - sets);
            slotsByItem.computeIfAbsent(best, k -> new ArrayList<>()).add(grid.get(gridIndex).index());
        }
        List<MenuGoal> goals = new ArrayList<>();
        slotsByItem.forEach((item, slots) -> slots.forEach(slot -> goals.add(MenuGoals.fillSlot(slot, item, sets, false, true))));
        goals.add(MenuGoals.putBackCarried());
        return MenuGoals.sequence(goals.toArray(new MenuGoal[0]));
    }
}
