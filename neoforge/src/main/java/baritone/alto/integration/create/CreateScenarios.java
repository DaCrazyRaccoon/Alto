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

package baritone.alto.integration.create;

import baritone.alto.Alto;
import baritone.alto.core.ItemCounts;
import baritone.alto.mc.McItems;
import baritone.alto.selftest.M7Scenarios;
import baritone.alto.selftest.Scenario;
import baritone.alto.selftest.SelfTests;
import baritone.alto.selftest.TestRig;
import baritone.alto.supply.ShoppingList;
import baritone.api.pathing.goals.GoalBlock;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.content.contraptions.elevator.ElevatorContactBlockEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlock;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlock;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;

/**
 * In-game scenarios for the Create integration, registered only when Create is installed. The setups build real
 * Create machines on the integrated server (like a creative player would); what is tested always runs through
 * Alto's normal client paths.
 */
final class CreateScenarios {

    private CreateScenarios() {
    }

    static void register(Alto alto) {
        SelfTests t = alto.selfTests();
        t.register("create-materials", Materials::new);
        t.register("create-protect", () -> new M7Scenarios.WallDetour("create-protect",
                "a 2-high wall of encased shafts and a row of train track, each with a gap at one end, a diamond pickaxe in "
                        + "hand: #goto across goes around and breaks neither (control run with altoProtectMachines off first)",
                (o, wall) -> {
                    M7Scenarios.wallRow(o, 4, 2, AllBlocks.ANDESITE_ENCASED_SHAFT.getDefaultState()
                            .setValue(BlockStateProperties.AXIS, Direction.Axis.X), wall);
                    M7Scenarios.wallRow(o, 7, 1, AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.XO), wall);
                },
                () -> new ItemStack(Items.DIAMOND_PICKAXE)));
        t.register("create-hazard", Hazard::new);
        t.register("create-platform", Platform::new);
        t.register("create-elevator", Elevator::new);
        t.register("create-order", Order::new);
    }

    static ServerPlayer serverPlayer(ServerLevel level) {
        return level.getServer().getPlayerList().getPlayer(Minecraft.getInstance().player.getUUID());
    }

    static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    static BlockState motor(Direction facing) {
        return AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, facing);
    }

    static boolean arrived(Alto alto, BlockPos goal) {
        return !alto.baritone().getCustomGoalProcess().isActive() && Minecraft.getInstance().player.blockPosition().equals(goal);
    }

    // ------------------------------------------------------------------------------------------ material lists

    static final class Materials extends Scenario {
        @Override
        public String name() {
            return "create-materials";
        }

        @Override
        public String description() {
            return "material lists use Create's own block costs: farmland needs dirt, an encased shaft needs a shaft";
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.resetPlayer(level, player, o);
        }

        @Override
        public void start(Alto alto, BlockPos o) {
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            BlockState farmland = Blocks.FARMLAND.defaultBlockState();
            BlockState encased = AllBlocks.ANDESITE_ENCASED_SHAFT.getDefaultState();
            ItemCounts f = alto.supplyRun().requirements(farmland);
            ItemCounts e = alto.supplyRun().requirements(encased);
            ShoppingList list = alto.supplyRun().listFor(Map.of(farmland, 3, encased, 2));
            int dirt = 0;
            int shafts = 0;
            for (ShoppingList.Line l : list.lines()) {
                if (l.item().equals(McItems.key(Items.DIRT))) {
                    dirt = l.needed();
                } else if (l.item().equals(McItems.key(AllBlocks.SHAFT.asItem()))) {
                    shafts = l.needed();
                }
            }
            String s = "farmland -> " + f + ", encased shaft -> " + e + ", list for 3 farmland + 2 encased shafts: dirt " + dirt + ", shaft " + shafts;
            boolean ok = f.get(McItems.key(Items.DIRT)) == 1 && e.get(McItems.key(AllBlocks.SHAFT.asItem())) == 1 && dirt == 3 && shafts == 2;
            return ok ? Verdict.pass(s) : Verdict.fail(s);
        }
    }

    // ------------------------------------------------------------------------------------------ hazards

    static final class Hazard extends Scenario {
        private BlockPos drill;
        private BlockPos goal;
        private double closest = Double.MAX_VALUE;
        private boolean rotating;

        @Override
        public String name() {
            return "create-hazard";
        }

        @Override
        public String description() {
            return "a running mechanical drill (creative motor) stands in the straight line to the goal: #goto keeps 2 blocks away and takes no damage";
        }

        @Override
        public int timeoutSeconds() {
            return 60;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            drill = o.offset(0, 0, 5);
            set(level, drill, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
            set(level, drill.west(), motor(Direction.EAST));
            goal = o.offset(0, 0, 10);
            TestRig.resetPlayer(level, player, o);
        }

        @Override
        public void start(Alto alto, BlockPos o) {
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            LocalPlayer p = Minecraft.getInstance().player;
            if (!rotating) {
                // wait until the client sees the drill turning (the hazard rule only applies to running machines)
                rotating = p.level().getBlockEntity(drill) instanceof KineticBlockEntity k && k.getSpeed() != 0;
                if (rotating) {
                    alto.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));
                } else if (ticks > 200) {
                    return Verdict.fail("the drill never started turning");
                }
                return Verdict.RUNNING;
            }
            double dx = p.getX() - (drill.getX() + 0.5);
            double dz = p.getZ() - (drill.getZ() + 0.5);
            closest = Math.min(closest, Math.sqrt(dx * dx + dz * dz));
            if (p.getHealth() < p.getMaxHealth()) {
                alto.baritone().getPathingBehavior().cancelEverything();
                return Verdict.fail("took damage near the drill (closest " + String.format("%.2f", closest) + ")");
            }
            if (!arrived(alto, goal)) {
                return Verdict.RUNNING;
            }
            String s = "closest distance to the running drill " + String.format("%.2f", closest) + " blocks, no damage";
            return closest >= 1.8 ? Verdict.pass(s) : Verdict.fail(s);
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
        }
    }

    // ------------------------------------------------------------------------------------------ moving platform

    static final class Platform extends Scenario {
        private BlockPos platform;
        private BlockPos piston;
        private BlockPos goal;
        private int phase;
        private int mark;
        private int movingTicks;
        private int pausedTicks;
        private double maxDrift;
        private Vec3 localStart;
        private AbstractContraptionEntity carrier;
        private boolean flipped;

        @Override
        public String name() {
            return "create-platform";
        }

        @Override
        public String description() {
            return "standing on a Create piston platform when it starts moving: #goto waits while the floor moves "
                    + "(no walking off), then walks to the goal from where the platform stopped";
        }

        @Override
        public int timeoutSeconds() {
            return 90;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            // In the floor, along +X: 3 poles, the piston (shaft axis Z), the platform block, a 3-block trench.
            piston = o.offset(-3, -1, 4);
            platform = o.offset(-2, -1, 4);
            for (int x = -6; x <= -4; x++) {
                set(level, o.offset(x, -1, 4), AllBlocks.PISTON_EXTENSION_POLE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
            }
            set(level, piston, AllBlocks.MECHANICAL_PISTON.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST)
                    .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, false)
                    .setValue(MechanicalPistonBlock.STATE, MechanicalPistonBlock.PistonState.RETRACTED));
            set(level, platform, AllBlocks.ANDESITE_CASING.getDefaultState());
            for (int x = -1; x <= 1; x++) {
                set(level, o.offset(x, -1, 4), Blocks.AIR.defaultBlockState());
            }
            goal = o.offset(4, 0, 9);
            TestRig.resetPlayer(level, player, o);
            player.teleportTo(level, platform.getX() + 0.5, o.getY(), platform.getZ() + 0.5, 0f, 10f);
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            // Power the piston: a creative motor on the south side (negative speed along Z makes it extend east).
            TestRig.onServer(sl -> {
                set(sl, piston.south(), motor(Direction.NORTH));
                return true;
            }, false);
        }

        private AbstractContraptionEntity carrierUnder(LocalPlayer p) {
            for (AbstractContraptionEntity e : CreateTravel.contraptions(p.level())) {
                Vec3 local = e.toLocalVector(p.position().add(0, -0.2, 0), 1);
                if (e.getContraption().getBlocks().containsKey(BlockPos.containing(local))) {
                    return e;
                }
            }
            return null;
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            LocalPlayer p = Minecraft.getInstance().player;
            switch (phase) {
                case 0 -> {
                    if (alto.platformPause().onMovingPlatform()) {
                        carrier = carrierUnder(p);
                        localStart = carrier == null ? null : carrier.toLocalVector(p.position(), 1);
                        alto.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));
                        phase = 1;
                        return Verdict.RUNNING;
                    }
                    if (ticks == 60 && !flipped) {
                        // the piston did not start: turn the other way (motor on the north side)
                        flipped = true;
                        TestRig.onServer(sl -> {
                            set(sl, piston.south(), Blocks.SMOOTH_STONE.defaultBlockState());
                            set(sl, piston.north(), motor(Direction.SOUTH));
                            return true;
                        }, false);
                    }
                    if (ticks > 200) {
                        String state = TestRig.onServer(sl -> sl.getBlockState(piston).toString(), "?");
                        return Verdict.fail("the platform never moved (piston " + state + ", contraptions " + CreateTravel.contraptions(p.level()).size() + ")");
                    }
                    return Verdict.RUNNING;
                }
                case 1 -> {
                    if (alto.platformPause().onMovingPlatform()) {
                        movingTicks++;
                        if (alto.baritone().getPathingControlManager().mostRecentInControl().orElse(null) == alto.platformPause()) {
                            pausedTicks++;
                        }
                        if (carrier != null && carrier.isAlive() && localStart != null) {
                            Vec3 now = carrier.toLocalVector(p.position(), 1);
                            maxDrift = Math.max(maxDrift, Math.hypot(now.x - localStart.x, now.z - localStart.z));
                        }
                        mark = ticks;
                        return Verdict.RUNNING;
                    }
                    phase = 2;
                    return Verdict.RUNNING;
                }
                default -> {
                    if (!arrived(alto, goal)) {
                        return Verdict.RUNNING;
                    }
                    BlockPos moved = o.offset(1, -1, 4);
                    boolean platformMoved = TestRig.onServer(sl -> sl.getBlockState(moved).is(AllBlocks.ANDESITE_CASING.get()), false);
                    String s = "rode " + movingTicks + " ticks, Baritone paused " + pausedTicks + " of them, drift on the platform "
                            + String.format("%.2f", maxDrift) + " blocks, platform ended at +3 x: " + platformMoved + ", then reached the goal"
                            + (flipped ? " (motor flipped)" : "");
                    return movingTicks >= 20 && pausedTicks >= 15 && maxDrift < 0.6 && platformMoved ? Verdict.pass(s) : Verdict.fail(s);
                }
            }
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
        }
    }

    // ------------------------------------------------------------------------------------------ elevator

    static final class Elevator extends Scenario {
        private BlockPos pulley;
        private int phase;
        private int pausedTicks;
        private int startY;

        @Override
        public String name() {
            return "create-elevator";
        }

        @Override
        public String description() {
            return "#elevator Upper inside a Create elevator cab (contraption controls, two floors): select the floor "
                    + "with the scroll wheel on the controls, right-click, ride up 6 blocks";
        }

        @Override
        public int timeoutSeconds() {
            return 90;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            // Cab: 3x3 floor flush with the ground, controls on the north side, a redstone contact facing the column.
            for (int x = 1; x <= 3; x++) {
                for (int z = 6; z <= 8; z++) {
                    set(level, o.offset(x, -1, z), AllBlocks.ANDESITE_CASING.getDefaultState());
                }
            }
            set(level, o.offset(2, 0, 6), AllBlocks.CONTRAPTION_CONTROLS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
            set(level, o.offset(3, 0, 7), AllBlocks.REDSTONE_CONTACT.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
            SuperGlueEntity glue = new SuperGlueEntity(level, SuperGlueEntity.span(o.offset(1, -1, 6), o.offset(3, 0, 8)));
            level.addFreshEntity(glue);
            // Floors: elevator contacts in the column east of the cab's contact.
            nameFloor(level, o.offset(4, 0, 7), "G", "Ground");
            nameFloor(level, o.offset(4, 6, 7), "U", "Upper");
            // The pulley 9 blocks up, turned by a creative motor.
            pulley = o.offset(2, 9, 7);
            set(level, pulley, AllBlocks.ELEVATOR_PULLEY.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
            set(level, pulley.north(), motor(Direction.SOUTH));
            TestRig.resetPlayer(level, player, o);
            player.teleportTo(level, o.getX() + 2.5, o.getY(), o.getZ() + 7.5, 180f, 10f);
        }

        private static void nameFloor(ServerLevel level, BlockPos pos, String shortName, String longName) {
            set(level, pos, AllBlocks.ELEVATOR_CONTACT.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST));
            if (level.getBlockEntity(pos) instanceof ElevatorContactBlockEntity c) {
                c.updateName(shortName, longName);
            }
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            // Assemble, as right-clicking the pulley with an empty hand does.
            TestRig.onServer(sl -> {
                if (sl.getBlockEntity(pulley) instanceof ElevatorPulleyBlockEntity be) {
                    be.clicked();
                }
                return true;
            }, false);
            startY = Minecraft.getInstance().player.blockPosition().getY();
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            LocalPlayer p = Minecraft.getInstance().player;
            switch (phase) {
                case 0 -> {
                    var cab = CreateTravel.nearestElevator(p.level(), p);
                    if (cab.isPresent() && ((ElevatorContraption) cab.get().getContraption()).namesList.size() >= 2) {
                        alto.baritone().getCommandManager().execute("elevator Upper");
                        phase = 1;
                        return Verdict.RUNNING;
                    }
                    if (ticks > 200) {
                        String why = TestRig.onServer(sl -> sl.getBlockEntity(pulley) instanceof ElevatorPulleyBlockEntity be
                                ? "running " + be.running + ", " + (be.getLastAssemblyException() == null ? "no assembly error" : be.getLastAssemblyException().getMessage())
                                : "no pulley", "?");
                        return Verdict.fail("no elevator cab with 2 floors (" + why + ", cab " + cab.isPresent() + ")");
                    }
                    return Verdict.RUNNING;
                }
                default -> {
                    if (alto.baritone().getPathingControlManager().mostRecentInControl().orElse(null) == alto.platformPause()) {
                        pausedTicks++;
                    }
                    if (ticks < 10 || alto.process().busy()) {
                        return Verdict.RUNNING;
                    }
                    String result = alto.process().lastResult();
                    int rise = p.blockPosition().getY() - startY;
                    String s = result + "; rose " + rise + " blocks, paused while moving " + pausedTicks + " ticks";
                    return result.startsWith("done") && rise >= 5 ? Verdict.pass(s) : Verdict.fail(s);
                }
            }
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
            TestRig.onServer(sl -> {
                if (sl.getBlockEntity(pulley) instanceof ElevatorPulleyBlockEntity be && be.running) {
                    be.clicked(); // disassemble, so the cab does not stay up there
                }
                return true;
            }, false);
        }
    }

    // ------------------------------------------------------------------------------------------ stock keeper

    static final class Order extends Scenario {
        private BlockPos chest;
        private int phase;
        private int mark;
        private String result = "";

        @Override
        public String name() {
            return "create-order";
        }

        @Override
        public String description() {
            return "#order iron_ingot 4 at a stock keeper (a seated villager next to a stock ticker; chest + packager + stock link): "
                    + "the screen the server opens is used to send the order, the packager takes 4 ingots out of the chest";
        }

        @Override
        public int timeoutSeconds() {
            return 90;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            chest = o.offset(-3, 0, 8);
            set(level, chest, Blocks.CHEST.defaultBlockState());
            if (level.getBlockEntity(chest) instanceof Container c) {
                c.setItem(0, new ItemStack(Items.IRON_INGOT, 32));
            }
            BlockPos packager = chest.above();
            set(level, packager, AllBlocks.PACKAGER.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
            BlockPos link = packager.above();
            set(level, link, AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE, AttachFace.FLOOR)
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
            BlockPos ticker = o.offset(0, 0, 8);
            set(level, ticker, AllBlocks.STOCK_TICKER.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
            // One network: the ticker and the link share a frequency (set before their first tick registers them).
            UUID network = UUID.randomUUID();
            if (level.getBlockEntity(ticker) instanceof StockTickerBlockEntity t && level.getBlockEntity(link) instanceof PackagerLinkBlockEntity l) {
                t.behaviour.freqId = network;
                l.behaviour.freqId = network;
            }
            // The keeper: a villager seated next to the ticker.
            BlockPos seat = o.offset(1, 0, 8);
            set(level, seat, AllBlocks.SEATS.get(DyeColor.WHITE).getDefaultState());
            Villager keeper = EntityType.VILLAGER.create(level);
            if (keeper != null) {
                keeper.setNoAi(true);
                keeper.moveTo(seat.getX() + 0.5, seat.getY(), seat.getZ() + 0.5, 0f, 0f);
                level.addFreshEntity(keeper);
                SeatBlock.sitDown(level, seat, keeper);
            }
            TestRig.resetPlayer(level, player, o);
        }

        @Override
        public void start(Alto alto, BlockPos o) {
        }

        private int ingotsInChest() {
            return TestRig.onServer(sl -> sl.getBlockEntity(chest) instanceof Container c ? c.countItem(Items.IRON_INGOT) : -1, -1);
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            switch (phase) {
                case 0 -> {
                    if (ticks < 60) {
                        return Verdict.RUNNING; // let the network register and the packager read the chest
                    }
                    alto.baritone().getCommandManager().execute("order minecraft:iron_ingot 4");
                    phase = 1;
                    return Verdict.RUNNING;
                }
                case 1 -> {
                    if (alto.process().busy() || ticks < 70) {
                        return Verdict.RUNNING;
                    }
                    result = alto.process().lastResult();
                    if (!result.startsWith("done")) {
                        return Verdict.fail(result + "; chest " + ingotsInChest());
                    }
                    phase = 2;
                    mark = ticks;
                    return Verdict.RUNNING;
                }
                default -> {
                    int left = ingotsInChest();
                    if (left == 28) {
                        String box = TestRig.onServer(sl -> sl.getBlockEntity(chest.above()) instanceof PackagerBlockEntity pb
                                ? pb.heldBox.isEmpty() ? "no box held" : "packager holds " + pb.heldBox.getHoverName().getString() : "?", "?");
                        return Verdict.pass(result + "; chest 32 -> 28 iron, " + box);
                    }
                    return ticks - mark > 200 ? Verdict.fail(result + "; but the chest still has " + left + " iron after 10 s") : Verdict.RUNNING;
                }
            }
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
            if (Minecraft.getInstance().screen != null) {
                Minecraft.getInstance().player.closeContainer();
            }
        }
    }
}
