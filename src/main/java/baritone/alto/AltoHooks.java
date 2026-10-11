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

package baritone.alto;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import baritone.utils.pathing.Avoidance;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The few static entry points that upstream Baritone code calls (each call site is marked "ALTO FORK").
 * <p>
 * Thread safety: the pathfinder runs off the client thread and calls the block checks here. They only read
 * immutable sets published through volatile fields, which the client thread replaces as a whole
 * ({@link #publishBlockRules}, {@link #publishProtection}). {@link #extraAvoidances} is called while a path search
 * is being set up on the client thread.
 */
public final class AltoHooks {

    private static volatile Set<Block> neverWalkInto = Set.of();
    private static volatile Set<Block> neverWalkOn = Set.of();
    private static volatile ProtectionSnapshot protection = ProtectionSnapshot.NONE;
    private static final List<Function<IPlayerContext, List<Avoidance>>> AVOIDANCE_PROVIDERS = new CopyOnWriteArrayList<>();

    private AltoHooks() {
    }

    /** Positions the bot must neither break nor place in (claims of other players). Immutable. */
    public interface ProtectionSnapshot {
        ProtectionSnapshot NONE = (x, y, z) -> false;

        boolean isProtected(int x, int y, int z);
    }

    private static volatile java.util.function.Predicate<Block> neverBreakExtra = b -> false;

    private static boolean on() {
        return Baritone.settings().altoEnabled.value;
    }

    // ------------------------------------------------------------------------------ publishing (client thread)

    public static void publishBlockRules(Set<Block> walkInto, Set<Block> walkOn) {
        neverWalkInto = Set.copyOf(walkInto);
        neverWalkOn = Set.copyOf(walkOn);
    }

    public static void publishProtection(ProtectionSnapshot snapshot) {
        protection = snapshot == null ? ProtectionSnapshot.NONE : snapshot;
    }

    public static void addAvoidanceProvider(Function<IPlayerContext, List<Avoidance>> provider) {
        AVOIDANCE_PROVIDERS.add(provider);
    }

    /** Integrations: blocks without a block entity that paths must not break either (e.g. Create train tracks). */
    public static void addNeverBreak(java.util.function.Predicate<Block> blocks) {
        java.util.function.Predicate<Block> old = neverBreakExtra;
        neverBreakExtra = old.or(blocks);
    }

    // ------------------------------------------------------------------------------ called by upstream code

    /** MovementHelper.avoidWalkingInto: hazard blocks (e.g. Create saws and crushing wheels). */
    public static boolean avoidWalkingInto(Block block) {
        return on() && neverWalkInto.contains(block);
    }

    /** MovementHelper.canWalkOnBlockState: blocks never stood on (e.g. Create belts in "avoid" mode). */
    public static boolean neverWalkOn(Block block) {
        return on() && neverWalkOn.contains(block);
    }

    /**
     * MovementHelper.avoidBreaking: a path never breaks blocks that hold data (machines, containers, signs, Create
     * shafts and belts...) or train tracks (altoProtectMachines). Read on the path search thread.
     */
    public static boolean neverBreak(net.minecraft.world.level.block.state.BlockState state) {
        return on() && Baritone.settings().altoProtectMachines.value && (state.hasBlockEntity() || neverBreakExtra.test(state.getBlock()));
    }

    /** CalculationContext.isPossiblyProtected: never break or place here (other players' claims). */
    public static boolean isProtected(int x, int y, int z) {
        return on() && protection.isProtected(x, y, z);
    }

    /** Avoidance.create: extra areas to avoid (running machines, moving contraptions). Client thread. */
    public static List<Avoidance> extraAvoidances(IPlayerContext ctx) {
        if (!on() || AVOIDANCE_PROVIDERS.isEmpty()) {
            return List.of();
        }
        List<Avoidance> out = new ArrayList<>();
        for (Function<IPlayerContext, List<Avoidance>> p : AVOIDANCE_PROVIDERS) {
            try {
                out.addAll(p.apply(ctx));
            } catch (Throwable t) {
                AltoLog.debug("avoidance provider failed: " + t);
            }
        }
        return out;
    }

    /** Ladders, including modded ones (Create metal ladders, copycats...) when altoCompatClimbables is on. */
    public static boolean isLadder(Block block) {
        return block == Blocks.LADDER || block instanceof LadderBlock && on() && Baritone.settings().altoCompatClimbables.value;
    }

    /** Scaffolding, including modded subclasses (Create metal scaffolding). */
    public static boolean isScaffolding(Block block) {
        return block == Blocks.SCAFFOLDING || block instanceof ScaffoldingBlock && on() && Baritone.settings().altoCompatClimbables.value;
    }

    // ------------------------------------------------------------------------------ farming (FarmProcess)

    private static volatile Set<Block> moddedCrops;
    private static volatile Set<Block> moddedFarmland;
    private static volatile Set<net.minecraft.world.item.Item> moddedSeeds;
    private static volatile boolean farmOutOfSeeds;
    private static int farmSeedWait;

    private static void computeCrops() {
        if (moddedCrops != null) {
            return;
        }
        Set<Block> crops = new java.util.HashSet<>();
        Set<Block> farmland = new java.util.HashSet<>();
        for (Block b : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (b instanceof net.minecraft.world.level.block.CropBlock && b != Blocks.WHEAT && b != Blocks.CARROTS
                    && b != Blocks.POTATOES && b != Blocks.BEETROOTS && !harvestedByHand(b)) {
                crops.add(b);
            }
            if (b instanceof FarmBlock && b != Blocks.FARMLAND) {
                farmland.add(b);
            }
        }
        Set<net.minecraft.world.item.Item> seeds = new java.util.HashSet<>();
        for (net.minecraft.world.item.Item i : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (i instanceof net.minecraft.world.item.BlockItem bi && crops.contains(bi.getBlock())) {
                seeds.add(i);
            }
        }
        moddedFarmland = Set.copyOf(farmland);
        moddedSeeds = Set.copyOf(seeds);
        moddedCrops = Set.copyOf(crops);
    }

    /** Crops harvested by right-clicking (breaking them would destroy the plant), e.g. Farmer's Delight tomatoes. */
    private static boolean harvestedByHand(Block b) {
        for (Class<?> c = b.getClass(); c != null && c != Block.class; c = c.getSuperclass()) {
            String n = c.getSimpleName();
            if (n.contains("Tomato") || n.contains("Vine") || n.contains("Berry")) {
                return true;
            }
        }
        return false;
    }

    private static boolean cropsOn() {
        return on() && Baritone.settings().altoCompatCrops.value;
    }

    /** FarmProcess scan: modded crops and modded farmland as extra block types. */
    public static List<Block> extraFarmBlocks() {
        if (!cropsOn()) {
            return List.of();
        }
        computeCrops();
        List<Block> out = new ArrayList<>(moddedCrops);
        out.removeAll(Baritone.settings().altoFarmIgnore.value);
        out.addAll(moddedFarmland);
        return out;
    }

    /** A modded crop block (any age) that #farm handles. */
    public static boolean isModdedCrop(BlockState state) {
        if (!cropsOn()) {
            return false;
        }
        computeCrops();
        return moddedCrops.contains(state.getBlock()) && !Baritone.settings().altoFarmIgnore.value.contains(state.getBlock());
    }

    /** A fully grown modded crop. */
    public static boolean isModdedCropReady(BlockState state) {
        return isModdedCrop(state) && ((net.minecraft.world.level.block.CropBlock) state.getBlock()).isMaxAge(state);
    }

    /** Seeds of modded crops that are planted on farmland. */
    public static boolean isModdedSeed(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty() || !cropsOn()) {
            return false;
        }
        computeCrops();
        return moddedSeeds.contains(stack.getItem());
    }

    /** Dropped items #farm also picks up: modded seeds and any food. */
    public static boolean farmPickup(net.minecraft.world.item.ItemStack stack) {
        return on() && Baritone.settings().altoFarmPickupModded.value
                && (isModdedSeed(stack) || stack.has(net.minecraft.core.component.DataComponents.FOOD));
    }

    /** FarmProcess reports that there is open farmland but nothing to plant (Alto restocks seeds). */
    public static void setFarmOutOfSeeds(boolean value) {
        farmOutOfSeeds = value;
        farmSeedWait = value ? farmSeedWait + 1 : 0;
    }

    /** Instead of "Farm failed", #farm waits up to 30 seconds while Alto restocks seeds. */
    public static boolean farmShouldWaitForSeeds() {
        return on() && farmOutOfSeeds && Baritone.settings().altoFarmRestockSeeds.value && farmSeedWait < 600;
    }

    public static boolean farmOutOfSeeds() {
        return farmOutOfSeeds;
    }

    /** All plantable seed items Alto knows (vanilla farmland seeds plus modded). */
    public static Set<net.minecraft.world.item.Item> moddedSeeds() {
        computeCrops();
        return moddedSeeds;
    }

    /** Drops when Baritone's loot-table lookup is unavailable: the block's own item plus Alto's gather table. */
    public static List<net.minecraft.world.item.Item> fallbackDrops(Block block) {
        List<net.minecraft.world.item.Item> out = new ArrayList<>();
        if (block.asItem() != net.minecraft.world.item.Items.AIR) {
            out.add(block.asItem());
        }
        try {
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
            baritone.alto.planner.mc.GatherTable.sources().forEach((item, src) -> {
                if (src.blocks().contains(id)) {
                    var it = baritone.alto.mc.McItems.item(item);
                    if (it != net.minecraft.world.item.Items.AIR && !out.contains(it)) {
                        out.add(it);
                    }
                }
            });
        } catch (Throwable ignored) {
            // the gather table is optional here
        }
        return out;
    }

    /** Farmland, including modded farmland (Farmer's Delight rich soil farmland). */
    public static boolean isFarmland(Block block) {
        return block == Blocks.FARMLAND || block instanceof FarmBlock && on() && Baritone.settings().altoCompatCrops.value;
    }
}
