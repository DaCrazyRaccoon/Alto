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

package baritone.alto.supply;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import baritone.alto.planner.Plan;
import baritone.alto.task.Job;
import baritone.alto.task.MenuGoalTask;
import baritone.alto.container.MenuGoals;
import baritone.alto.task.PlanContext;
import baritone.alto.task.PlanTask;
import baritone.process.BuilderProcess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * Feature D: when the builder (#build, #sel fill, schematics) runs out of a block, plan how to produce it
 * (inventory, storage, crafting, smelting, mining) and run that plan as a <i>temporary</i> job above the builder, so
 * the build keeps its state and simply continues when the blocks arrive in the hotbar. When it is needed again, the
 * same happens again ("needs more planks -> crafts more planks").
 * <p>
 * Guard rails: a failed supply run is not retried for 10 seconds, and after two failures in a row for the same build
 * Alto steps aside and the builder pauses with its usual message plus Alto's shopping list.
 */
public final class BuilderSupply {

    private static final int RETRY_COOLDOWN_TICKS = 200;

    private final Alto alto;
    private String buildId = "";
    private int failuresInRow;
    private long lastFailureTick = -1_000_000;
    private long ticks;
    private ItemCounts lastRequest = new ItemCounts();

    public BuilderSupply(Alto alto) {
        this.alto = alto;
        alto.addTickListener(a -> ticks++);
    }

    /**
     * Called by the builder right before it would pause because of missing materials.
     *
     * @return true if a supply run was started (the builder then waits instead of pausing)
     */
    public boolean onMissing(BuilderProcess builder, Map<BlockState, Integer> missing) {
        if (!alto.enabled() || !Baritone.settings().altoBuilderSupply.value || missing == null || missing.isEmpty()) {
            return false;
        }
        if (alto.process().busy() || alto.supplyRun().hasPending()) {
            return true; // a run is already going (or the build's supply run is about to start); the builder waits
        }
        String id = builder.altoBuildName();
        if (!id.equals(buildId)) {
            buildId = id;
            failuresInRow = 0;
        }
        if (failuresInRow >= 2) {
            return false;
        }
        if (ticks - lastFailureTick < RETRY_COOLDOWN_TICKS) {
            return false;
        }
        int batch = Math.max(1, Baritone.settings().altoBuilderSupplyBatch.value);
        ItemCounts need = new ItemCounts();
        for (Map.Entry<BlockState, Integer> e : missing.entrySet()) {
            Item item = e.getKey().getBlock().asItem();
            if (item == Items.AIR) {
                continue; // liquids, fire, ... cannot be supplied as items
            }
            need.add(McItems.key(item), Math.min(batch, Math.max(1, e.getValue())));
        }
        if (need.isEmpty()) {
            return false;
        }
        // Never mine a block the build itself uses (the miner could take it from the build).
        java.util.Set<String> buildBlocks = new java.util.HashSet<>();
        for (BlockState st : missing.keySet()) {
            buildBlocks.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
        }
        Plan plan = alto.plan(need, new ItemCounts(), buildBlocks);
        if (!plan.isComplete()) {
            AltoLog.warn("The build needs " + need + " but Alto cannot produce it. Shopping list: " + plan.missing());
            fail();
            return false;
        }
        if (plan.isEmpty()) {
            // Everything is carried, but maybe not on the hotbar (the builder only places from the hotbar).
            var inv = alto.ctx().player().getInventory();
            for (ItemKey k : need.asMap().keySet()) {
                Item it = McItems.item(k);
                boolean onHotbar = false;
                for (int i = 0; i < 9; i++) {
                    onHotbar |= inv.getItem(i).is(it);
                }
                if (!onHotbar && McItems.count(inv, it) > 0) {
                    java.util.Set<ItemKey> buildItems = need.asMap().keySet();
                    MenuGoalTask move = new MenuGoalTask(alto, "Put " + k.shortName() + " on the hotbar for the build",
                            MenuGoals.toHotbar(k, x -> !buildItems.contains(x) && !(McItems.item(x) instanceof net.minecraft.world.item.TieredItem)));
                    alto.startJob(new Job(move, true, "builder", (ok, msg) -> {
                        if (!ok) {
                            fail();
                        }
                    }));
                    return true;
                }
            }
            return false; // on the hotbar already: the builder places them
        }
        lastRequest = need;
        PlanContext pctx = new PlanContext(new ItemCounts(), builder::altoInBuildArea);
        need.forEach(pctx.deliverToHotbar::add);
        PlanTask task = new PlanTask(alto, plan, pctx, need);
        for (String line : plan.describe()) {
            AltoLog.info(line);
        }
        AltoLog.chat("The build is out of " + need + "; getting more: " + plan.steps().size() + " step(s), about "
                + Plan.formatTicks(plan.estimatedTicks()) + ".");
        Object[] snapshot = builder.altoSnapshot();
        alto.startJob(new Job(task, true, "builder", (ok, msg) -> {
            if (ok) {
                failuresInRow = 0;
            } else {
                fail();
            }
            // A mining step lets Baritone's miner take over, which resets the builder: start the same build again.
            if (!builder.isActive()) {
                builder.altoResume(snapshot);
            }
        }));
        return true;
    }

    private void fail() {
        failuresInRow++;
        lastFailureTick = ticks;
    }

    public ItemCounts lastRequest() {
        return lastRequest;
    }

    /** True if a key is part of the last request (used by the HUD). */
    public boolean requested(ItemKey key) {
        return lastRequest.get(key) > 0;
    }
}
