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
 * The test area is the box x -6..6, y 0..5, z -3..12 around the player's feet, on a stone floor.
 */
public final class TestRig {

    public static final int X0 = -6;
    public static final int X1 = 6;
    public static final int Z0 = -3;
    public static final int Z1 = 12;
    public static final int Y1 = 5;

    private final Map<Settings.Setting<?>, Object> saved = new HashMap<>();

    /** Clears the test area to air on a stone floor, removes dropped items. Server thread. */
    public static void clearArea(ServerLevel level, BlockPos o) {
        for (int x = X0; x <= X1; x++) {
            for (int z = Z0; z <= Z1; z++) {
                level.setBlock(o.offset(x, -1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 0; y <= Y1; y++) {
                    level.setBlock(o.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        AABB box = new AABB(o.offset(X0 - 2, -2, Z0 - 2).getCenter(), o.offset(X1 + 2, Y1 + 2, Z1 + 2).getCenter());
        level.getEntitiesOfClass(ItemEntity.class, box).forEach(Entity::discard);
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
}
