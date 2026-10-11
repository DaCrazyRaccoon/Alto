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
import baritone.alto.storage.ContainerRecord;
import baritone.alto.storage.FetchTask;
import baritone.alto.task.AltoTask;
import baritone.alto.task.Job;
import baritone.alto.task.SequenceTask;
import baritone.process.BuilderProcess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Feature 10, schematic supply runs: when a build starts, Alto lists the materials it still needs (shopping list in
 * chat and in baritone/alto/shopping-list.txt) and, if you have storage zones, fetches what your storage has in one
 * trip (as much as fits), before the builder continues. Anything still missing later is handled by
 * {@link BuilderSupply} (fetch, craft or mine on demand).
 */
public final class SupplyRun {

    private static final long SAME_BUILD_MS = 10 * 60 * 1000;
    private static final int KEEP_FREE_SLOTS = 2;

    private final Alto alto;
    /** Integrations (Create) that know what a block costs; null = not theirs. First answer wins. */
    private final List<Function<BlockState, ItemCounts>> requirementSources = new CopyOnWriteArrayList<>();
    private BuilderProcess pending;
    private int delay;
    private String lastBuild = "";
    private long lastAt;
    private ShoppingList last;

    public SupplyRun(Alto alto) {
        this.alto = alto;
        alto.addTickListener(a -> tick());
    }

    public void addRequirementSource(Function<BlockState, ItemCounts> source) {
        requirementSources.add(0, source);
    }

    public ShoppingList last() {
        return last;
    }

    /** BuilderProcess.build hook (also called when Alto resumes a build after a supply job: ignored then). */
    public void onBuildStarted(BuilderProcess builder) {
        if (!alto.enabled() || !Baritone.settings().altoSupplyRuns.value) {
            return;
        }
        String id = builder.altoBuildName();
        long now = System.currentTimeMillis();
        if (id.equals(lastBuild) && now - lastAt < SAME_BUILD_MS) {
            return;
        }
        lastBuild = id;
        lastAt = now;
        pending = builder;
        delay = 2;
    }

    private void tick() {
        if (pending == null || --delay > 0) {
            return;
        }
        BuilderProcess b = pending;
        pending = null;
        if (!b.isActive() || alto.ctx().player() == null) {
            return;
        }
        try {
            ShoppingList list = listFor(b.altoRemaining());
            last = list;
            report(list, "Materials for " + b.altoBuildName());
            startRun(list, "Supply run for the build");
        } catch (RuntimeException e) {
            AltoLog.exception("supply run failed", e);
        }
    }

    /** Shopping list for block states still to place. */
    public ShoppingList listFor(Map<BlockState, Integer> states) {
        ItemCounts need = new ItemCounts();
        states.forEach((state, n) -> requirements(state).forEach((item, c) -> need.add(item, c * n)));
        ItemCounts carried = McItems.inventoryCounts(alto.ctx().player().getInventory());
        ItemCounts storage = new ItemCounts();
        Predicate<ContainerRecord> usable = alto.storage().usable();
        for (ContainerRecord r : alto.storage().index().all()) {
            if (usable.test(r)) {
                storage.addAll(r.contents);
            }
        }
        return ShoppingList.compute(need, carried, storage, k -> !alto.recipeBook().producing(k).isEmpty());
    }

    /** Items needed for one block: an integration's answer, else the block's item (two for double slabs, none for the second half of doors and beds). */
    public ItemCounts requirements(BlockState state) {
        for (Function<BlockState, ItemCounts> f : requirementSources) {
            try {
                ItemCounts c = f.apply(state);
                if (c != null) {
                    return c;
                }
            } catch (Throwable t) {
                AltoLog.debug("requirement source failed for " + state + ": " + t);
            }
        }
        ItemCounts out = new ItemCounts();
        if (state.isAir() || !state.getFluidState().isEmpty() && state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
            return out;
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return out;
        }
        if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD) {
            return out;
        }
        Item item = state.getBlock().asItem();
        if (item == Items.AIR) {
            return out;
        }
        int n = state.getBlock() instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE ? 2 : 1;
        out.add(McItems.key(item), n);
        return out;
    }

    /** Chat summary (first lines) and the full list in baritone/alto/shopping-list.txt. */
    public void report(ShoppingList list, String title) {
        List<String> lines = list.describe(title);
        try {
            Files.createDirectories(alto.directory());
            Files.write(alto.directory().resolve("shopping-list.txt"), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AltoLog.debug("could not write the shopping list: " + e);
        }
        AltoLog.chat(title + ": " + list.lines().size() + " material(s), " + (list.totalMissing() == 0
                ? "everything is carried or in your storage" : list.totalMissing() + " item(s) missing") + " (full list: baritone/alto/shopping-list.txt)");
        lines.stream().skip(1).filter(l -> l.contains("MISSING")).limit(6).forEach(l -> AltoLog.chat("  " + l));
    }

    /** Fetches from storage what fits in the inventory, as one temporary job. False if there is nothing to fetch. */
    public boolean startRun(ShoppingList list, String name) {
        if (alto.storage().zones().isEmpty() && !Baritone.settings().altoStorageAnywhere.value) {
            return false;
        }
        int free = McItems.freeSlots(alto.ctx().player().getInventory()) - KEEP_FREE_SLOTS;
        ItemCounts run = list.supplyRun(free, k -> McItems.item(k).getDefaultMaxStackSize());
        if (run.isEmpty()) {
            return false;
        }
        List<AltoTask> steps = new ArrayList<>();
        var p = alto.ctx().player();
        Predicate<ContainerRecord> usable = alto.storage().usable();
        run.forEach((item, count) -> {
            int left = count;
            for (ContainerRecord r : alto.storage().index().containing(item, alto.storage().dimension(), p.getX(), p.getY(), p.getZ(), usable)) {
                if (left <= 0) {
                    break;
                }
                int take = Math.min(left, r.contents.get(item));
                steps.add(new FetchTask(alto, r.key, item, take));
                left -= take;
            }
        });
        if (steps.isEmpty()) {
            return false;
        }
        AltoLog.chat(name + ": fetching " + run.total() + " item(s) of " + run.asMap().size() + " kind(s) from your storage.");
        alto.startJob(new Job(new SequenceTask(alto, name, steps), true, "supply", (ok, msg) -> {
        }));
        return true;
    }
}
