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
import baritone.alto.integration.Integrations;
import baritone.alto.mc.McItems;
import baritone.alto.storage.ZoneScanTask;
import baritone.alto.task.Job;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Milestone 3 scenarios: integrations load (or stay off) cleanly, use-item-on-block, Create filters and
 * Sophisticated Storage. Mod-specific scenarios are only registered when that mod is installed, so the dev client
 * without mods (the soft-dependency test) runs only the vanilla ones.
 */
final class M3Scenarios {

    /** mod id -> integration name, as registered by the NeoForge entry point. */
    static final Map<String, String> INTEGRATIONS = new LinkedHashMap<>();

    static {
        INTEGRATIONS.put("create", "Create");
        INTEGRATIONS.put("emi", "EMI");
        INTEGRATIONS.put("sophisticatedcore", "Sophisticated Storage/Backpacks");
        INTEGRATIONS.put("openpartiesandclaims", "Open Parties and Claims");
        INTEGRATIONS.put("corpse", "Corpse");
    }

    private M3Scenarios() {
    }

    static void register(SelfTests tests) {
        tests.register("integrations", Integrations_::new);
        tests.register("useon", UseOn::new);
        if (ModList.get().isLoaded("create")) {
            tests.register("create-filter", CreateFilter::new);
        }
        if (ModList.get().isLoaded("sophisticatedstorage")) {
            tests.register("sophisticated-fetch", SophisticatedFetch::new);
        }
    }

    static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }

    static Item item(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    static BlockState facing(BlockState s, Direction d) {
        for (Property<?> p : s.getProperties()) {
            if (p.getName().equals("facing") && p instanceof DirectionProperty dp && dp.getPossibleValues().contains(d)) {
                return s.setValue(dp, d);
            }
        }
        return s;
    }

    /** Runs {@code f} on the integrated server thread (results are authoritative there). */
    static <T> T onServer(Function<ServerLevel, T> f, T fallback) {
        try {
            var server = Minecraft.getInstance().getSingleplayerServer();
            ServerLevel sl = server.getLevel(Minecraft.getInstance().level.dimension());
            return server.submit(() -> f.apply(sl)).get();
        } catch (Exception e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------------------------------ integrations

    static final class Integrations_ extends Scenario {
        @Override
        public String name() {
            return "integrations";
        }

        @Override
        public String description() {
            return "every installed mod Alto supports has its integration active, none failed, none active for missing mods";
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
        }

        @Override
        public void start(Alto alto, BlockPos o) {
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            List<String> problems = new ArrayList<>();
            for (Map.Entry<String, String> e : INTEGRATIONS.entrySet()) {
                boolean installed = ModList.get().isLoaded(e.getKey());
                boolean active = Integrations.isActive(e.getValue());
                if (installed != active) {
                    problems.add(e.getValue() + (installed ? " installed but not active" : " active without the mod"));
                }
            }
            if (!Integrations.failed().isEmpty()) {
                problems.add("failed: " + Integrations.failed());
            }
            String s = "active " + Integrations.active();
            return problems.isEmpty() ? Verdict.pass(s) : Verdict.fail(s + "; " + problems);
        }
    }

    // ------------------------------------------------------------------------------------------ useon

    static final class UseOn extends Scenario {
        private BlockPos note;

        @Override
        public String name() {
            return "useon";
        }

        @Override
        public String description() {
            return "#useon empty <note block> up: walk there, use an empty hand on the top face once (note 0 -> 1)";
        }

        @Override
        public int timeoutSeconds() {
            return 60;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            note = o.offset(2, 0, 6);
            level.setBlock(note, Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, 0), 3);
            TestRig.resetPlayer(level, player, o, new ItemStack(Items.DIRT, 16));
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            alto.baritone().getCommandManager().execute("useon empty " + note.getX() + " " + note.getY() + " " + note.getZ() + " up");
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            if (ticks < 5 || alto.process().busy()) {
                return Verdict.RUNNING;
            }
            int n = onServer(sl -> sl.getBlockState(note).getValue(NoteBlock.NOTE), -1);
            String s = "note " + n + ", dirt " + McItems.count(M2Scenarios.inv(), Items.DIRT);
            return n == 1 && McItems.count(M2Scenarios.inv(), Items.DIRT) == 16 ? Verdict.pass(s) : Verdict.fail(s);
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
        }
    }

    // ------------------------------------------------------------------------------------------ Create filter

    static final class CreateFilter extends Scenario {
        private BlockPos deployer;

        @Override
        public String name() {
            return "create-filter";
        }

        @Override
        public String description() {
            return "#filter iron_ingot on a Create deployer: walk there, hold the ingot, click the filter slot (ingot not used up)";
        }

        @Override
        public int timeoutSeconds() {
            return 90;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            deployer = o.offset(0, 0, 6);
            level.setBlock(deployer, facing(block("create:deployer").defaultBlockState(), Direction.DOWN), 3);
            TestRig.resetPlayer(level, player, o, new ItemStack(Items.IRON_INGOT, 1), new ItemStack(Items.DIRT, 8));
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            alto.baritone().getCommandManager().execute("filter minecraft:iron_ingot " + deployer.getX() + " " + deployer.getY() + " " + deployer.getZ());
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            if (ticks < 5 || alto.process().busy()) {
                return Verdict.RUNNING;
            }
            String nbt = onServer(sl -> {
                BlockEntity be = sl.getBlockEntity(deployer);
                return be == null ? "no block entity" : be.saveWithoutMetadata(sl.registryAccess()).getCompound("Filter").toString();
            }, "server unavailable");
            int ingots = McItems.count(M2Scenarios.inv(), Items.IRON_INGOT);
            String s = "deployer filter " + nbt + ", ingots in inventory " + ingots;
            return nbt.contains("minecraft:iron_ingot") && ingots == 1 ? Verdict.pass(s) : Verdict.fail(s);
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
        }
    }

    // ------------------------------------------------------------------------------------------ Sophisticated Storage

    static final class SophisticatedFetch extends Scenario {
        private BlockPos chest;
        private int phase;

        @Override
        public String name() {
            return "sophisticated-fetch";
        }

        @Override
        public String description() {
            return "#fetch oak_log 8 from a Sophisticated Storage chest in a zone, holding a tier upgrade (must not be applied)";
        }

        @Override
        public int timeoutSeconds() {
            return 120;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            chest = o.offset(-3, 0, 5);
            level.setBlock(chest, block("sophisticatedstorage:chest").defaultBlockState(), 3);
            IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, chest, null);
            if (h != null) {
                ItemHandlerHelper.insertItem(h, new ItemStack(Items.OAK_LOG, 16), false);
            }
            TestRig.resetPlayer(level, player, o, new ItemStack(item("sophisticatedstorage:basic_to_copper_tier_upgrade"), 1));
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            Minecraft.getInstance().player.getInventory().selected = 0; // holding the tier upgrade
            M2Scenarios.zone(alto, o);
            alto.startJob(Job.of(new ZoneScanTask(alto, alto.storage().zones().zone("alto_selftest").orElseThrow()), "selftest"));
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            if (ticks < 5 || alto.process().busy()) {
                return Verdict.RUNNING;
            }
            if (phase == 0) {
                phase = 1;
                alto.baritone().getCommandManager().execute("fetch minecraft:oak_log 8");
                return Verdict.RUNNING;
            }
            String blockId = onServer(sl -> BuiltInRegistries.BLOCK.getKey(sl.getBlockState(chest).getBlock()).toString(), "?");
            int logs = McItems.count(M2Scenarios.inv(), Items.OAK_LOG);
            int upgrades = McItems.count(M2Scenarios.inv(), item("sophisticatedstorage:basic_to_copper_tier_upgrade"));
            String s = "logs " + logs + ", chest block " + blockId + ", tier upgrade still carried " + upgrades;
            return logs == 8 && blockId.equals("sophisticatedstorage:chest") && upgrades == 1 ? Verdict.pass(s) : Verdict.fail(s);
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
            M2Scenarios.removeZone(alto);
        }
    }
}
