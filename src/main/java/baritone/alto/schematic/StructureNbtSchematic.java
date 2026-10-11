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

package baritone.alto.schematic;

import baritone.utils.schematic.StaticSchematic;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Vanilla structure files (".nbt"), the format of structure blocks and of Create's schematic table and
 * schematicannon (feature 10). Positions the file does not list ("structure void") are left as they are.
 * Block entity data (chest contents, filters...) is not placed; Baritone places block states only.
 */
public final class StructureNbtSchematic extends StaticSchematic {

    public StructureNbtSchematic(CompoundTag nbt) {
        ListTag size = nbt.getList("size", Tag.TAG_INT);
        if (size.size() != 3) {
            throw new IllegalArgumentException("not a structure file (no size)");
        }
        this.x = size.getInt(0);
        this.y = size.getInt(1);
        this.z = size.getInt(2);
        if (x <= 0 || y <= 0 || z <= 0 || (long) x * y * z > 64L * 1024 * 1024) {
            throw new IllegalArgumentException("unsupported structure size " + x + "x" + y + "x" + z);
        }
        this.states = new BlockState[x][z][y];
        ListTag paletteTag = nbt.contains("palettes", Tag.TAG_LIST)
                ? nbt.getList("palettes", Tag.TAG_LIST).getList(0) // several palettes (e.g. shipwrecks): use the first
                : nbt.getList("palette", Tag.TAG_COMPOUND);
        BlockState[] palette = new BlockState[paletteTag.size()];
        var lookup = BuiltInRegistries.BLOCK.asLookup();
        for (int i = 0; i < palette.length; i++) {
            BlockState s = NbtUtils.readBlockState(lookup, paletteTag.getCompound(i));
            // Unknown blocks (mod missing) read as air: keep them as "unknown" (not placed, not cleared).
            palette[i] = s.isAir() && !isAirName(paletteTag.getCompound(i)) ? null : s;
        }
        ListTag blocks = nbt.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompound(i);
            ListTag pos = b.getList("pos", Tag.TAG_INT);
            int bx = pos.getInt(0);
            int by = pos.getInt(1);
            int bz = pos.getInt(2);
            int idx = b.getInt("state");
            if (bx < 0 || by < 0 || bz < 0 || bx >= x || by >= y || bz >= z || idx < 0 || idx >= palette.length) {
                continue;
            }
            BlockState s = palette[idx];
            if (s != null && !s.is(Blocks.STRUCTURE_VOID)) {
                states[bx][bz][by] = s;
            }
        }
    }

    private static boolean isAirName(CompoundTag paletteEntry) {
        String name = paletteEntry.getString("Name");
        return name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air");
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState currentState) {
        return super.inSchematic(x, y, z, currentState) && states[x][z][y] != null;
    }

    @Override
    public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
        BlockState s = states[x][z][y];
        return s == null ? current : s;
    }
}
