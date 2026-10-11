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
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Milestone 6 scenarios: safety stops.
 */
final class M6Scenarios {

    private M6Scenarios() {
    }

    static void register(SelfTests tests) {
        tests.register("safety-stop", SafetyStop::new);
        tests.register("screens", Screens::new);
    }

    static void setHealth(float health) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(Minecraft.getInstance().player.getUUID());
            if (sp != null) {
                sp.setHealth(health);
            }
        });
    }

    static final class SafetyStop extends Scenario {
        private final TestRig rig = new TestRig();
        private int phase;
        private int mark;
        private int countAtTrip;

        @Override
        public String name() {
            return "safety-stop";
        }

        @Override
        public String description() {
            return "#mine 8 with low health: everything pauses (nothing more is mined), #resume continues and finishes";
        }

        @Override
        public int timeoutSeconds() {
            return 120;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            M2Scenarios.pile(level, o, Blocks.POLISHED_ANDESITE);
            TestRig.resetPlayer(level, player, o, new ItemStack(Items.STONE_PICKAXE));
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            rig.setting(TestRig.settings().altoSafetyStops, true);
            alto.baritone().getCommandManager().execute("mine 8 minecraft:polished_andesite");
        }

        private static int mined() {
            return McItems.count(Minecraft.getInstance().player.getInventory(), Items.POLISHED_ANDESITE);
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            switch (phase) {
                case 0 -> {
                    if (mined() >= 1) { // mining works; now get hurt
                        setHealth(6);
                        phase = 1;
                        mark = ticks;
                    }
                    return Verdict.RUNNING;
                }
                case 1 -> {
                    if (alto.safety().tripped()) {
                        phase = 2;
                        mark = ticks;
                        countAtTrip = mined();
                        return Verdict.RUNNING;
                    }
                    return ticks - mark > 40 ? Verdict.fail("no safety stop 2 s after health dropped to 6") : Verdict.RUNNING;
                }
                case 2 -> {
                    if (ticks - mark < 100) {
                        return Verdict.RUNNING; // 5 s paused
                    }
                    if (mined() > countAtTrip + 1) { // one block may have been broken at the moment of the stop
                        return Verdict.fail("kept mining during the safety stop (" + countAtTrip + " -> " + mined() + ")");
                    }
                    setHealth(20);
                    alto.baritone().getCommandManager().execute("resume");
                    phase = 3;
                    return Verdict.RUNNING;
                }
                default -> {
                    if (alto.safety().tripped()) {
                        return Verdict.fail("#resume did not clear the safety stop");
                    }
                    if (mined() >= 8 && !alto.baritone().getMineProcess().isActive()) {
                        return Verdict.pass("stopped at " + countAtTrip + " mined, paused 5 s, resumed and mined " + mined() + "/8");
                    }
                    return Verdict.RUNNING;
                }
            }
        }

        @Override
        public void cleanup(Alto alto) {
            alto.safety().clear();
            alto.baritone().getPathingBehavior().cancelEverything();
            setHealth(20);
            rig.restore();
        }
    }

    /** The settings screen and the script runner open and render (the harness screenshots them). */
    static final class Screens extends Scenario {
        private int phase;
        private int mark;
        private final java.util.List<String> seen = new java.util.ArrayList<>();

        @Override
        public String name() {
            return "screens";
        }

        @Override
        public String description() {
            return "open Alto's settings screen and the script runner; the HUD shows a running job";
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            TestRig.resetPlayer(level, player, o);
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            alto.openSettingsScreen();
            mark = 0;
        }

        private static String screen() {
            var s = Minecraft.getInstance().screen;
            return s == null ? "none" : s.getClass().getSimpleName();
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            if (ticks - mark < 40) {
                return Verdict.RUNNING;
            }
            seen.add(screen());
            mark = ticks;
            switch (phase++) {
                case 0 -> {
                    Minecraft.getInstance().setScreen(null);
                    alto.openScriptScreen();
                    return Verdict.RUNNING;
                }
                case 1 -> {
                    Minecraft.getInstance().setScreen(null);
                    alto.baritone().getCommandManager().execute("goto " + o.getX() + " " + o.getY() + " " + (o.getZ() + 300));
                    mark = ticks - 30; // look at the HUD 10 ticks after starting the walk
                    return Verdict.RUNNING;
                }
                default -> {
                    java.util.List<String> hud = alto.hudLines();
                    alto.baritone().getPathingBehavior().cancelEverything();
                    String s = "screens " + seen.subList(0, 2) + ", HUD " + hud;
                    return seen.get(0).equals("AltoSettingsScreen") && seen.get(1).equals("ScriptRunnerScreen") && !hud.isEmpty()
                            ? Verdict.pass(s) : Verdict.fail(s);
                }
            }
        }

        @Override
        public void cleanup(Alto alto) {
            Minecraft.getInstance().setScreen(null);
            alto.baritone().getPathingBehavior().cancelEverything();
        }
    }
}
