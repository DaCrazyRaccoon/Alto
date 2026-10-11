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

package baritone.alto.selftest;

import baritone.alto.Alto;
import baritone.alto.mc.McItems;
import baritone.alto.storage.ZoneScanTask;
import baritone.alto.storage.Zones;
import baritone.alto.task.Job;
import baritone.api.schematic.FillSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Milestone 2 scenarios: smelting, fetching from storage for a build, auto-deposit + restock during a job,
 * shulker boxes, auto-eat and tool replacement.
 */
final class M2Scenarios {

    private static final String ZONE = "alto_selftest";

    private M2Scenarios() {
    }

    static void register(SelfTests tests) {
        tests.register("smelt", Smelt::new);
        tests.register("fetch-build", FetchBuild::new);
        tests.register("deposit-restock", DepositRestock::new);
        tests.register("shulker", ShulkerBuild::new);
        tests.register("auto-eat", AutoEat::new);
        tests.register("tool-replace", ToolReplace::new);
    }

    static Inventory inv() {
        return Minecraft.getInstance().player.getInventory();
    }

    static Level level() {
        return Minecraft.getInstance().level;
    }

    static void chest(ServerLevel level, BlockPos pos, ItemStack... contents) {
        level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        if (level.getBlockEntity(pos) instanceof Container c) {
            for (int i = 0; i < contents.length; i++) {
                c.setItem(i, contents[i].copy());
            }
            c.setChanged();
        }
    }

    /** A 4x2x4 pile of blocks at the far end of the test area: a small, local job for the upkeep tests. */
    static void pile(ServerLevel level, BlockPos o, net.minecraft.world.level.block.Block block) {
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 4; z++) {
                    level.setBlock(o.offset(x - 2, y, 8 + z), block.defaultBlockState(), 3);
                }
            }
        }
    }

    static void zone(Alto alto, BlockPos o) {
        String dim = alto.storage().dimension();
        alto.storage().zones().putZone(new Zones.Zone(ZONE, dim, o.getX() + TestRig.X0, o.getY() - 1, o.getZ() + TestRig.Z0,
                o.getX() + TestRig.X1, o.getY() + TestRig.Y1, o.getZ() + TestRig.Z1, List.of()));
        alto.storage().zonesChanged();
    }

    static void removeZone(Alto alto) {
        alto.storage().zones().removeZone(ZONE);
        alto.storage().zonesChanged();
    }

    static int countIn(Level level, BlockPos pos, net.minecraft.world.item.Item item) {
        if (level.getBlockEntity(pos) instanceof Container c) {
            int n = 0;
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).is(item)) {
                    n += c.getItem(i).getCount();
                }
            }
            return n;
        }
        return -1;
    }

    /** Server view of a container (the client has no contents for closed chests). */
    static int serverCount(BlockPos pos, net.minecraft.world.item.Item item) {
        try {
            var server = Minecraft.getInstance().getSingleplayerServer();
            ServerLevel sl = server.getLevel(Minecraft.getInstance().level.dimension());
            return server.submit(() -> countIn(sl, pos, item)).get();
        } catch (Exception e) {
            return -2;
        }
    }

    // ------------------------------------------------------------------------------------------ smelt

    static final class Smelt extends Scenario {
        private final TestRig rig = new TestRig();

        @Override
        public String name() {
            return "smelt";
        }

        @Override
        public String description() {
            return "#craft iron_ingot 4 holding raw iron, coal, a furnace and a pickaxe (place furnace, smelt, collect, pick up)";
        }

        @Override
        public int timeoutSeconds() {
            return 150;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            TestRig.resetPlayer(level, player, o, new ItemStack(Items.RAW_IRON, 4), new ItemStack(Items.COAL, 1),
                    new ItemStack(Items.FURNACE, 1), new ItemStack(Items.STONE_PICKAXE, 1));
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            rig.setting(TestRig.settings().altoMaxFurnaces, 1);
            alto.baritone().getCommandManager().execute("craft minecraft:iron_ingot 4");
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            if (ticks < 5 || alto.process().busy()) {
                return Verdict.RUNNING;
            }
            int ingots = McItems.count(inv(), Items.IRON_INGOT);
            int furnaceItems = McItems.count(inv(), Items.FURNACE);
            int furnaceBlocks = M1Scenarios.countIn(level(), o.offset(TestRig.X0, -1, TestRig.Z0), o.offset(TestRig.X1, TestRig.Y1, TestRig.Z1), Blocks.FURNACE);
            String s = "ingots " + ingots + ", furnace items " + furnaceItems + ", furnace blocks " + furnaceBlocks
                    + ", raw iron left " + McItems.count(inv(), Items.RAW_IRON);
            return ingots == 4 && furnaceItems == 1 && furnaceBlocks == 0 ? Verdict.pass(s) : Verdict.fail(s);
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
            rig.restore();
        }
    }

    // ------------------------------------------------------------------------------------------ fetch for a build

    static final class FetchBuild extends Scenario {
        private final TestRig rig = new TestRig();
        private int phase;
        private BlockPos chestPos;

        @Override
        public String name() {
            return "fetch-build";
        }

        @Override
        public String description() {
            return "fill 3x1x3 with oak planks with an empty inventory; oak logs only in a chest in a storage zone (scan, fetch, craft, build)";
        }

        @Override
        public int timeoutSeconds() {
            return 150;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            chestPos = o.offset(-4, 0, 2);
            chest(level, chestPos, new ItemStack(Items.OAK_LOG, 16));
            TestRig.resetPlayer(level, player, o);
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            rig.setting(TestRig.settings().alwaysUseCraftingStation, false);
            zone(alto, o);
            alto.startJob(Job.of(new ZoneScanTask(alto, alto.storage().zones().zone(ZONE).orElseThrow()), "selftest"));
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            if (phase == 0) {
                if (ticks > 5 && !alto.process().busy()) {
                    phase = 1;
                    alto.baritone().getBuilderProcess().build("alto-selftest", new FillSchematic(3, 1, 3, Blocks.OAK_PLANKS.defaultBlockState()), o.offset(-1, 0, 3));
                }
                return Verdict.RUNNING;
            }
            int planks = M1Scenarios.countIn(level(), o.offset(-1, 0, 3), o.offset(1, 0, 5), Blocks.OAK_PLANKS);
            if (alto.baritone().getBuilderProcess().isPaused()) {
                return Verdict.fail("builder paused; placed " + planks + "/9");
            }
            if (planks == 9 && !alto.baritone().getBuilderProcess().isActive() && !alto.process().busy()) {
                int left = serverCount(chestPos, Items.OAK_LOG);
                String s = "9/9 placed; logs left in the chest " + left + "; index knows "
                        + alto.storage().index().size() + " container(s)";
                return left >= 0 && left < 16 ? Verdict.pass(s) : Verdict.fail("logs did not come from the chest: " + s);
            }
            return Verdict.RUNNING;
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
            removeZone(alto);
            rig.restore();
        }
    }

    // ------------------------------------------------------------------------------------------ auto-deposit + restock

    static final class DepositRestock extends Scenario {
        private final TestRig rig = new TestRig();
        private BlockPos chestA;
        private BlockPos chestB;

        @Override
        public String name() {
            return "deposit-restock";
        }

        @Override
        public String description() {
            return "full inventory while mining a log pile: auto-deposit into a zone (keeping tools and food), then restock torches from the zone";
        }

        @Override
        public int timeoutSeconds() {
            return 180;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            chestA = o.offset(-3, 0, 4);
            chestB = o.offset(3, 0, 4);
            chest(level, chestA);
            chest(level, chestB, new ItemStack(Items.TORCH, 32));
            List<ItemStack> items = new ArrayList<>();
            items.add(new ItemStack(Items.STONE_PICKAXE));
            items.add(new ItemStack(Items.BREAD, 5));
            for (int i = 0; i < 34; i++) {
                items.add(new ItemStack(Items.COBBLESTONE, 64));
            }
            TestRig.resetPlayer(level, player, o, items.toArray(new ItemStack[0]));
            pile(level, o, Blocks.STRIPPED_OAK_LOG); // never generated naturally: #mine stays in the test area
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            rig.setting(TestRig.settings().altoAutoDeposit, true);
            rig.setting(TestRig.settings().altoRestock, true);
            rig.setting(TestRig.settings().altoRestockItems, new ArrayList<>(List.of("minecraft:torch=16")));
            rig.setting(TestRig.settings().altoKeepItems, new ArrayList<>());
            zone(alto, o);
            alto.selfTests().allowUpkeep(true);
            alto.baritone().getCommandManager().execute("mine minecraft:stripped_oak_log");
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            alto.selfTests().allowUpkeep(true);
            int cobble = McItems.count(inv(), Items.COBBLESTONE);
            int torches = McItems.count(inv(), Items.TORCH);
            if (cobble == 0 && torches >= 16 && !alto.process().busy()) {
                boolean kept = McItems.count(inv(), Items.STONE_PICKAXE) == 1 && McItems.count(inv(), Items.BREAD) == 5;
                String s = "cobblestone deposited (chest A " + serverCount(chestA, Items.COBBLESTONE) + ", chest B "
                        + serverCount(chestB, Items.COBBLESTONE) + "), torches restocked " + torches + ", tools/food kept " + kept;
                return kept ? Verdict.pass(s) : Verdict.fail(s);
            }
            return Verdict.RUNNING;
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
            removeZone(alto);
            rig.restore();
        }
    }

    // ------------------------------------------------------------------------------------------ shulker

    static final class ShulkerBuild extends Scenario {
        private final TestRig rig = new TestRig();

        @Override
        public String name() {
            return "shulker";
        }

        @Override
        public String description() {
            return "fill 3x1x3 with oak planks; the only logs are inside a carried shulker box (place, take, pick up, craft, build)";
        }

        @Override
        public int timeoutSeconds() {
            return 150;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            ItemStack box = new ItemStack(Items.SHULKER_BOX);
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.OAK_LOG, 4))));
            TestRig.resetPlayer(level, player, o, box);
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            rig.setting(TestRig.settings().alwaysUseCraftingStation, false);
            alto.baritone().getBuilderProcess().build("alto-selftest", new FillSchematic(3, 1, 3, Blocks.OAK_PLANKS.defaultBlockState()), o.offset(-1, 0, 3));
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            int planks = M1Scenarios.countIn(level(), o.offset(-1, 0, 3), o.offset(1, 0, 5), Blocks.OAK_PLANKS);
            if (alto.baritone().getBuilderProcess().isPaused()) {
                return Verdict.fail("builder paused; placed " + planks + "/9");
            }
            if (planks == 9 && !alto.baritone().getBuilderProcess().isActive() && !alto.process().busy()) {
                int boxes = 0;
                int logsInBox = 0;
                for (ItemStack s : inv().items) {
                    if (McItems.isShulkerBox(s)) {
                        boxes++;
                        ItemContainerContents c = s.get(DataComponents.CONTAINER);
                        if (c != null) {
                            for (ItemStack in : c.nonEmptyItems()) {
                                if (in.is(Items.OAK_LOG)) {
                                    logsInBox += in.getCount();
                                }
                            }
                        }
                    }
                }
                int boxBlocks = M1Scenarios.countIn(level(), o.offset(TestRig.X0, 0, TestRig.Z0), o.offset(TestRig.X1, TestRig.Y1, TestRig.Z1), Blocks.SHULKER_BOX);
                String s = "9/9 placed; shulker boxes carried " + boxes + " (logs inside " + logsInBox + "), in world " + boxBlocks;
                return boxes == 1 && boxBlocks == 0 ? Verdict.pass(s) : Verdict.fail(s);
            }
            return Verdict.RUNNING;
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
            rig.restore();
        }
    }

    // ------------------------------------------------------------------------------------------ auto-eat

    static final class AutoEat extends Scenario {
        private final TestRig rig = new TestRig();

        @Override
        public String name() {
            return "auto-eat";
        }

        @Override
        public String description() {
            return "hunger 8 with bread in the inventory and auto-eat switched on: eats bread until above the threshold";
        }

        @Override
        public int timeoutSeconds() {
            return 40;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            TestRig.resetPlayer(level, player, o, new ItemStack(Items.BREAD, 3));
            player.getFoodData().setFoodLevel(8);
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            rig.setting(TestRig.settings().altoAutoEat, true);
            rig.setting(TestRig.settings().altoAutoEatOnlyWhileBotting, false);
            alto.selfTests().allowUpkeep(true);
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            alto.selfTests().allowUpkeep(true);
            int bread = McItems.count(inv(), Items.BREAD);
            int food = Minecraft.getInstance().player.getFoodData().getFoodLevel();
            if (bread < 3 && food > 8 && !alto.process().busy()) {
                // It keeps eating while hunger is at or below the threshold (14); bread gives 5.
                return Verdict.pass("ate " + (3 - bread) + " bread, hunger now " + food);
            }
            return Verdict.RUNNING;
        }

        @Override
        public void cleanup(Alto alto) {
            alto.process().cancelJob("self-test end");
            rig.restore();
        }
    }

    // ------------------------------------------------------------------------------------------ tool replacement

    static final class ToolReplace extends Scenario {
        private final TestRig rig = new TestRig();

        @Override
        public String name() {
            return "tool-replace";
        }

        @Override
        public String description() {
            return "nearly broken stone pickaxe in hand while mining polished andesite, with cobblestone and sticks: crafts a new stone pickaxe";
        }

        @Override
        public int timeoutSeconds() {
            return 120;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            ItemStack worn = new ItemStack(Items.STONE_PICKAXE);
            worn.setDamageValue(worn.getMaxDamage() - 3);
            TestRig.resetPlayer(level, player, o, worn, new ItemStack(Items.COBBLESTONE, 3), new ItemStack(Items.STICK, 2),
                    new ItemStack(Items.CRAFTING_TABLE, 1));
            pile(level, o, Blocks.POLISHED_ANDESITE); // never generated naturally
            player.getInventory().selected = 0;
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            rig.setting(TestRig.settings().altoToolUpkeep, true);
            rig.setting(TestRig.settings().altoToolMaxTier, "stone");
            Minecraft.getInstance().player.getInventory().selected = 0;
            alto.selfTests().allowUpkeep(true);
            alto.baritone().getCommandManager().execute("mine minecraft:polished_andesite");
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            alto.selfTests().allowUpkeep(true);
            int picks = McItems.count(inv(), Items.STONE_PICKAXE);
            if (picks >= 2 && !alto.process().busy()) {
                return Verdict.pass("now carrying " + picks + " stone pickaxes; table items " + McItems.count(inv(), Items.CRAFTING_TABLE));
            }
            return Verdict.RUNNING;
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
            rig.restore();
        }
    }
}
