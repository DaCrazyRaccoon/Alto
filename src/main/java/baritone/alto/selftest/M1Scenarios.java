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
import baritone.api.schematic.FillSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * Milestone 1 scenarios: the planks example (both station modes) and crafting a 3x3 item from logs.
 */
final class M1Scenarios {

    private M1Scenarios() {
    }

    static void register(SelfTests tests) {
        tests.register("planks", () -> new PlanksFill(false));
        tests.register("planks-table", () -> new PlanksFill(true));
        tests.register("craft-chest", CraftChest::new);
    }

    static int countIn(Level level, BlockPos from, BlockPos to, net.minecraft.world.level.block.Block block) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(from, to)) {
            if (level.getBlockState(p).is(block)) {
                n++;
            }
        }
        return n;
    }

    static int tablesNear(Level level, BlockPos o) {
        return countIn(level, o.offset(TestRig.X0, 0, TestRig.Z0), o.offset(TestRig.X1, TestRig.Y1, TestRig.Z1), Blocks.CRAFTING_TABLE);
    }

    /** The headline example: fill an area with oak planks while only holding oak logs. */
    static final class PlanksFill extends Scenario {
        private final boolean forceTable;
        private final TestRig rig = new TestRig();

        PlanksFill(boolean forceTable) {
            this.forceTable = forceTable;
        }

        @Override
        public String name() {
            return forceTable ? "planks-table" : "planks";
        }

        @Override
        public String description() {
            return forceTable
                    ? "fill 3x1x3 with oak planks holding only 4 oak logs, alwaysUseCraftingStation=true (place table, craft, pick up)"
                    : "fill 3x1x3 with oak planks holding only 3 oak logs (planks crafted in the 2x2 grid)";
        }

        @Override
        public int timeoutSeconds() {
            return 150;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            TestRig.resetPlayer(level, player, o, new ItemStack(Items.OAK_LOG, forceTable ? 4 : 3));
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            rig.setting(TestRig.settings().alwaysUseCraftingStation, forceTable);
            rig.setting(TestRig.settings().altoBuilderSupply, true);
            alto.baritone().getBuilderProcess().build("alto-selftest", new FillSchematic(3, 1, 3, Blocks.OAK_PLANKS.defaultBlockState()), o.offset(-1, 0, 3));
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            Level level = Minecraft.getInstance().level;
            int planks = countIn(level, o.offset(-1, 0, 3), o.offset(1, 0, 5), Blocks.OAK_PLANKS);
            boolean builderActive = alto.baritone().getBuilderProcess().isActive();
            if (alto.baritone().getBuilderProcess().isPaused()) {
                return Verdict.fail("the builder paused (supply failed); placed " + planks + "/9");
            }
            if (planks == 9 && !builderActive && !alto.process().busy()) {
                Inventory inv = Minecraft.getInstance().player.getInventory();
                int tablesInWorld = tablesNear(level, o);
                int tableItems = McItems.count(inv, Items.CRAFTING_TABLE);
                String summary = "9/9 planks placed; left over: " + McItems.count(inv, Items.OAK_PLANKS) + " planks, "
                        + McItems.count(inv, Items.OAK_LOG) + " logs; tables in world " + tablesInWorld + ", in inventory " + tableItems;
                if (forceTable) {
                    if (tablesInWorld != 0) {
                        return Verdict.fail("the placed crafting table was not picked up; " + summary);
                    }
                    if (tableItems != 1) {
                        return Verdict.fail("expected the crafting table back in the inventory; " + summary);
                    }
                } else if (tablesInWorld != 0 || tableItems != 0) {
                    return Verdict.fail("a crafting table was used although planks fit the 2x2 grid; " + summary);
                }
                return Verdict.pass(summary);
            }
            return Verdict.RUNNING;
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
            rig.restore();
        }
    }

    /** #craft chest from logs: needs planks (2x2) and a table for the 3x3 recipe. */
    static final class CraftChest extends Scenario {
        private final TestRig rig = new TestRig();

        @Override
        public String name() {
            return "craft-chest";
        }

        @Override
        public String description() {
            return "#craft chest 1 holding only 3 oak logs (planks, bootstrap table, place, craft, pick up)";
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            TestRig.resetPlayer(level, player, o, new ItemStack(Items.OAK_LOG, 3));
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            rig.setting(TestRig.settings().alwaysUseCraftingStation, false);
            alto.baritone().getCommandManager().execute("craft minecraft:chest 1");
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            if (ticks < 5 || alto.process().busy()) {
                return Verdict.RUNNING;
            }
            Inventory inv = Minecraft.getInstance().player.getInventory();
            int chests = McItems.count(inv, Items.CHEST);
            int tablesInWorld = tablesNear(Minecraft.getInstance().level, o);
            int tableItems = McItems.count(inv, Items.CRAFTING_TABLE);
            String summary = "chests " + chests + ", tables in world " + tablesInWorld + ", table items " + tableItems;
            if (chests == 1 && tablesInWorld == 0 && tableItems == 1) {
                return Verdict.pass(summary);
            }
            return Verdict.fail(summary);
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
            rig.restore();
        }
    }
}
