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

import baritone.Baritone;
import baritone.api.Settings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;

/**
 * Test fixtures for self-test scenarios (server thread for world/player changes, client thread for settings).
 * The test area is the box x -6..6, y 0..5, z -3..12 around the player's feet, on a stone floor, walled in
 * (works on flat and on real terrain).
 */
public final class TestRig {

    public static final int X0 = -6;
    public static final int X1 = 6;
    public static final int Z0 = -3;
    public static final int Z1 = 12;
    public static final int Y1 = 5;

    private final Map<Settings.Setting<?>, Object> saved = new HashMap<>();

    /** Height cleared above the floor, so hills, trees and overhangs of real terrain are out of the way. */
    public static final int CLEAR_TOP = 24;

    /**
     * Clears the test area to air on a stone floor, removes dropped items, animals and vehicles. Server thread.
     * Works on real terrain too: the floor is sealed below (caves), the area is walled in up to {@link #CLEAR_TOP}
     * (water, lava and animals stay out), and loose or liquid blocks right above it are capped with stone.
     */
    public static void clearArea(ServerLevel level, BlockPos o) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = X0 - 1; x <= X1 + 1; x++) {
            for (int z = Z0 - 1; z <= Z1 + 1; z++) {
                boolean wall = x == X0 - 1 || x == X1 + 1 || z == Z0 - 1 || z == Z1 + 1;
                level.setBlock(o.offset(x, -3, z), stone, Block.UPDATE_CLIENTS);
                level.setBlock(o.offset(x, -2, z), stone, Block.UPDATE_CLIENTS);
                level.setBlock(o.offset(x, -1, z), wall ? stone : Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 0; y <= CLEAR_TOP; y++) {
                    level.setBlock(o.offset(x, y, z), wall ? stone : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
                BlockPos above = o.offset(x, CLEAR_TOP + 1, z);
                BlockState top = level.getBlockState(above);
                if (!top.isAir() && (!top.getFluidState().isEmpty() || top.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)) {
                    level.setBlock(above, stone, Block.UPDATE_CLIENTS);
                }
            }
        }
        AABB box = new AABB(o.offset(X0 - 2, -3, Z0 - 2).getCenter(), o.offset(X1 + 2, CLEAR_TOP + 2, Z1 + 2).getCenter());
        level.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof net.minecraft.world.entity.player.Player)).forEach(Entity::discard);
    }

    /** Survival, full health and food, empty inventory plus the given items, standing at the origin facing +Z. */
    public static void resetPlayer(ServerLevel level, ServerPlayer p, BlockPos o, ItemStack... items) {
        p.setGameMode(GameType.SURVIVAL);
        p.getInventory().clearContent();
        for (ItemStack s : items) {
            p.getInventory().add(s.copy());
        }
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.teleportTo(level, o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0f, 10f);
        p.containerMenu.broadcastChanges();
        p.inventoryMenu.broadcastChanges();
    }

    public static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    /** Changes a setting for this scenario; {@link #restore()} puts the old value back. Client thread. */
    public <T> void setting(Settings.Setting<T> setting, T value) {
        saved.putIfAbsent(setting, setting.value);
        setting.value = value;
    }

    @SuppressWarnings("unchecked")
    public void restore() {
        saved.forEach((s, v) -> ((Settings.Setting<Object>) s).value = v);
        saved.clear();
    }

    public static Settings settings() {
        return Baritone.settings();
    }

    // ------------------------------------------------------------------------------ for scenarios of mod integrations

    /** The client player's inventory. */
    public static net.minecraft.world.entity.player.Inventory inv() {
        return M2Scenarios.inv();
    }

    /** Runs {@code f} on the integrated server thread, in the client's current dimension. */
    public static <T> T onServer(java.util.function.Function<ServerLevel, T> f, T fallback) {
        return M3Scenarios.onServer(f, fallback);
    }

    /** A storage zone around the test area (removed again by {@link #removeZone}). */
    public static void zone(baritone.alto.Alto alto, BlockPos o) {
        M2Scenarios.zone(alto, o);
    }

    public static void removeZone(baritone.alto.Alto alto) {
        M2Scenarios.removeZone(alto);
    }

    public static Block block(String id) {
        return M3Scenarios.block(id);
    }

    public static net.minecraft.world.item.Item item(String id) {
        return M3Scenarios.item(id);
    }

    public static BlockState facing(BlockState s, net.minecraft.core.Direction d) {
        return M3Scenarios.facing(s, d);
    }
}
