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
import baritone.alto.storage.ZoneScanTask;
import baritone.alto.task.Job;
import baritone.alto.travel.PortalBook;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;

/**
 * Milestone 4 scenarios: portal travel there and back, and #farm restocking seeds from storage.
 */
final class M4Scenarios {

    private M4Scenarios() {
    }

    static void register(SelfTests tests) {
        tests.register("portal-travel", PortalTravel::new);
        tests.register("farm-seeds", FarmSeeds::new);
        tests.register("minecart", MinecartRide::new);
        tests.register("boat", BoatTrip::new);
        tests.register("supply-build", SupplyBuild::new);
        tests.register("portal-build", PortalBuild::new);
    }

    // ------------------------------------------------------------------------------------------ portal travel

    static final class PortalTravel extends Scenario {
        private int phase;
        private int waitTicks;
        private BlockPos origin;

        @Override
        public String name() {
            return "portal-travel";
        }

        @Override
        public String description() {
            return "#goto into the nether through a portal Alto only saw, then back to the test area through the learned link";
        }

        @Override
        public int timeoutSeconds() {
            return 180;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            // 4x5 frame along X at z+10: portal blocks x 0..1, y 1..3
            // Frame first, then the portal blocks, without shape updates (a half-built frame would break them).
            for (int pass = 0; pass < 2; pass++) {
                for (int x = -1; x <= 2; x++) {
                    for (int y = 0; y <= 4; y++) {
                        boolean frame = x == -1 || x == 2 || y == 0 || y == 4;
                        if (frame == (pass == 0)) {
                            level.setBlock(o.offset(x, y, 10), frame ? Blocks.OBSIDIAN.defaultBlockState()
                                    : Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X),
                                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        }
                    }
                }
            }
            TestRig.resetPlayer(level, player, o, new ItemStack(Items.DIRT, 32));
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            origin = o;
            alto.portals().book().forget(new PortalBook.Pos(PortalBook.OVERWORLD, o.getX(), o.getY() + 1, o.getZ() + 10));
            phase = 0;
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            Level level = Minecraft.getInstance().level;
            String dim = level.dimension().location().toString();
            switch (phase) {
                case 0: // wait until the portal scan (every 5 s) has seen the portal
                    if (alto.portals().book().best(PortalBook.OVERWORLD, o.getX(), o.getY(), o.getZ(), PortalBook.NETHER, o.getX() / 8, o.getZ() / 8).isEmpty()) {
                        return ticks > 20 * 15 ? Verdict.fail("the portal was not noticed within 15 s") : Verdict.RUNNING;
                    }
                    alto.baritone().getCommandManager().execute("goto " + Math.floorDiv(o.getX(), 8) + " " + Math.floorDiv(o.getZ() + 10, 8) + " nether");
                    phase = 1;
                    return Verdict.RUNNING;
                case 1:
                    if (alto.process().busy() || ticks < 40) {
                        return Verdict.RUNNING;
                    }
                    if (!dim.equals(PortalBook.NETHER)) {
                        return Verdict.fail("phase 1 ended in " + dim + ": " + alto.process().lastResult());
                    }
                    waitTicks = 0;
                    phase = 2;
                    return Verdict.RUNNING;
                case 2: // let the link be recorded, then go back
                    if (++waitTicks < 40) {
                        return Verdict.RUNNING;
                    }
                    alto.baritone().getCommandManager().execute("goto " + o.getX() + " " + o.getY() + " " + (o.getZ() + 4) + " overworld");
                    phase = 3;
                    return Verdict.RUNNING;
                default:
                    if (alto.process().busy() || waitTicks++ < 80) {
                        return Verdict.RUNNING;
                    }
                    int links = alto.portals().book().links().size();
                    double d = Math.sqrt(Minecraft.getInstance().player.blockPosition().distSqr(o.offset(0, 0, 4)));
                    String s = "back in " + dim + ", " + Math.round(d) + " blocks from the target, portal links known " + links
                            + "; " + alto.process().lastResult();
                    return dim.equals(PortalBook.OVERWORLD) && d < 2 && links >= 2 ? Verdict.pass(s) : Verdict.fail(s);
            }
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
            MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null && origin != null) {
                server.execute(() -> {
                    ServerPlayer sp = server.getPlayerList().getPlayer(Minecraft.getInstance().player.getUUID());
                    if (sp != null && sp.level().dimension() != Level.OVERWORLD) {
                        sp.teleportTo(server.overworld(), origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 0f, 10f);
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------------------------------------ farm seeds

    static final class FarmSeeds extends Scenario {
        private final TestRig rig = new TestRig();
        private int phase;
        private BlockPos center;

        @Override
        public String name() {
            return "farm-seeds";
        }

        @Override
        public String description() {
            return "#farm on 8 empty farmland with no seeds carried: Alto fetches wheat seeds from a chest in the zone and plants all 8";
        }

        @Override
        public int timeoutSeconds() {
            return 120;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            center = o.offset(0, -1, 6);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    level.setBlock(center.offset(x, 0, z), x == 0 && z == 0 ? Blocks.WATER.defaultBlockState()
                            : Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            M2Scenarios.chest(level, o.offset(-4, 0, 1), new ItemStack(Items.WHEAT_SEEDS, 16));
            TestRig.resetPlayer(level, player, o, new ItemStack(Items.DIRT, 8));
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            rig.setting(TestRig.settings().replantCrops, true);
            rig.setting(TestRig.settings().altoFarmRestockSeeds, true);
            M2Scenarios.zone(alto, o);
            alto.startJob(Job.of(new ZoneScanTask(alto, alto.storage().zones().zone("alto_selftest").orElseThrow()), "selftest"));
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            if (phase == 0) {
                if (ticks < 5 || alto.process().busy()) {
                    return Verdict.RUNNING;
                }
                alto.selfTests().allowUpkeep(true);
                alto.baritone().getCommandManager().execute("farm 12");
                phase = 1;
                return Verdict.RUNNING;
            }
            Level level = Minecraft.getInstance().level;
            int wheat = 0;
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (level.getBlockState(center.offset(x, 1, z)).is(Blocks.WHEAT)) {
                        wheat++;
                    }
                }
            }
            if (wheat < 8) {
                return Verdict.RUNNING;
            }
            return Verdict.pass("planted " + wheat + "/8 after fetching seeds; seeds left in the chest "
                    + M2Scenarios.serverCount(o.offset(-4, 0, 1), Items.WHEAT_SEEDS));
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
            M2Scenarios.removeZone(alto);
            rig.restore();
        }
    }

    // ------------------------------------------------------------------------------------------ minecart

    static final class MinecartRide extends Scenario {
        private BlockPos start;
        private boolean boarded;

        @Override
        public String name() {
            return "minecart";
        }

        @Override
        public String description() {
            return "#minecart: board a cart on a powered line, ride to the end, get off when it stops";
        }

        @Override
        public int timeoutSeconds() {
            return 90;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            start = o.offset(3, 0, 0);
            level.setBlock(start.offset(0, 0, -1), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL); // pushes the cart away
            for (int z = 0; z <= 11; z++) {
                level.setBlock(start.offset(0, -1, z), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(start.offset(0, 0, z), Blocks.POWERED_RAIL.defaultBlockState(), Block.UPDATE_ALL);
            }
            var cart = net.minecraft.world.entity.EntityType.MINECART.create(level);
            cart.moveTo(start.getX() + 0.5, start.getY() + 0.1, start.getZ() + 0.5);
            cart.setDeltaMovement(0, 0, 0);
            level.addFreshEntity(cart);
            TestRig.resetPlayer(level, player, o);
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            alto.baritone().getCommandManager().execute("minecart");
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            var p = Minecraft.getInstance().player;
            boarded |= p.getVehicle() != null;
            if (ticks < 5 || alto.process().busy()) {
                return Verdict.RUNNING;
            }
            double moved = p.getZ() - start.getZ();
            String s = "boarded " + boarded + ", riding now " + (p.getVehicle() != null) + ", carried " + Math.round(moved)
                    + " blocks; " + alto.process().lastResult();
            return boarded && p.getVehicle() == null && moved > 6 ? Verdict.pass(s) : Verdict.fail(s);
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
            MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
            server.execute(() -> server.overworld().getEntities(net.minecraft.world.entity.EntityType.MINECART, e -> true)
                    .forEach(e -> e.discard()));
        }
    }

    // ------------------------------------------------------------------------------------------ boat

    static final class BoatTrip extends Scenario {
        private BlockPos target;
        private boolean boarded;

        @Override
        public String name() {
            return "boat";
        }

        @Override
        public String description() {
            return "#boat across a pool: place the boat, steer, get off at the shore, pick the boat up, walk to the target";
        }

        @Override
        public int timeoutSeconds() {
            return 120;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            for (int x = -2; x <= 2; x++) {
                for (int z = 2; z <= 10; z++) {
                    level.setBlock(o.offset(x, -1, z), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            target = o.offset(0, 0, 12);
            TestRig.resetPlayer(level, player, o, new ItemStack(Items.OAK_BOAT, 1));
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            Minecraft.getInstance().player.getInventory().selected = 0;
            alto.baritone().getCommandManager().execute("boat " + target.getX() + " " + target.getZ());
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            var p = Minecraft.getInstance().player;
            boarded |= p.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat;
            if (ticks < 5 || alto.process().busy()) {
                return Verdict.RUNNING;
            }
            int boats = baritone.alto.mc.McItems.count(p.getInventory(), Items.OAK_BOAT);
            int left = Minecraft.getInstance().level.getEntitiesOfClass(net.minecraft.world.entity.vehicle.Boat.class,
                    p.getBoundingBox().inflate(32)).size();
            boolean there = p.blockPosition().getX() == target.getX() && p.blockPosition().getZ() == target.getZ();
            String s = "boarded " + boarded + ", at target " + there + ", boat items " + boats + ", boats left in the world " + left
                    + "; " + alto.process().lastResult();
            return boarded && there && boats == 1 && left == 0 ? Verdict.pass(s) : Verdict.fail(s);
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
        }
    }

    // ------------------------------------------------------------------------------------------ supply run

    static final class SupplyBuild extends Scenario {
        private final TestRig rig = new TestRig();
        private int phase;
        private BlockPos origin;

        @Override
        public String name() {
            return "supply-build";
        }

        @Override
        public String description() {
            return "#build a 3x2x3 .nbt schematic with an empty inventory: shopping list, supply run from a zone chest, build";
        }

        @Override
        public int timeoutSeconds() {
            return 150;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            M2Scenarios.chest(level, o.offset(-4, 0, 1), new ItemStack(Items.STONE_BRICKS, 20), new ItemStack(Items.GLASS, 20));
            TestRig.resetPlayer(level, player, o);
            origin = o.offset(-1, 0, 5);
        }

        /** A vanilla structure file: bottom layer stone bricks, top layer glass. */
        static void writeSchematic(java.nio.file.Path file) throws java.io.IOException {
            net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
            net.minecraft.nbt.ListTag size = new net.minecraft.nbt.ListTag();
            for (int v : new int[]{3, 2, 3}) {
                size.add(net.minecraft.nbt.IntTag.valueOf(v));
            }
            root.put("size", size);
            net.minecraft.nbt.ListTag palette = new net.minecraft.nbt.ListTag();
            for (String n : new String[]{"minecraft:stone_bricks", "minecraft:glass"}) {
                net.minecraft.nbt.CompoundTag e = new net.minecraft.nbt.CompoundTag();
                e.putString("Name", n);
                palette.add(e);
            }
            root.put("palette", palette);
            net.minecraft.nbt.ListTag blocks = new net.minecraft.nbt.ListTag();
            for (int x = 0; x < 3; x++) {
                for (int y = 0; y < 2; y++) {
                    for (int z = 0; z < 3; z++) {
                        net.minecraft.nbt.CompoundTag b = new net.minecraft.nbt.CompoundTag();
                        net.minecraft.nbt.ListTag pos = new net.minecraft.nbt.ListTag();
                        pos.add(net.minecraft.nbt.IntTag.valueOf(x));
                        pos.add(net.minecraft.nbt.IntTag.valueOf(y));
                        pos.add(net.minecraft.nbt.IntTag.valueOf(z));
                        b.put("pos", pos);
                        b.putInt("state", y);
                        blocks.add(b);
                    }
                }
            }
            root.put("blocks", blocks);
            root.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            java.nio.file.Files.createDirectories(file.getParent());
            net.minecraft.nbt.NbtIo.writeCompressed(root, file);
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            rig.setting(TestRig.settings().altoSupplyRuns, true);
            try {
                writeSchematic(Minecraft.getInstance().gameDirectory.toPath().resolve("schematics").resolve("alto_supply_test.nbt"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("could not write the test schematic", e);
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
                alto.baritone().getCommandManager().execute("build alto_supply_test.nbt " + origin.getX() + " " + origin.getY() + " " + origin.getZ());
                phase = 1;
                return Verdict.RUNNING;
            }
            Level level = Minecraft.getInstance().level;
            int bricks = M1Scenarios.countIn(level, origin, origin.offset(2, 0, 2), Blocks.STONE_BRICKS);
            int glass = M1Scenarios.countIn(level, origin.offset(0, 1, 0), origin.offset(2, 1, 2), Blocks.GLASS);
            if (alto.baritone().getBuilderProcess().isActive() || alto.process().busy() || ticks < 40) {
                return Verdict.RUNNING;
            }
            var list = alto.supplyRun().last();
            String s = "stone bricks " + bricks + "/9, glass " + glass + "/9, shopping list " + (list == null ? "none"
                    : list.lines().size() + " material(s), " + list.totalMissing() + " missing");
            return bricks == 9 && glass == 9 && list != null && list.lines().size() == 2 && list.totalMissing() == 0
                    ? Verdict.pass(s) : Verdict.fail(s);
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
            M2Scenarios.removeZone(alto);
            rig.restore();
        }
    }

    // ------------------------------------------------------------------------------------------ portal building

    static final class PortalBuild extends Scenario {
        private final TestRig rig = new TestRig();
        private BlockPos origin;
        private boolean wasInNether;

        @Override
        public String name() {
            return "portal-build";
        }

        @Override
        public String description() {
            return "#goto into the nether with no portal known: build a frame (10 obsidian, 4 cobblestone corners), light it, go through";
        }

        @Override
        public int timeoutSeconds() {
            return 180;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            TestRig.resetPlayer(level, player, o, new ItemStack(Items.OBSIDIAN, 10), new ItemStack(Items.COBBLESTONE, 4),
                    new ItemStack(Items.FLINT_AND_STEEL));
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            origin = o;
            rig.setting(TestRig.settings().altoBuildPortals, true);
            for (PortalBook.Pos p : alto.portals().book().portals()) {
                alto.portals().book().forget(p); // no known portal: it has to build one
            }
            alto.baritone().getCommandManager().execute("goto " + Math.floorDiv(o.getX(), 8) + " " + Math.floorDiv(o.getZ(), 8) + " nether");
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            String dim = Minecraft.getInstance().level.dimension().location().toString();
            wasInNether |= dim.equals(PortalBook.NETHER);
            if (ticks < 40 || alto.process().busy()) {
                return Verdict.RUNNING;
            }
            int obsidian = baritone.alto.mc.McItems.count(Minecraft.getInstance().player.getInventory(), Items.OBSIDIAN);
            String s = "reached the nether " + wasInNether + ", obsidian left " + obsidian + "; " + alto.process().lastResult();
            return wasInNether && obsidian == 0 ? Verdict.pass(s) : Verdict.fail(s);
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
            rig.restore();
            MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null && origin != null) {
                server.execute(() -> {
                    ServerPlayer sp = server.getPlayerList().getPlayer(Minecraft.getInstance().player.getUUID());
                    if (sp != null && sp.level().dimension() != Level.OVERWORLD) {
                        sp.teleportTo(server.overworld(), origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 0f, 10f);
                    }
                });
            }
        }
    }
}
