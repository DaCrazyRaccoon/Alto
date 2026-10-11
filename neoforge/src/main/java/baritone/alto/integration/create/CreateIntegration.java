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

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.integration.AltoIntegration;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Create 6.0.10 integration (feature G). Only loaded when Create is installed (by class name, see Integrations).
 * Everything here reads what Create already shows the client: block entity speed (synced), fan air currents,
 * filter slots and their positions. Nothing is sent that Create's own client would not send.
 */
public final class CreateIntegration implements AltoIntegration {

    @Override
    public String name() {
        return "Create";
    }

    @Override
    public boolean enabled() {
        return Baritone.settings().altoCreate.value;
    }

    @Override
    public void init(Alto alto) {
        // Hazards: "rotating" means the kinetic block entity reports a speed (0 when stopped or overstressed).
        alto.hazards().setRotatingCondition((level, be) -> be instanceof KineticBlockEntity k && k.getSpeed() != 0);
        alto.hazards().setAirCurrent(CreateIntegration::airCurrentPoints);
        alto.addFilterAccess(new CreateFilterAccess());
        // Material lists: Create knows what its blocks cost (belts, shafts, copycats...) and handles vanilla specials.
        alto.supplyRun().addRequirementSource(CreateIntegration::requirement);
    }

    static baritone.alto.core.ItemCounts requirement(net.minecraft.world.level.block.state.BlockState state) {
        var r = com.simibubi.create.content.schematics.requirement.ItemRequirement.of(state, null);
        if (r.isInvalid()) {
            return null; // not known to Create: use Alto's own rule
        }
        baritone.alto.core.ItemCounts out = new baritone.alto.core.ItemCounts();
        for (var sr : r.getRequiredItems()) {
            if (sr.usage == com.simibubi.create.content.schematics.requirement.ItemRequirement.ItemUseType.CONSUME && !sr.stack.isEmpty()) {
                out.add(baritone.alto.mc.McItems.key(sr.stack.getItem()), sr.stack.getCount());
            }
        }
        return out;
    }

    /** Points along a running fan's air current (every 2 blocks), so paths avoid being pushed or burned. */
    static List<BlockPos> airCurrentPoints(BlockEntity be) {
        if (!(be instanceof EncasedFanBlockEntity fan) || fan.getSpeed() == 0) {
            return List.of();
        }
        AirCurrent current = fan.airCurrent;
        if (current == null || current.bounds == null || current.direction == null) {
            return List.of();
        }
        AABB box = current.bounds;
        Vec3 dir = Vec3.atLowerCornerOf(current.direction.getNormal());
        Vec3 start = Vec3.atCenterOf(fan.getBlockPos()).add(dir);
        int length = (int) Math.ceil(Math.min(32, Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize()))));
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < length; i += 2) {
            out.add(BlockPos.containing(start.add(dir.scale(i))));
        }
        return out;
    }
}
