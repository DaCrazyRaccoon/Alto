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
import baritone.alto.script.lang.Value;
import baritone.alto.script.runtime.RunStatus;
import baritone.alto.script.runtime.ScriptRun;
import baritone.alto.storage.ZoneScanTask;
import baritone.alto.task.Job;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Milestone 5 scenarios: a long, complicated Alto script runs from the scripts folder like a user's script.
 */
final class M5Scenarios {

    static final String LONG_SCRIPT = "/assets/baritoe/alto/selftest/long_mission.alto.json";

    private M5Scenarios() {
    }

    static void register(SelfTests tests) {
        tests.register("script-long", LongScript::new);
    }

    /** The long mission script (1300+ lines, 5 handlers, 5 procedures, about 30 actions) must end with result "ok". */
    static final class LongScript extends Scenario {
        private final TestRig rig = new TestRig();
        private int phase;
        private int pings;
        private BlockPos chest;
        private BlockPos note;

        @Override
        public String name() {
            return "script-long";
        }

        @Override
        public String description() {
            return "the long mission script: storage, crafting, stations, smelting, mining, filling, a schematic with a "
                    + "supply run, eating, depositing, timers, a chat trigger, item events, procedures; 12 self-checks";
        }

        @Override
        public int timeoutSeconds() {
            return 900;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            chest = o.offset(-4, 0, 1);
            M2Scenarios.chest(level, chest, new ItemStack(Items.OAK_LOG, 16), new ItemStack(Items.RAW_IRON, 3),
                    new ItemStack(Items.COAL, 4), new ItemStack(Items.COBBLESTONE, 16), new ItemStack(Items.STONE_BRICKS, 20),
                    new ItemStack(Items.GLASS, 20));
            note = o.offset(4, 0, 1);
            level.setBlock(note, Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, 0), 3);
            M2Scenarios.pile(level, o, Blocks.POLISHED_ANDESITE);
            TestRig.resetPlayer(level, player, o, new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STICK, 1),
                    new ItemStack(Items.COAL, 1), new ItemStack(Items.BREAD, 3));
            player.getFoodData().setFoodLevel(14);
            // Peaceful refills the hunger bar by itself; off for this test so the script's eat block really eats.
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_NATURAL_REGENERATION).set(false, level.getServer());
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            rig.setting(TestRig.settings().altoScripts, true);
            rig.setting(TestRig.settings().altoSupplyRuns, true);
            rig.setting(TestRig.settings().altoSafetyStops, true);
            rig.setting(TestRig.settings().alwaysUseCraftingStation, false);
            try {
                Path game = Minecraft.getInstance().gameDirectory.toPath();
                M4Scenarios.SupplyBuild.writeSchematic(game.resolve("schematics").resolve("alto_supply_test.nbt"));
                Path target = alto.scripts().folder().resolve("long_mission.alto.json");
                Files.createDirectories(target.getParent());
                try (InputStream in = M5Scenarios.class.getResourceAsStream(LONG_SCRIPT)) {
                    if (in == null) {
                        throw new IOException("missing " + LONG_SCRIPT);
                    }
                    Files.write(target, in.readAllBytes());
                }
            } catch (IOException e) {
                throw new IllegalStateException("could not prepare the long script", e);
            }
            M2Scenarios.zone(alto, o);
            alto.startJob(Job.of(new ZoneScanTask(alto, alto.storage().zones().zone("alto_selftest").orElseThrow()), "selftest"));
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            if (phase == 0) {
                if (ticks < 5 || alto.process().busy()) {
                    return Verdict.RUNNING;
                }
                List<String> problem = alto.scripts().start("long_mission", true);
                if (problem != null) {
                    return Verdict.fail("script did not start: " + problem);
                }
                phase = 1;
                return Verdict.RUNNING;
            }
            ScriptRun run = alto.scripts().current();
            if (run == null) {
                return Verdict.fail("no script run");
            }
            if (!run.status().isEnded()) {
                Value pinged = run.variables().get("chat_pings");
                // Type the chat trigger as the user would, every 30 s until the script has seen it.
                if (ticks % 600 == 300 && pinged instanceof Value.Num n && n.value() < 1) {
                    pings++;
                    if (!alto.scripts().onTypedChat("!ping")) {
                        return Verdict.fail("the chat trigger was not recognised");
                    }
                }
                return Verdict.RUNNING;
            }
            Level level = Minecraft.getInstance().level;
            int wall = M1Scenarios.countIn(level, o.offset(-5, 0, 6), o.offset(-3, 2, 6), Blocks.OAK_PLANKS);
            int glass = M1Scenarios.countIn(level, o.offset(2, 1, 5), o.offset(4, 1, 7), Blocks.GLASS);
            int noteValue = M3Scenarios.onServer(sl -> sl.getBlockState(note).getValue(NoteBlock.NOTE), -1);
            Value result = run.variables().get("result");
            Value checks = run.variables().get("checks_passed");
            String s = "status " + run.status() + ", result " + (result == null ? "?" : result.display()) + ", checks "
                    + (checks == null ? "?" : checks.display()) + "/12, actions " + run.actionsStarted() + ", wall " + wall
                    + "/9, glass " + glass + "/9, note " + noteValue + ", pings typed " + pings
                    + (run.status() == RunStatus.FINISHED ? "" : "; " + alto.scripts().lastEnd());
            boolean ok = run.status() == RunStatus.FINISHED && result != null && result.display().equals("ok")
                    && wall == 9 && glass == 9 && noteValue == 1;
            return ok ? Verdict.pass(s) : Verdict.fail(s);
        }

        @Override
        public void cleanup(Alto alto) {
            var server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
                server.execute(() -> server.overworld().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_NATURAL_REGENERATION).set(true, server));
            }
            alto.scripts().stop("self-test cleanup");
            alto.baritone().getPathingBehavior().cancelEverything();
            M2Scenarios.removeZone(alto);
            rig.restore();
        }
    }
}
