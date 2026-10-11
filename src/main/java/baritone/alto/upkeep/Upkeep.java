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

package baritone.alto.upkeep;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import baritone.alto.planner.Plan;
import baritone.alto.storage.ContainerRecord;
import baritone.alto.storage.DepositTask;
import baritone.alto.storage.FetchTask;
import baritone.alto.storage.KeepList;
import baritone.alto.task.AltoTask;
import baritone.alto.task.Job;
import baritone.alto.task.PlanContext;
import baritone.alto.task.PlanTask;
import baritone.alto.task.SequenceTask;
import baritone.api.Settings;
import baritone.api.process.IBaritoneProcess;
import baritone.process.BuilderProcess;
import baritone.process.ExploreProcess;
import baritone.process.FarmProcess;
import baritone.process.GetToBlockProcess;
import baritone.process.MineProcess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.TieredItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Automatic helpers that run while Baritone works on a job (mining, farming, building, exploring): extra 1
 * auto-deposit, extra 2 restock, extra 4 compress rules, extra 5 tool replacement and auto-eat. Each starts a
 * <i>temporary</i> Alto job, so the job it interrupts keeps its state and continues afterwards. Every helper has its
 * own setting; failures back off for a few minutes instead of retrying in a loop.
 */
public final class Upkeep {

    private static final int CHECK_EVERY = 20;
    private static final long FAIL_COOLDOWN_MS = 3 * 60 * 1000;

    private final Alto alto;
    private final Map<String, Long> cooldownUntil = new HashMap<>();
    private int ticks;

    public Upkeep(Alto alto) {
        this.alto = alto;
        alto.addTickListener(a -> tick());
    }

    private void tick() {
        if (++ticks % CHECK_EVERY != 0 || !alto.enabled() || alto.ctx().player() == null || alto.process().busy()
                || alto.selfTests().running() && !alto.selfTests().allowUpkeep()) {
            return;
        }
        Optional<IBaritoneProcess> working = workingProcess();
        Settings s = Baritone.settings();
        if (s.altoAutoEat.value && (working.isPresent() || !s.altoAutoEatOnlyWhileBotting.value) && tryEat()) {
            return;
        }
        if (working.isEmpty()) {
            return;
        }
        if (s.altoAutoDeposit.value && tryDeposit(working.get())) {
            return;
        }
        if (s.altoCompress.value && tryCompress()) {
            return;
        }
        if (s.altoToolUpkeep.value && tryToolReplacement()) {
            return;
        }
        if (s.altoFarmRestockSeeds.value && working.get() instanceof FarmProcess && tryRestockSeeds()) {
            return;
        }
        if (s.altoRestock.value) {
            tryRestock();
        }
    }

    /** #farm has open farmland but nothing to plant: fetch the seed type your storage holds most of. */
    private boolean tryRestockSeeds() {
        if (!baritone.alto.AltoHooks.farmOutOfSeeds() || !haveStorage() || cooling("seeds")) {
            return false;
        }
        java.util.Set<Item> seeds = new java.util.HashSet<>(baritone.alto.AltoHooks.moddedSeeds());
        seeds.addAll(List.of(net.minecraft.world.item.Items.WHEAT_SEEDS, net.minecraft.world.item.Items.BEETROOT_SEEDS,
                net.minecraft.world.item.Items.CARROT, net.minecraft.world.item.Items.POTATO,
                net.minecraft.world.item.Items.MELON_SEEDS, net.minecraft.world.item.Items.PUMPKIN_SEEDS));
        ItemKey best = null;
        int bestCount = 0;
        java.util.function.Predicate<ContainerRecord> usable = alto.storage().usable();
        for (ContainerRecord r : alto.storage().index().all()) {
            if (!usable.test(r)) {
                continue;
            }
            for (Map.Entry<ItemKey, Integer> e : r.contents.asMap().entrySet()) {
                if (seeds.contains(McItems.item(e.getKey())) && e.getValue() > bestCount) {
                    best = e.getKey();
                    bestCount = e.getValue();
                }
            }
        }
        if (best == null) {
            cooldownUntil.put("seeds", System.currentTimeMillis() + FAIL_COOLDOWN_MS);
            AltoLog.chat("Out of seeds, and none are known in your storage.");
            return false;
        }
        List<AltoTask> steps = new ArrayList<>();
        int left = 64;
        for (ContainerRecord r : alto.storage().index().containing(best, alto.storage().dimension(),
                alto.ctx().player().getX(), alto.ctx().player().getY(), alto.ctx().player().getZ(), usable)) {
            if (left <= 0) {
                break;
            }
            int take = Math.min(left, r.contents.get(best));
            steps.add(new FetchTask(alto, r.key, best, take));
            left -= take;
        }
        AltoLog.chat("Out of seeds: fetching " + best.shortName() + " from your storage.");
        start("seeds", new SequenceTask(alto, "Restock seeds (" + best.shortName() + ")", steps));
        return true;
    }

    /** The Baritone process doing a job right now (not Alto itself, not plain #goto). */
    private Optional<IBaritoneProcess> workingProcess() {
        Optional<IBaritoneProcess> p = alto.baritone().getPathingControlManager().mostRecentInControl();
        return p.filter(proc -> proc instanceof MineProcess || proc instanceof FarmProcess || proc instanceof BuilderProcess
                || proc instanceof ExploreProcess || proc instanceof GetToBlockProcess);
    }

    private boolean cooling(String key) {
        Long until = cooldownUntil.get(key);
        return until != null && System.currentTimeMillis() < until;
    }

    private void start(String key, AltoTask task) {
        alto.startJob(new Job(task, true, "upkeep", (ok, msg) -> {
            if (!ok) {
                cooldownUntil.put(key, System.currentTimeMillis() + FAIL_COOLDOWN_MS);
            }
        }));
    }

    private boolean haveStorage() {
        return !alto.storage().zones().isEmpty() || Baritone.settings().altoStorageAnywhere.value;
    }

    // ------------------------------------------------------------------------------------------ auto-eat

    private boolean tryEat() {
        if (cooling("eat")) {
            return false;
        }
        int food = alto.ctx().player().getFoodData().getFoodLevel();
        if (food > Baritone.settings().altoEatBelowHunger.value) {
            return false;
        }
        Inventory inv = alto.ctx().player().getInventory();
        for (Item item : Baritone.settings().altoFoodItems.value) {
            if (McItems.count(inv, item) > 0 && new ItemStack(item).has(DataComponents.FOOD)) {
                AltoLog.chat("Hunger " + food + ": eating " + McItems.key(item).shortName());
                start("eat", new EatTask(alto, item));
                return true;
            }
        }
        cooldownUntil.put("eat", System.currentTimeMillis() + FAIL_COOLDOWN_MS);
        AltoLog.warn("Hungry (" + food + ") but no allowed food in the inventory (setting altoFoodItems).");
        return false;
    }

    // ------------------------------------------------------------------------------------------ auto-deposit

    private boolean tryDeposit(IBaritoneProcess working) {
        if (cooling("deposit") || !haveStorage()) {
            return false;
        }
        int free = McItems.freeSlots(alto.ctx().player().getInventory());
        if (free > Baritone.settings().altoAutoDepositFreeSlots.value) {
            return false;
        }
        ItemCounts needs = new ItemCounts();
        if (working instanceof BuilderProcess) {
            // Keep the blocks the build places: everything the builder could place right now.
            for (var st : alto.baritone().getBuilderProcess().getApproxPlaceable()) {
                Item item = st.getBlock().asItem();
                needs.add(McItems.key(item), 1);
            }
        }
        AltoLog.chat("Inventory full: depositing into storage, then continuing.");
        start("deposit", new DepositTask(alto, KeepList.keep(needs)));
        return true;
    }

    // ------------------------------------------------------------------------------------------ restock

    /** Parses "item=count" rules from altoRestockItems. */
    static Map<ItemKey, Integer> restockRules(List<String> raw) {
        Map<ItemKey, Integer> out = new HashMap<>();
        for (String r : raw) {
            int eq = r.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                out.put(ItemKey.of(r.substring(0, eq).trim()), Integer.parseInt(r.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
                // invalid rule: skipped (shown by #alto status)
            }
        }
        return out;
    }

    private boolean tryRestock() {
        if (!haveStorage()) {
            return false;
        }
        Inventory inv = alto.ctx().player().getInventory();
        for (Map.Entry<ItemKey, Integer> rule : restockRules(Baritone.settings().altoRestockItems.value).entrySet()) {
            String key = "restock:" + rule.getKey();
            if (cooling(key)) {
                continue;
            }
            int have = McItems.count(inv, McItems.item(rule.getKey()));
            int want = rule.getValue();
            if (have >= Math.max(1, want / 4)) {
                continue; // restock when below a quarter of the target
            }
            List<AltoTask> steps = new ArrayList<>();
            int left = want - have;
            for (ContainerRecord r : alto.storage().index().containing(rule.getKey(), alto.storage().dimension(),
                    alto.ctx().player().getX(), alto.ctx().player().getY(), alto.ctx().player().getZ(), alto.storage().usable())) {
                if (left <= 0) {
                    break;
                }
                int take = Math.min(left, r.contents.get(rule.getKey()));
                steps.add(new FetchTask(alto, r.key, rule.getKey(), take));
                left -= take;
            }
            if (steps.isEmpty()) {
                cooldownUntil.put(key, System.currentTimeMillis() + FAIL_COOLDOWN_MS);
                continue;
            }
            AltoLog.chat("Restocking " + rule.getKey().shortName() + " (" + have + " left, want " + want + ").");
            start(key, new SequenceTask(alto, "Restock " + rule.getKey().shortName(), steps));
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------ compress

    private boolean tryCompress() {
        if (cooling("compress") || McItems.freeSlots(alto.ctx().player().getInventory()) > 3) {
            return false;
        }
        Inventory inv = alto.ctx().player().getInventory();
        for (String rule : Baritone.settings().altoCompressRules.value) {
            int arrow = rule.indexOf("->");
            if (arrow <= 0) {
                continue;
            }
            ItemKey from = ItemKey.of(rule.substring(0, arrow).trim());
            ItemKey to = ItemKey.of(rule.substring(arrow + 2).trim());
            int have = McItems.count(inv, McItems.item(from));
            if (have < 9) {
                continue;
            }
            int target = McItems.count(inv, McItems.item(to)) + have / 9;
            ItemCounts want = ItemCounts.of(to, target);
            Plan plan = alto.plan(want, new ItemCounts());
            // Only compress from what is carried: no fetching, no mining.
            boolean onlyCrafting = plan.isComplete() && plan.steps().stream().allMatch(st ->
                    st instanceof baritone.alto.planner.PlanStep.Craft || st instanceof baritone.alto.planner.PlanStep.Station);
            if (!onlyCrafting || plan.isEmpty()) {
                continue;
            }
            AltoLog.chat("Compressing " + from.shortName() + " into " + to.shortName() + ".");
            start("compress", new PlanTask(alto, plan, new PlanContext(new ItemCounts(), p -> false), want));
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------ tools

    private static final String[] TIERS = {"wooden", "stone", "iron", "golden", "diamond", "netherite"};

    private boolean tryToolReplacement() {
        Inventory inv = alto.ctx().player().getInventory();
        ItemStack held = inv.getSelected();
        if (!(held.getItem() instanceof TieredItem) || !held.isDamageableItem()) {
            return false;
        }
        int left = held.getMaxDamage() - held.getDamageValue();
        if (left > Baritone.settings().altoToolMinDurability.value) {
            return false;
        }
        Class<?> kind = held.getItem() instanceof PickaxeItem ? PickaxeItem.class
                : held.getItem() instanceof AxeItem ? AxeItem.class
                : held.getItem() instanceof ShovelItem ? ShovelItem.class : null;
        if (kind == null) {
            return false;
        }
        // Another usable tool of the same kind already carried?
        for (ItemStack s : inv.items) {
            if (s != held && kind.isInstance(s.getItem()) && s.getMaxDamage() - s.getDamageValue() > Baritone.settings().altoToolMinDurability.value) {
                return false;
            }
        }
        String key = "tool:" + kind.getSimpleName();
        if (cooling(key)) {
            return false;
        }
        String kindName = kind == PickaxeItem.class ? "pickaxe" : kind == AxeItem.class ? "axe" : "shovel";
        int maxTier = java.util.Arrays.asList(TIERS).indexOf(Baritone.settings().altoToolMaxTier.value.toLowerCase(java.util.Locale.ROOT));
        if (maxTier < 0) {
            maxTier = 2;
        }
        for (int t = maxTier; t >= 0; t--) {
            if (TIERS[t].equals("golden")) {
                continue;
            }
            Optional<Item> candidate = McItems.parseItem("minecraft:" + TIERS[t] + "_" + kindName);
            if (candidate.isEmpty()) {
                continue;
            }
            ItemKey ck = McItems.key(candidate.get());
            ItemCounts want = ItemCounts.of(ck, McItems.count(inv, candidate.get()) + 1);
            Plan plan = alto.plan(want, new ItemCounts());
            if (plan.isComplete() && !plan.isEmpty()) {
                AltoLog.chat("The " + McItems.key(held.getItem()).shortName() + " is nearly broken (" + left + " uses left): getting a "
                        + ck.shortName() + ".");
                start(key, new PlanTask(alto, plan, new PlanContext(new ItemCounts(), p -> false), want));
                return true;
            }
        }
        cooldownUntil.put(key, System.currentTimeMillis() + FAIL_COOLDOWN_MS);
        AltoLog.warn("The " + McItems.key(held.getItem()).shortName() + " is nearly broken and no replacement can be made or fetched.");
        return false;
    }
}
