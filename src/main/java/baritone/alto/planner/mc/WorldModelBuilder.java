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

package baritone.alto.planner.mc;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.core.ItemCounts;
import baritone.alto.mc.McItems;
import baritone.alto.planner.StationKind;
import baritone.alto.planner.WorldModel;
import baritone.api.utils.IPlayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;

import java.util.Map;
import java.util.Optional;

/**
 * Builds a planner {@link WorldModel} snapshot from the live game: inventory, carried shulker contents, nearby
 * stations, known storage (storage index), how items can be gathered, and the allowed fuels.
 */
public final class WorldModelBuilder {

    private WorldModelBuilder() {
    }

    public static WorldModel build(Alto alto, ItemCounts reserved) {
        return build(alto, reserved, java.util.Set.of());
    }

    /** @param neverMine block ids that must not be mined for materials (e.g. blocks the current build places) */
    public static WorldModel build(Alto alto, ItemCounts reserved, java.util.Set<String> neverMine) {
        IPlayerContext ctx = alto.ctx();
        Inventory inv = ctx.player().getInventory();
        WorldModel.Builder b = WorldModel.builder()
                .inventory(McItems.inventoryCounts(inv))
                .inShulkers(McItems.shulkerContents(inv))
                .reserve(reserved == null ? new ItemCounts() : reserved)
                .hasPickaxe(hasPickaxe(inv));
        if (Baritone.settings().altoStations.value && Baritone.settings().altoReuseStations.value) {
            int radius = Baritone.settings().altoStationSearchRadius.value;
            BlockPos feet = ctx.playerFeet();
            for (StationKind kind : StationKind.values()) {
                if (!kind.needsBlock()) {
                    continue;
                }
                Optional<BlockPos> pos = alto.stations().findNearest(kind, radius, p -> true);
                pos.ifPresent(p -> b.station(kind, p.toShortString(), Math.sqrt(p.distSqr(feet))));
            }
        }
        if (Baritone.settings().altoPlannerAllowGather.value) {
            GatherTable.sources().forEach((item, src) -> {
                java.util.List<String> blocks = src.blocks().stream().filter(id -> !neverMine.contains(id)).toList();
                if (!blocks.isEmpty()) {
                    b.gatherable(item.id(), src.ticksPerItem(), blocks.toArray(new String[0]));
                }
            });
        }
        alto.contributeToWorldModel(b);
        for (Map.Entry<String, Integer> fuel : alto.fuels().entrySet()) {
            b.fuel(fuel.getKey(), fuel.getValue());
        }
        return b.build();
    }

    private static boolean hasPickaxe(Inventory inv) {
        for (ItemStack s : inv.items) {
            if (s.getItem() instanceof PickaxeItem) {
                return true;
            }
        }
        return false;
    }
}
