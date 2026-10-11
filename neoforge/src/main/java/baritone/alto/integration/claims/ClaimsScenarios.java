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

package baritone.alto.integration.claims;

import baritone.alto.Alto;
import baritone.alto.AltoHooks;
import baritone.alto.selftest.M7Scenarios;
import baritone.alto.selftest.TestRig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import xaero.pac.common.server.api.OpenPACServerAPI;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * In-game scenario for the Open Parties and Claims integration (registered only when OPAC is installed): the test
 * area is claimed for another (offline) player, and the pathfinder must go around a stone wall instead of breaking it.
 */
final class ClaimsScenarios {

    /** The "neighbour" who owns the claim: a fixed offline-style UUID that is never a real player. */
    static final UUID NEIGHBOUR = UUID.nameUUIDFromBytes("alto-selftest-neighbour".getBytes(StandardCharsets.UTF_8));

    private ClaimsScenarios() {
    }

    static void register(Alto alto) {
        alto.selfTests().register("claims-protect", ClaimsProtect::new);
    }

    static final class ClaimsProtect extends M7Scenarios.WallDetour {

        ClaimsProtect() {
            super("claims-protect",
                    "the test area is claimed by another player (Open Parties and Claims), a 2-high stone wall with a gap, a "
                            + "diamond pickaxe in hand: #goto across goes around and breaks nothing (control run before the claim)",
                    (o, wall) -> M7Scenarios.wallRow(o, 5, 2, Blocks.STONE.defaultBlockState(), wall),
                    () -> new ItemStack(Items.DIAMOND_PICKAXE));
        }

        private static void forEachChunk(BlockPos o, java.util.function.BiConsumer<Integer, Integer> f) {
            for (int cx = (o.getX() + TestRig.X0 - 1) >> 4; cx <= (o.getX() + TestRig.X1 + 1) >> 4; cx++) {
                for (int cz = (o.getZ() + TestRig.Z0 - 1) >> 4; cz <= (o.getZ() + TestRig.Z1 + 1) >> 4; cz++) {
                    f.accept(cx, cz);
                }
            }
        }

        @Override
        protected void protect(ServerLevel level, BlockPos o) {
            var claims = OpenPACServerAPI.get(level.getServer()).getServerClaimsManager();
            forEachChunk(o, (cx, cz) -> claims.claim(level.dimension().location(), NEIGHBOUR, -1, cx, cz, false));
        }

        @Override
        protected boolean protectionVisible(Alto alto, BlockPos o) {
            BlockPos inWall = wall.keySet().iterator().next();
            return AltoHooks.isProtected(inWall.getX(), inWall.getY(), inWall.getZ());
        }

        @Override
        protected void unprotect(ServerLevel level, BlockPos o) {
            var claims = OpenPACServerAPI.get(level.getServer()).getServerClaimsManager();
            forEachChunk(o, (cx, cz) -> claims.unclaim(level.dimension().location(), cx, cz));
        }

        @Override
        protected void protectionSetting(TestRig rig, boolean on) {
            // the control run happens before the claim exists; altoClaims stays on
        }
    }
}
