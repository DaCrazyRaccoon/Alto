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

package baritone.alto.planner;

import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import baritone.alto.planner.PlanStep.StationAction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link Plan} for "I need N of item X" from a {@link RecipeBook} and a {@link WorldModel} snapshot.
 * <p>
 * Order of preference for every needed item (the user-facing rule, see docs/alto/USER_GUIDE.md "Planner"):
 * <ol>
 *     <li>what is already in the inventory (free),</li>
 *     <li>what is in carried shulker boxes,</li>
 *     <li>known storage within {@link PlannerOptions#storagePreferenceRadius},</li>
 *     <li>the cheapest of: farther storage, crafting, smelting (compared by estimated time),</li>
 *     <li>mining, only when nothing else works.</li>
 * </ol>
 * Alternatives (tags such as "any planks", several recipes for one item, several fuels) are compared by dry runs
 * on copies of the planning state; the cheapest complete one wins. Cycles are cut by never producing an item
 * while it is already being produced further up the chain. A crafting table needed for the "force station" mode is
 * bootstrapped in the inventory grid, otherwise "planks need a table, a table needs planks" could never start.
 * <p>
 * Pure Java: no Minecraft classes, deterministic for the same inputs.
 */
public final class Planner {

    /** Cost per item that cannot be obtained; dominates every other cost so complete plans always win. */
    static final double MISSING_PENALTY = 10_000_000;
    /** Mining is the last resort. */
    static final double GATHER_PENALTY = 100_000;
    /** A preferred recipe (EMI default) wins unless it costs this many ticks more. It never outweighs missing items or mining. */
    static final double PREFERRED_BONUS = 2_000;
    static final double SHULKER_UNPACK_TICKS = 80;
    static final double CONTAINER_OPEN_TICKS = 30;
    static final double STATION_PLACE_TICKS = 40;
    static final double STATION_PICKUP_TICKS = 80;
    static final int FURNACE_REFERENCE_COOK = 200;
    private static final ItemKey CRAFTING_TABLE = ItemKey.of("minecraft:crafting_table");

    private final RecipeBook book;
    private final PlannerOptions opt;

    public Planner(RecipeBook book, PlannerOptions options) {
        this.book = book;
        this.opt = options.copy();
    }

    public Plan plan(ItemKey item, int count, WorldModel world) {
        ItemCounts targets = new ItemCounts();
        targets.add(item, count);
        return plan(targets, world);
    }

    public Plan plan(ItemCounts targets, WorldModel world) {
        State s = new State(world);
        targets.forEach((item, n) -> satisfy(s, item, n, 0, Set.of(), false));
        finish(s);
        return new Plan(targets.copy(), mergeAdjacent(s.steps), s.usedFromInventory, s.missing, s.produced, Math.round(s.ticks), s.cost,
                new ArrayList<>(s.warnings));
    }

    // ------------------------------------------------------------------------------------------ core recursion

    private void satisfy(State s, ItemKey item, int n, int depth, Set<ItemKey> stack, boolean forceGrid) {
        if (n <= 0) {
            return;
        }
        s.nodes[0]++;
        if (depth > 0 && opt.neverConsume.contains(item)) {
            s.warnings.add(item.shortName() + " is on the never-consume list (setting altoNeverConsume)");
            markMissing(s, item, n);
            return;
        }
        n -= s.takeInventory(item, n);
        if (n == 0) {
            return;
        }
        if (opt.useShulkers) {
            n -= unpackShulker(s, item, n);
            if (n == 0) {
                return;
            }
        }
        if (opt.useStorage) {
            for (WorldModel.StorageSource src : s.world.storages) {
                if (src.distance() <= opt.storagePreferenceRadius) {
                    n -= fetch(s, src, item, n);
                    if (n == 0) {
                        return;
                    }
                }
            }
        }
        if (depth > opt.maxDepth || s.nodes[0] > opt.nodeBudget) {
            if (s.nodes[0] > opt.nodeBudget) {
                s.warnings.add("planner budget exceeded; some alternatives were not explored");
            }
            markMissing(s, item, n);
            return;
        }
        // Far storage first (then produce the rest) versus producing everything: compare.
        State best = null;
        if (opt.useStorage && anyFarStorageHas(s, item)) {
            State c = s.copy();
            int rem = n;
            for (WorldModel.StorageSource src : s.world.storages) {
                if (src.distance() > opt.storagePreferenceRadius && rem > 0) {
                    rem -= fetch(c, src, item, rem);
                }
            }
            produceBest(c, item, rem, depth, stack, forceGrid);
            best = c;
        }
        State p = s.copy();
        produceBest(p, item, n, depth, stack, forceGrid);
        if (best == null || p.cost < best.cost) {
            best = p;
        }
        s.adopt(best);
    }

    /** Produce {@code n} of {@code item} by the cheapest recipe, or by mining, or mark it missing. */
    private void produceBest(State s, ItemKey item, int n, int depth, Set<ItemKey> stack, boolean forceGrid) {
        if (n <= 0) {
            return;
        }
        State best = null;
        double bestScore = Double.MAX_VALUE;
        if (!stack.contains(item)) {
            Set<ItemKey> inner = new HashSet<>(stack);
            inner.add(item);
            int tried = 0;
            for (RecipeDef r : candidates(item)) {
                if (closesCycle(r, inner)) {
                    continue; // e.g. iron ingot from an iron block while making that iron block
                }
                if (tried++ >= opt.maxAlternatives) {
                    break;
                }
                State c = s.copy();
                produceWith(c, r, n, depth, inner, forceGrid);
                if (best == null || score(c, item, r) < bestScore) {
                    best = c;
                    bestScore = score(c, item, r);
                }
            }
        }
        WorldModel.GatherSource gather = s.world.gatherable.get(item);
        if (gather != null && opt.allowGather) {
            State c = s.copy();
            c.steps.add(new PlanStep.Gather(item, n, gather.blocks()));
            double t = 200 + n * gather.ticksPerItem();
            c.ticks += t;
            c.cost += GATHER_PENALTY + t;
            if (best == null || c.cost < bestScore) {
                best = c;
            }
        }
        if (best == null) {
            markMissing(s, item, n);
            return;
        }
        s.adopt(best);
    }

    /** True if some ingredient can only be satisfied by an item that is already being produced up the chain. */
    private static boolean closesCycle(RecipeDef r, Set<ItemKey> producing) {
        for (Ingredient in : r.inputs()) {
            if (producing.containsAll(in.options())) {
                return true;
            }
        }
        return false;
    }

    private double score(State c, ItemKey item, RecipeDef r) {
        return r.id().equals(opt.preferredRecipes.get(item)) ? c.cost - PREFERRED_BONUS : c.cost;
    }

    private List<RecipeDef> candidates(ItemKey item) {
        List<RecipeDef> out = new ArrayList<>();
        String preferred = opt.preferredRecipes.get(item);
        for (RecipeDef r : book.producing(item)) {
            if (!r.kind().executable || r.inputs().isEmpty() || r.consumesOwnOutput()) {
                continue;
            }
            if (r.kind() == RecipeKind.CRAFTING && !opt.allowCrafting) {
                continue;
            }
            if (r.kind().isCooking() && !opt.allowSmelting) {
                continue;
            }
            if (r.id().equals(preferred)) {
                out.add(0, r); // always within maxAlternatives
            } else {
                out.add(r);
            }
        }
        return out;
    }

    private void produceWith(State c, RecipeDef r, int n, int depth, Set<ItemKey> stack, boolean forceGrid) {
        int ops = (n + r.outputCount() - 1) / r.outputCount();
        if (r.kind() == RecipeKind.CRAFTING) {
            boolean grid = r.fitsInventoryGrid()
                    && (!opt.alwaysUseCraftingStation || forceGrid || r.output().equals(CRAFTING_TABLE));
            StationKind station = grid ? StationKind.INVENTORY_GRID : StationKind.CRAFTING_TABLE;
            for (Ingredient ing : r.aggregatedInputs()) {
                satisfyIngredient(c, ing, ing.count() * ops, depth + 1, stack, forceGrid);
            }
            if (station == StationKind.CRAFTING_TABLE) {
                ensureStation(c, StationKind.CRAFTING_TABLE, depth + 1, stack, forceGrid);
            }
            c.steps.add(new PlanStep.Craft(r, ops, station));
            int batches = (ops + 63) / 64;
            double t = CONTAINER_OPEN_TICKS + batches * 3.0 * opt.ticksPerClick;
            c.ticks += t;
            // Crafting costs as much as walking to storage at the preference radius, so near storage wins.
            c.cost += t + opt.travelTicks(opt.storagePreferenceRadius);
        } else {
            satisfyIngredient(c, r.inputs().get(0), ops, depth + 1, stack, forceGrid);
            StationKind station = r.kind().cookingStation();
            ensureStation(c, station, depth + 1, stack, forceGrid);
            ItemKey fuel = ItemKey.EMPTY;
            int fuelCount = 0;
            if (!c.world.fuels.isEmpty()) {
                int burnNeeded = ops * FURNACE_REFERENCE_COOK; // blast furnaces/smokers burn fuel twice as fast
                State bestFuel = null;
                ItemKey bestFuelItem = null;
                int bestFuelCount = 0;
                for (Map.Entry<ItemKey, Integer> f : c.world.fuels.entrySet()) {
                    if (opt.neverConsume.contains(f.getKey())) {
                        continue;
                    }
                    int units = (burnNeeded + f.getValue() - 1) / f.getValue();
                    State t = c.copy();
                    int missingBefore = t.missing.total();
                    satisfy(t, f.getKey(), units, depth + 1, stack, forceGrid);
                    boolean complete = t.missing.total() == missingBefore;
                    if (bestFuel == null || complete && t.cost < bestFuel.cost) {
                        bestFuel = t;
                        bestFuelItem = f.getKey();
                        bestFuelCount = units;
                    }
                    if (complete && t.cost - c.cost < 1) {
                        break; // fuel already in hand: no need to look further
                    }
                }
                c.adopt(bestFuel);
                fuel = bestFuelItem;
                fuelCount = bestFuelCount;
            } else {
                c.warnings.add("no allowed fuel configured (setting altoAllowedFuels)");
                markMissing(c, ItemKey.of("minecraft:coal"), Math.max(1, ops / 8));
            }
            c.steps.add(new PlanStep.Smelt(r, ops, station, fuel, fuelCount));
            int furnaces = Math.max(1, Math.min(opt.maxFurnaces, ops));
            double t = 100 + (double) ops * Math.max(1, r.cookTicks()) / furnaces;
            c.ticks += t;
            c.cost += t + opt.travelTicks(opt.storagePreferenceRadius);
        }
        int surplus = ops * r.outputCount() - n;
        c.addProduced(r.output(), surplus);
        r.remainders().forEach((item, perCraft) -> c.addProduced(item, perCraft * ops));
    }

    /** Satisfy a tag-like ingredient: use what is at hand first (any option), then the cheapest single option. */
    private void satisfyIngredient(State c, Ingredient ing, int need, int depth, Set<ItemKey> stack, boolean forceGrid) {
        if (ing.options().size() == 1) {
            satisfy(c, ing.options().get(0), need, depth, stack, forceGrid);
            return;
        }
        List<ItemKey> options = new ArrayList<>(ing.options());
        options.removeIf(opt.neverConsume::contains);
        if (options.isEmpty()) {
            satisfy(c, ing.options().get(0), need, depth, stack, forceGrid); // reports the never-consume item as missing
            return;
        }
        options.sort(Comparator.comparingInt((ItemKey o) -> -c.inventoryCount(o)));
        for (ItemKey o : options) {
            need -= c.takeInventory(o, need);
            if (need == 0) {
                return;
            }
        }
        if (opt.useShulkers) {
            for (ItemKey o : options) {
                need -= unpackShulker(c, o, need);
                if (need == 0) {
                    return;
                }
            }
        }
        if (opt.useStorage) {
            for (WorldModel.StorageSource src : c.world.storages) {
                if (src.distance() > opt.storagePreferenceRadius) {
                    continue;
                }
                for (ItemKey o : options) {
                    need -= fetch(c, src, o, need);
                    if (need == 0) {
                        return;
                    }
                }
            }
        }
        // Rank the remaining options: ones whose own ingredients are at hand come first.
        options.sort(Comparator.comparingInt((ItemKey o) -> -precursorScore(c, o)));
        State best = null;
        int tried = 0;
        for (ItemKey o : options) {
            if (tried++ >= opt.maxAlternatives) {
                break;
            }
            State t = c.copy();
            satisfy(t, o, need, depth, stack, forceGrid);
            if (best == null || t.cost < best.cost) {
                best = t;
            }
        }
        c.adopt(best);
    }

    private int precursorScore(State c, ItemKey item) {
        int score = 0;
        for (RecipeDef r : book.producing(item)) {
            for (Ingredient in : r.inputs()) {
                for (ItemKey o : in.options()) {
                    if (c.inventoryCount(o) > 0 || c.shulker.get(o) > 0) {
                        score++;
                        break;
                    }
                }
            }
        }
        return score;
    }

    private void ensureStation(State c, StationKind kind, int depth, Set<ItemKey> stack, boolean forceGrid) {
        if (c.ready.contains(kind)) {
            return;
        }
        if (opt.reuseExistingStations) {
            for (WorldModel.StationSite site : c.world.stations) {
                if (site.kind() == kind && site.distance() <= opt.stationSearchRadius) {
                    c.ready.add(kind);
                    c.steps.add(new PlanStep.Station(kind, StationAction.USE_EXISTING, site.id()));
                    double t = opt.travelTicks(site.distance());
                    c.ticks += t;
                    c.cost += t;
                    return;
                }
            }
        }
        if (!opt.allowPlaceStation) {
            c.warnings.add("no " + kind.displayName + " nearby and placing stations is disabled");
            markMissing(c, kind.item(), 1);
            return;
        }
        boolean bootstrap = kind == StationKind.CRAFTING_TABLE;
        // The table bootstrap is independent of the chain that needs it, so it starts with a fresh stack.
        satisfy(c, kind.item(), 1, depth, bootstrap ? Set.of() : stack, forceGrid || bootstrap);
        c.steps.add(new PlanStep.Station(kind, StationAction.PLACE, null));
        c.ready.add(kind);
        c.placed.add(kind);
        c.ticks += STATION_PLACE_TICKS;
        c.cost += STATION_PLACE_TICKS;
    }

    private void finish(State s) {
        if (!opt.pickUpPlacedStations) {
            return;
        }
        for (StationKind kind : s.placed) {
            if (kind.needsPickaxeToCollect() && !s.world.hasPickaxe) {
                s.warnings.add("the placed " + kind.displayName + " stays in the world: no pickaxe to collect it");
                continue;
            }
            s.steps.add(new PlanStep.Station(kind, StationAction.PICK_UP, null));
            s.ticks += STATION_PICKUP_TICKS;
            s.cost += STATION_PICKUP_TICKS;
        }
    }

    // ------------------------------------------------------------------------------------------ sources

    private int unpackShulker(State s, ItemKey item, int n) {
        int t = s.shulker.take(item, n);
        if (t > 0) {
            s.steps.add(new PlanStep.UnpackShulker(item, t));
            double ticks = (s.shulkerUsed ? 0 : SHULKER_UNPACK_TICKS) + opt.ticksPerClick * Math.ceil(t / 64.0);
            s.shulkerUsed = true;
            s.ticks += ticks;
            s.cost += ticks;
        }
        return t;
    }

    private int fetch(State s, WorldModel.StorageSource src, ItemKey item, int n) {
        ItemCounts contents = s.storage.computeIfAbsent(src.id(), k -> src.contents().copy());
        int t = contents.take(item, n);
        if (t == 0) {
            return 0;
        }
        s.steps.add(new PlanStep.Fetch(src.id(), src.label(), item, t, src.distance()));
        double ticks = opt.ticksPerClick * Math.ceil(t / 64.0);
        if (s.visitedStorages.add(src.id())) {
            ticks += opt.travelTicks(src.distance()) + CONTAINER_OPEN_TICKS;
        }
        s.ticks += ticks;
        s.cost += ticks;
        return t;
    }

    private boolean anyFarStorageHas(State s, ItemKey item) {
        for (WorldModel.StorageSource src : s.world.storages) {
            if (src.distance() > opt.storagePreferenceRadius) {
                ItemCounts contents = s.storage.getOrDefault(src.id(), src.contents());
                if (contents.get(item) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void markMissing(State s, ItemKey item, int n) {
        s.missing.add(item, n);
        s.cost += MISSING_PENALTY * n;
    }

    /** Merges directly adjacent steps that do the same thing (two fetches of the same item from one chest). */
    private static List<PlanStep> mergeAdjacent(List<PlanStep> steps) {
        List<PlanStep> out = new ArrayList<>();
        for (PlanStep step : steps) {
            PlanStep last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last instanceof PlanStep.Fetch a && step instanceof PlanStep.Fetch b
                    && a.storageId().equals(b.storageId()) && a.item().equals(b.item())) {
                out.set(out.size() - 1, new PlanStep.Fetch(a.storageId(), a.label(), a.item(), a.count() + b.count(), a.distance()));
            } else if (last instanceof PlanStep.Craft a && step instanceof PlanStep.Craft b
                    && a.recipe().id().equals(b.recipe().id()) && a.station() == b.station()) {
                out.set(out.size() - 1, new PlanStep.Craft(a.recipe(), a.crafts() + b.crafts(), a.station()));
            } else if (last instanceof PlanStep.UnpackShulker a && step instanceof PlanStep.UnpackShulker b && a.item().equals(b.item())) {
                out.set(out.size() - 1, new PlanStep.UnpackShulker(a.item(), a.count() + b.count()));
            } else {
                out.add(step);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ state

    /** The planning ledger. Copied for dry runs; the winner is adopted. */
    private static final class State {
        final WorldModel world;
        /** Original (usable) inventory still unclaimed. */
        ItemCounts original;
        /** Items produced by earlier steps and not yet consumed (surplus, remainders). */
        ItemCounts produced;
        ItemCounts shulker;
        Map<String, ItemCounts> storage;
        EnumSet<StationKind> ready;
        List<StationKind> placed;
        List<PlanStep> steps;
        ItemCounts missing;
        ItemCounts usedFromInventory;
        Set<String> visitedStorages;
        boolean shulkerUsed;
        double cost;
        double ticks;
        final int[] nodes;
        final Set<String> warnings;

        State(WorldModel world) {
            this.world = world;
            this.original = world.usableInventory();
            this.produced = new ItemCounts();
            this.shulker = world.inShulkers.copy();
            this.storage = new HashMap<>();
            this.ready = EnumSet.of(StationKind.INVENTORY_GRID);
            this.placed = new ArrayList<>();
            this.steps = new ArrayList<>();
            this.missing = new ItemCounts();
            this.usedFromInventory = new ItemCounts();
            this.visitedStorages = new HashSet<>();
            this.nodes = new int[1];
            this.warnings = new LinkedHashSet<>();
        }

        private State(State o) {
            this.world = o.world;
            this.original = o.original.copy();
            this.produced = o.produced.copy();
            this.shulker = o.shulker.copy();
            this.storage = new HashMap<>();
            o.storage.forEach((k, v) -> this.storage.put(k, v.copy()));
            this.ready = EnumSet.copyOf(o.ready);
            this.placed = new ArrayList<>(o.placed);
            this.steps = new ArrayList<>(o.steps);
            this.missing = o.missing.copy();
            this.usedFromInventory = o.usedFromInventory.copy();
            this.visitedStorages = new HashSet<>(o.visitedStorages);
            this.shulkerUsed = o.shulkerUsed;
            this.cost = o.cost;
            this.ticks = o.ticks;
            this.nodes = o.nodes; // shared budget
            this.warnings = o.warnings; // shared
        }

        State copy() {
            return new State(this);
        }

        void adopt(State o) {
            this.original = o.original;
            this.produced = o.produced;
            this.shulker = o.shulker;
            this.storage = o.storage;
            this.ready = o.ready;
            this.placed = o.placed;
            this.steps = o.steps;
            this.missing = o.missing;
            this.usedFromInventory = o.usedFromInventory;
            this.visitedStorages = o.visitedStorages;
            this.shulkerUsed = o.shulkerUsed;
            this.cost = o.cost;
            this.ticks = o.ticks;
        }

        int inventoryCount(ItemKey item) {
            return original.get(item) + produced.get(item);
        }

        /** Takes from produced surplus first, then from the original inventory. */
        int takeInventory(ItemKey item, int n) {
            int t = produced.take(item, n);
            int u = original.take(item, n - t);
            usedFromInventory.add(item, u);
            return t + u;
        }

        void addProduced(ItemKey item, int n) {
            produced.add(item, n);
        }
    }
}
