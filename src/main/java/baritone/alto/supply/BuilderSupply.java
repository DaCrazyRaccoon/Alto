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
        if (alto.process().busy()) {
            return true; // a run is already going; the builder just waits
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
        Plan plan = alto.plan(need, new ItemCounts());
        if (!plan.isComplete()) {
            AltoLog.warn("The build needs " + need + " but Alto cannot produce it. Shopping list: " + plan.missing());
            fail();
            return false;
        }
        if (plan.isEmpty()) {
            return false; // nothing to do (the builder will find the blocks itself)
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
        alto.startJob(new Job(task, true, "builder", (ok, msg) -> {
            if (ok) {
                failuresInRow = 0;
            } else {
                fail();
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
