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

package baritone.alto.task;

import baritone.alto.Alto;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import baritone.api.process.IMineProcess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * A plan's "mine N of X" step, done by Baritone's own miner: Alto starts {@link IMineProcess} and defers to it
 * until the inventory holds enough, then stops it. (Baritone's miner is a normal process, so while it runs other
 * normal processes such as the builder are reset; {@code BuilderSupply} restarts the build afterwards.)
 */
public final class GatherTask extends AltoTask {

    private final ItemKey item;
    private final int count;
    private final List<String> blockIds;
    private int target = -1;
    private boolean started;
    private int idleTicks;

    public GatherTask(Alto alto, ItemKey item, int count, List<String> blockIds) {
        super(alto);
        this.item = item;
        this.count = count;
        this.blockIds = List.copyOf(blockIds);
    }

    @Override
    public String name() {
        return "Mine " + count + "x " + item.shortName();
    }

    private int have() {
        Item it = McItems.item(item);
        return McItems.count(ctx.player().getInventory(), it);
    }

    @Override
    public Step tick(boolean calcFailed) {
        if (!alto.settingsAllowGather()) {
            return Step.failure("mining for materials is switched off (altoPlannerAllowGather)");
        }
        int now = have();
        if (target < 0) {
            target = now + count;
        }
        progress(Math.max(0, count - (target - now)), count);
        if (now >= target) {
            stopMiner();
            return Step.success("have " + now + "x " + item.shortName());
        }
        IMineProcess miner = alto.baritone().getMineProcess();
        if (!started) {
            List<Block> blocks = new ArrayList<>();
            for (String id : blockIds) {
                ResourceLocation rl = ResourceLocation.tryParse(id);
                Block b = rl == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(rl);
                if (b != Blocks.AIR) {
                    blocks.add(b);
                }
            }
            if (blocks.isEmpty()) {
                return Step.failure("no known block drops " + item.shortName());
            }
            miner.mine(0, blocks.toArray(new Block[0]));
            started = true;
            detail("mining " + String.join(", ", blockIds));
            return Step.defer();
        }
        if (!miner.isActive()) {
            if (++idleTicks > 40) {
                return Step.failure("the miner stopped with " + (count - (target - now)) + "/" + count + " " + item.shortName()
                        + " (none reachable nearby?)");
            }
            return Step.stay();
        }
        idleTicks = 0;
        return Step.defer();
    }

    private void stopMiner() {
        if (started && alto.baritone().getMineProcess().isActive()) {
            alto.baritone().getMineProcess().cancel();
        }
    }

    @Override
    public void cancel() {
        stopMiner();
    }
}
