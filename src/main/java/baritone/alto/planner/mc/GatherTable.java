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
import baritone.alto.AltoLog;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import baritone.alto.planner.WorldModel;
import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which items can be mined from which blocks, for the planner's last-resort "mine it" option. The blocks come from
 * the setting altoGatherBlocks (natural blocks only, so Alto never tears down builds for materials); what each block
 * drops is checked with Baritone's own loot-table lookup ({@link BlockOptionalMeta#matches(ItemStack)}).
 */
public final class GatherTable {

    /** Items a block may drop besides itself (vanilla ores and stone). Only confirmed drops are used. */
    private static final Item[] DROP_CANDIDATES = {
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.COAL, Items.RAW_IRON, Items.RAW_COPPER, Items.RAW_GOLD,
            Items.REDSTONE, Items.LAPIS_LAZULI, Items.FLINT, Items.CLAY_BALL, Items.QUARTZ, Items.GOLD_NUGGET,
            Items.EMERALD, Items.DIAMOND, Items.GLOWSTONE_DUST, Items.STICK, Items.APPLE
    };

    private static List<Block> cachedFor;
    private static Map<ItemKey, WorldModel.GatherSource> cached = Map.of();

    private GatherTable() {
    }

    public static synchronized Map<ItemKey, WorldModel.GatherSource> sources() {
        List<Block> blocks = Baritone.settings().altoGatherBlocks.value;
        if (blocks != cachedFor) {
            cached = build(blocks);
            cachedFor = blocks;
        }
        return cached;
    }

    private static Map<ItemKey, WorldModel.GatherSource> build(List<Block> blocks) {
        Map<ItemKey, List<String>> byItem = new LinkedHashMap<>();
        Map<ItemKey, Double> ticks = new LinkedHashMap<>();
        for (Block block : blocks) {
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            List<Item> candidates = new ArrayList<>();
            candidates.add(block.asItem());
            candidates.addAll(List.of(DROP_CANDIDATES));
            BlockOptionalMeta bom;
            try {
                bom = new BlockOptionalMeta(block);
            } catch (Throwable t) {
                AltoLog.debug("no drop data for " + id + ": " + t);
                continue;
            }
            for (Item item : candidates) {
                if (item == Items.AIR) {
                    continue;
                }
                boolean drops;
                try {
                    drops = bom.matches(new ItemStack(item));
                } catch (Throwable t) {
                    drops = item == block.asItem();
                }
                if (drops) {
                    ItemKey key = McItems.key(item);
                    byItem.computeIfAbsent(key, k -> new ArrayList<>()).add(id);
                    double t = block.defaultDestroyTime() * 30 + 60;
                    ticks.merge(key, t, Math::min);
                }
            }
        }
        Map<ItemKey, WorldModel.GatherSource> out = new LinkedHashMap<>();
        byItem.forEach((k, v) -> out.put(k, new WorldModel.GatherSource(List.copyOf(v), ticks.getOrDefault(k, 100.0))));
        return out;
    }
}
