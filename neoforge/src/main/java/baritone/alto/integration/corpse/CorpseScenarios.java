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

package baritone.alto.integration.corpse;

import baritone.alto.Alto;
import baritone.alto.mc.McItems;
import baritone.alto.selftest.Scenario;
import baritone.alto.selftest.TestRig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;

/**
 * In-game scenario for the Corpse integration (registered only when Corpse is installed): die with items, respawn,
 * {@code #corpse} walks back and takes everything out of the corpse.
 */
final class CorpseScenarios {

    private CorpseScenarios() {
    }

    static void register(Alto alto) {
        alto.selfTests().register("corpse", Recover::new);
    }

    static final class Recover extends Scenario {
        private int phase;
        private boolean respawnClicked;

        @Override
        public String name() {
            return "corpse";
        }

        @Override
        public String description() {
            return "die 8 blocks away holding 12 logs, an iron pickaxe and 6 bread, respawn, #corpse: walk back, open the "
                    + "corpse, take everything";
        }

        @Override
        public int timeoutSeconds() {
            return 90;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            GameRules rules = level.getGameRules();
            rules.getRule(GameRules.RULE_KEEPINVENTORY).set(false, level.getServer());
            rules.getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(false, level.getServer());
            player.setRespawnPosition(Level.OVERWORLD, o, 0f, true, false);
            TestRig.resetPlayer(level, player, o, new ItemStack(Items.OAK_LOG, 12), new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.BREAD, 6));
            player.teleportTo(level, o.getX() + 0.5, o.getY(), o.getZ() + 8.5, 0f, 10f);
            player.kill();
        }

        @Override
        public void start(Alto alto, BlockPos o) {
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            switch (phase) {
                case 0 -> {
                    if (mc.screen instanceof DeathScreen && !respawnClicked) {
                        respawnClicked = true;
                        p.respawn(); // what the death screen's Respawn button does
                        return Verdict.RUNNING;
                    }
                    if (p.isDeadOrDying() || mc.screen instanceof DeathScreen) {
                        return ticks > 400 ? Verdict.fail("never respawned") : Verdict.RUNNING;
                    }
                    if (alto.nearestOwnCorpse().isEmpty()) {
                        return ticks > 400 ? Verdict.fail("no corpse of mine is visible after respawning") : Verdict.RUNNING;
                    }
                    if (McItems.count(p.getInventory(), Items.OAK_LOG) != 0) {
                        return Verdict.fail("kept the items after dying (keepInventory?)");
                    }
                    alto.baritone().getCommandManager().execute("corpse");
                    phase = 1;
                    return Verdict.RUNNING;
                }
                default -> {
                    if (alto.process().busy()) {
                        return Verdict.RUNNING;
                    }
                    int logs = McItems.count(p.getInventory(), Items.OAK_LOG);
                    int picks = McItems.count(p.getInventory(), Items.IRON_PICKAXE);
                    int bread = McItems.count(p.getInventory(), Items.BREAD);
                    String s = alto.process().lastResult() + "; logs " + logs + "/12, pickaxe " + picks + "/1, bread " + bread + "/6";
                    return logs == 12 && picks == 1 && bread == 6 ? Verdict.pass(s) : Verdict.fail(s);
                }
            }
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
        }
    }
}
