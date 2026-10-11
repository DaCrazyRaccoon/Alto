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

package baritone.alto.integration.emi;

import baritone.alto.Alto;
import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import baritone.alto.neoforge.AltoClientEvents;
import baritone.alto.selftest.Scenario;
import baritone.alto.selftest.TestRig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
 * In-game scenario for the EMI integration (registered only when EMI and Create are installed): the pick key over
 * EMI's item list sets the filter of a marked Create deployer, and missing items get EMI's recipe categories as hints.
 */
final class EmiScenarios {

    private EmiScenarios() {
    }

    static void register(Alto alto) {
        alto.selfTests().register("emi-pick", PickFilter::new);
    }

    static final class PickFilter extends Scenario {
        private final TestRig rig = new TestRig();
        private BlockPos deployer;
        private int phase;
        private int mark;
        private double px;
        private double py;
        private ItemStack found = ItemStack.EMPTY;
        private String hint = "";

        @Override
        public String name() {
            return "emi-pick";
        }

        @Override
        public String description() {
            return "a marked Create deployer, the inventory open: the pick key over an item in EMI's list sets the deployer's "
                    + "filter to it; #plan-style hints name EMI's recipe categories for an iron sheet";
        }

        @Override
        public int timeoutSeconds() {
            return 90;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            deployer = o.offset(0, 0, 5);
            level.setBlock(deployer, TestRig.facing(TestRig.block("create:deployer").defaultBlockState(), Direction.DOWN), 3);
            TestRig.resetPlayer(level, player, o);
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            rig.setting(TestRig.settings().altoPickAutoFilter, true);
            List<String> hints = alto.hintsFor(ItemCounts.of("create:iron_sheet", 1));
            hint = hints.isEmpty() ? "" : hints.get(0);
            Minecraft.getInstance().setScreen(new InventoryScreen(Minecraft.getInstance().player));
        }

        /** Where on the screen EMI's list shows an item (right of the inventory), like moving the mouse over it. */
        private boolean findEmiItem() {
            Minecraft mc = Minecraft.getInstance();
            int w = mc.getWindow().getGuiScaledWidth();
            int h = mc.getWindow().getGuiScaledHeight();
            for (int y = 8; y < h - 8; y += 3) {
                for (int x = w / 2 + 92; x < w - 2; x += 3) {
                    ItemStack s = EmiIntegration.hovered(x, y);
                    if (!s.isEmpty()) {
                        px = x;
                        py = y;
                        found = s;
                        return true;
                    }
                }
            }
            return false;
        }

        private String filterOnServer() {
            return TestRig.onServer(sl -> {
                BlockEntity be = sl.getBlockEntity(deployer);
                return be == null ? "no block entity" : be.saveWithoutMetadata(sl.registryAccess()).getCompound("Filter").toString();
            }, "server unavailable");
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            Minecraft mc = Minecraft.getInstance();
            switch (phase) {
                case 0 -> {
                    if (!hint.contains("(EMI)")) {
                        return Verdict.fail("no EMI recipe hint for create:iron_sheet (got '" + hint + "')");
                    }
                    if (ticks < 20) {
                        return Verdict.RUNNING; // EMI lays out its panels while the screen renders
                    }
                    if (!(mc.screen instanceof InventoryScreen)) {
                        return Verdict.fail("the inventory screen closed (" + mc.screen + ")");
                    }
                    if (!findEmiItem()) {
                        return Verdict.fail("no item found in EMI's list right of the inventory (is EMI's index hidden?)");
                    }
                    // The filter needs the item in hand: give one (test fixture), then mark the deployer.
                    Item item = found.getItem();
                    TestRig.onServer(sl -> {
                        ServerPlayer sp = sl.getServer().getPlayerList().getPlayer(mc.player.getUUID());
                        sp.getInventory().add(new ItemStack(item));
                        sp.inventoryMenu.broadcastChanges();
                        return true;
                    }, false);
                    alto.picker().clearMarks();
                    alto.picker().mark(deployer);
                    phase = 1;
                    mark = ticks;
                    return Verdict.RUNNING;
                }
                case 1 -> {
                    if (McItems.count(TestRig.inv(), found.getItem()) < 1) {
                        return ticks - mark > 100 ? Verdict.fail("the test item never arrived") : Verdict.RUNNING;
                    }
                    if (!AltoClientEvents.pickAt(alto, px, py)) {
                        return Verdict.fail("the pick key saw nothing at the EMI item (" + px + ", " + py + ")");
                    }
                    phase = 2;
                    return Verdict.RUNNING;
                }
                default -> {
                    if (alto.process().busy() || mc.screen != null) {
                        return Verdict.RUNNING;
                    }
                    ItemKey picked = alto.picker().last();
                    String id = BuiltInRegistries.ITEM.getKey(found.getItem()).toString();
                    String filter = filterOnServer();
                    String s = "EMI item " + id + " at (" + (int) px + "," + (int) py + "), picked " + picked + ", "
                            + alto.process().lastResult() + ", deployer filter " + filter + "; hint: " + hint;
                    return picked.equals(McItems.key(found.getItem())) && filter.contains(id) ? Verdict.pass(s) : Verdict.fail(s);
                }
            }
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
            alto.picker().clearMarks();
            rig.restore();
            if (Minecraft.getInstance().screen != null) {
                Minecraft.getInstance().setScreen(null);
            }
        }
    }
}
