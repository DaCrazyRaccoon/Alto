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
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Compatibility scenarios: paths never take machines apart (altoProtectMachines). The same "wall with a gap"
 * fixture is used by the mod scenarios (Create shafts and train tracks, Open Parties and Claims).
 */
public final class M7Scenarios {

    private M7Scenarios() {
    }

    static void register(SelfTests tests) {
        tests.register("machines-safe", () -> new WallDetour("machines-safe",
                "a 2-high wall of barrels with a gap at one end, a diamond axe in hand: #goto across goes through the gap "
                        + "and breaks no barrel (control run with altoProtectMachines off first)",
                (o, wall) -> wallRow(o, 5, 2, Blocks.BARREL.defaultBlockState(), wall),
                () -> new ItemStack(Items.DIAMOND_AXE)));
    }

    /** One wall row at {@code z}, x -6..3, {@code height} blocks high: the gap is x 4..6. */
    public static void wallRow(BlockPos o, int z, int height, BlockState state, Map<BlockPos, BlockState> out) {
        for (int x = TestRig.X0; x <= 3; x++) {
            for (int y = 0; y < height; y++) {
                out.put(o.offset(x, y, z), state);
            }
        }
    }

    /**
     * {@code #goto} from the origin to (0, 0, 10) across walls that have a 3-wide gap at x 4..6. With the tool given,
     * breaking through is cheaper than the detour, so a pass shows that the pathfinder was not allowed to break.
     * First a control run without the protection (its result is only reported), then the run that must keep every
     * wall block. Subclasses (mod scenarios) add their own protection in {@link #protect}.
     */
    public static class WallDetour extends Scenario {
        private final TestRig rig = new TestRig();
        private final String name;
        private final String description;
        private final BiConsumer<BlockPos, Map<BlockPos, BlockState>> layout;
        private final Supplier<ItemStack> tool;
        protected final Map<BlockPos, BlockState> wall = new LinkedHashMap<>();
        private BlockPos goal;
        private BlockPos origin;
        private int phase;
        private int mark;
        private String controlResult = "";

        public WallDetour(String name, String description, BiConsumer<BlockPos, Map<BlockPos, BlockState>> layout, Supplier<ItemStack> tool) {
            this.name = name;
            this.description = description;
            this.layout = layout;
            this.tool = tool;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public int timeoutSeconds() {
            return 150;
        }

        @Override
        public void setup(ServerLevel level, ServerPlayer player, BlockPos o) {
            TestRig.clearArea(level, o);
            origin = o;
            wall.clear();
            layout.accept(o, wall);
            buildWall(level);
            goal = o.offset(0, 0, 10);
            TestRig.resetPlayer(level, player, o, tool.get());
        }

        private void buildWall(ServerLevel level) {
            wall.forEach((p, s) -> level.setBlock(p, s, Block.UPDATE_ALL));
        }

        /** Server thread: switch the protection under test on (after the control run). */
        protected void protect(ServerLevel level, BlockPos o) {
        }

        /** Client thread: true once the client knows about the protection (e.g. synced claims). */
        protected boolean protectionVisible(Alto alto, BlockPos o) {
            return true;
        }

        /** Server thread, at the end. */
        protected void unprotect(ServerLevel level, BlockPos o) {
        }

        /** Client thread: the setting under test, off for the control run, on for the real one. */
        protected void protectionSetting(TestRig rig, boolean on) {
            rig.setting(TestRig.settings().altoProtectMachines, on);
        }

        private int intact() {
            return TestRig.onServer(sl -> (int) wall.entrySet().stream()
                    .filter(e -> sl.getBlockState(e.getKey()).getBlock() == e.getValue().getBlock()).count(), -1);
        }

        private boolean arrived(Alto alto) {
            return !alto.baritone().getCustomGoalProcess().isActive() && Minecraft.getInstance().player.blockPosition().equals(goal);
        }

        private void go(Alto alto) {
            alto.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));
        }

        @Override
        public void start(Alto alto, BlockPos o) {
            protectionSetting(rig, false);
            go(alto);
        }

        @Override
        public Verdict check(Alto alto, BlockPos o, int ticks) {
            switch (phase) {
                case 0 -> { // control run: no protection
                    if (ticks < 600 && !arrived(alto)) {
                        return Verdict.RUNNING;
                    }
                    alto.baritone().getPathingBehavior().cancelEverything();
                    int broken = wall.size() - intact();
                    controlResult = broken > 0 ? "without the protection it broke " + broken + " block(s)"
                            : "without the protection it also went around";
                    // rebuild, put the player back, switch the protection on
                    var server = Minecraft.getInstance().getSingleplayerServer();
                    server.execute(() -> {
                        ServerLevel sl = server.overworld();
                        buildWall(sl);
                        sl.getEntitiesOfClass(ItemEntity.class, new AABB(o).inflate(16)).forEach(net.minecraft.world.entity.Entity::discard);
                        ServerPlayer sp = server.getPlayerList().getPlayer(Minecraft.getInstance().player.getUUID());
                        TestRig.resetPlayer(sl, sp, o, tool.get());
                        protect(sl, o);
                    });
                    protectionSetting(rig, true);
                    phase = 1;
                    mark = ticks;
                    return Verdict.RUNNING;
                }
                case 1 -> {
                    if (ticks - mark < 20) {
                        return Verdict.RUNNING;
                    }
                    if (intact() != wall.size()) {
                        return Verdict.fail("the wall could not be rebuilt for the protected run");
                    }
                    if (!protectionVisible(alto, o)) {
                        return ticks - mark > 200 ? Verdict.fail("the protection never reached the client") : Verdict.RUNNING;
                    }
                    go(alto);
                    phase = 2;
                    mark = ticks;
                    return Verdict.RUNNING;
                }
                default -> {
                    int left = intact();
                    if (left < wall.size()) {
                        alto.baritone().getPathingBehavior().cancelEverything();
                        return Verdict.fail("broke " + (wall.size() - left) + " protected block(s); " + controlResult);
                    }
                    if (arrived(alto)) {
                        return Verdict.pass("went around, " + left + "/" + wall.size() + " blocks intact; " + controlResult);
                    }
                    return ticks - mark > 1200 ? Verdict.fail("did not reach the goal in 60 s; " + controlResult) : Verdict.RUNNING;
                }
            }
        }

        @Override
        public void cleanup(Alto alto) {
            alto.baritone().getPathingBehavior().cancelEverything();
            rig.restore();
            var server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null && origin != null) {
                BlockPos o = origin;
                server.execute(() -> unprotect(server.overworld(), o));
            }
        }
    }
}
