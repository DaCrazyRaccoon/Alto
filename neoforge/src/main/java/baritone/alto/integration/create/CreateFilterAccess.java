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

import baritone.alto.integration.FilterAccess;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.tableCloth.TableClothFilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.SidedFilteringBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Finds Create filter slots exactly like Create's own renderer and click handler do (research/create.md §1.6):
 * {@code pos + transform.getLocalOffset(...)}, with {@code fromSide(face)} for per-face slots, hit radius
 * {@code scale / 2} ({@code / 3.5} for dual boxes). Factory gauges and table-cloth price tags are left alone on
 * purpose: they belong to Create's logistics/shop systems.
 */
final class CreateFilterAccess implements FilterAccess {

    @Override
    public List<FilterSlot> slots(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SmartBlockEntity sbe)) {
            return List.of();
        }
        FilteringBehaviour root = sbe.getBehaviour(FilteringBehaviour.TYPE);
        if (root == null || root instanceof FactoryPanelBehaviour || root instanceof TableClothFilteringBehaviour) {
            return List.of();
        }
        BlockState state = level.getBlockState(pos);
        String blockName = state.getBlock().getName().getString().toLowerCase(java.util.Locale.ROOT);
        List<FilterSlot> out = new ArrayList<>();
        Set<Direction> seen = EnumSet.noneOf(Direction.class);
        for (Direction face : Direction.values()) {
            FilteringBehaviour fb = root instanceof SidedFilteringBehaviour sided ? sided.get(face) : root;
            if (fb == null || !fb.isActive()) {
                continue;
            }
            ValueBoxTransform t = fb.getSlotPositioning();
            if (t == null) {
                continue;
            }
            Direction slotFace;
            Vec3 local;
            if (t instanceof ValueBoxTransform.Sided s) {
                s.fromSide(face);
                if (!s.shouldRender(level, pos, state)) {
                    continue;
                }
                local = s.getLocalOffset(level, pos, state);
                slotFace = face;
            } else {
                if (!t.shouldRender(level, pos, state)) {
                    continue;
                }
                local = t.getLocalOffset(level, pos, state);
                if (local == null) {
                    continue;
                }
                slotFace = nearestFace(local);
                if (slotFace != face) {
                    continue; // a single slot: report it once, on the face it sits on
                }
            }
            if (local == null || !seen.add(slotFace)) {
                continue;
            }
            double radius = t.getScale() / (t instanceof ValueBoxTransform.Dual ? 3.5 : 2.0);
            Vec3 world = Vec3.atLowerCornerOf(pos).add(local);
            out.add(new FilterSlot(slotFace, world, radius, fb.acceptsValueSettings(),
                    blockName + " filter (" + slotFace.getName() + ")"));
        }
        return out;
    }

    @Override
    public ItemStack current(Level level, BlockPos pos, Direction face) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SmartBlockEntity sbe)) {
            return ItemStack.EMPTY;
        }
        FilteringBehaviour root = sbe.getBehaviour(FilteringBehaviour.TYPE);
        if (root == null) {
            return ItemStack.EMPTY;
        }
        FilteringBehaviour fb = root instanceof SidedFilteringBehaviour sided ? sided.get(face) : root;
        return fb == null ? ItemStack.EMPTY : fb.getFilter();
    }

    /** The block face whose plane is closest to a point inside the block (0..1 local coordinates). */
    static Direction nearestFace(Vec3 local) {
        double[] d = {local.x, 1 - local.x, local.y, 1 - local.y, local.z, 1 - local.z};
        Direction[] faces = {Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};
        int best = 0;
        for (int i = 1; i < d.length; i++) {
            if (d[i] < d[best]) {
                best = i;
            }
        }
        return faces[best];
    }
}
