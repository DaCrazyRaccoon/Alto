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

package baritone.alto.storage;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.alto.container.MenuGoals;
import baritone.alto.container.SlotRole;
import baritone.alto.container.mc.ContainerAdapters;
import baritone.alto.container.mc.ContainerSession;
import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import baritone.alto.task.AltoTask;
import baritone.alto.task.Approach;
import baritone.alto.task.Step;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Puts everything except the keep-list into storage (feature F deposit / extra 1 auto-deposit).
 * <p>
 * Containers are tried nearest first: known ones with free slots, then containers inside storage zones that were
 * never opened. A zone with item rules only receives matching items. When a container turns out to be full, the
 * next one is tried; if none is left, the task fails with what could not be stored.
 */
public final class DepositTask extends AltoTask {

    private enum Phase { CHOOSE, WALK_ZONE, APPROACH, OPEN, STORE, CLOSE }

    private final Predicate<ItemKey> keep;
    private final Set<String> tried = new HashSet<>();
    private final ContainerSession session;
    private Phase phase = Phase.CHOOSE;
    private BlockPos target;
    private ContainerRecord.Key targetKey;
    private Approach approach;
    private Predicate<ItemKey> accepts = k -> true;
    private int stored;
    private int zoneWalks;

    public DepositTask(Alto alto, Predicate<ItemKey> keep) {
        super(alto);
        this.keep = keep;
        this.session = new ContainerSession(alto);
    }

    @Override
    public String name() {
        return "Deposit items";
    }

    private ItemCounts depositable() {
        ItemCounts all = McItems.inventoryCounts(ctx.player().getInventory());
        ItemCounts out = new ItemCounts();
        all.forEach((k, v) -> {
            if (!keep.test(k)) {
                out.add(k, v);
            }
        });
        return out;
    }

    @Override
    public Step tick(boolean calcFailed) {
        if (!Baritone.settings().altoContainers.value) {
            return Step.failure("container automation is switched off (altoContainers)");
        }
        switch (phase) {
            case CHOOSE:
                return choose();
            case WALK_ZONE: {
                Optional<Zones.Zone> zone = nearestZone();
                if (zone.isEmpty()) {
                    return Step.failure("no storage zone (set one with #home zone add)");
                }
                Zones.Zone z = zone.get();
                BlockPos center = new BlockPos((z.minX() + z.maxX()) / 2, (z.minY() + z.maxY()) / 2, (z.minZ() + z.maxZ()) / 2);
                if (ctx.playerFeet().distSqr(center) < 16 * 16 || calcFailed) {
                    phase = Phase.CHOOSE;
                    return Step.stay();
                }
                detail("walking to storage zone " + z.name());
                return Step.walk(new GoalNear(center, 8));
            }
            case APPROACH: {
                Step s = approach.tick(ctx, calcFailed);
                if (s != null && s.kind() == Step.Kind.FAILURE) {
                    AltoLog.info("deposit: skipping the container at " + target.toShortString() + " (" + s.message() + ")");
                    tried.add(targetKey.id());
                    phase = Phase.CHOOSE; // try the next container
                    return Step.stay();
                }
                if (s != null) {
                    detail("walking to " + target.toShortString());
                    return s;
                }
                session.openBlock(target, "storage", m -> !(m instanceof InventoryMenu) && ContainerAdapters.forMenu(m).indexable(m));
                phase = Phase.OPEN;
                return Step.stay();
            }
            case OPEN: {
                ContainerSession.Status st = session.tickOpen();
                if (st == ContainerSession.Status.FAILED) {
                    tried.add(targetKey.id());
                    phase = Phase.CHOOSE;
                    return Step.stay();
                }
                if (st == ContainerSession.Status.READY) {
                    if (!ContainerAdapters.forMenu(session.menu()).depositAllowed(session.menu())) {
                        tried.add(targetKey.id());
                        phase = Phase.CLOSE;
                    } else {
                        phase = Phase.STORE;
                    }
                }
                return Step.stay();
            }
            case STORE: {
                int before = depositable().total();
                ContainerSession.Status st = session.run(MenuGoals.store(k -> !keep.test(k) && accepts.test(k), "items", -1,
                        MenuGoals.roles(SlotRole.STORAGE)));
                detail("storing in " + target.toShortString());
                if (st == ContainerSession.Status.FAILED) {
                    tried.add(targetKey.id());
                    alto.storage().recorder().recordNow();
                    phase = Phase.CLOSE;
                } else if (st == ContainerSession.Status.DONE) {
                    stored += Math.max(0, before - depositable().total());
                    alto.storage().recorder().recordNow();
                    tried.add(targetKey.id());
                    phase = Phase.CLOSE;
                }
                return Step.stay();
            }
            case CLOSE: {
                if (session.close() == ContainerSession.Status.DONE) {
                    phase = Phase.CHOOSE;
                }
                return Step.stay();
            }
            default:
                return Step.failure("unknown phase");
        }
    }

    private Step choose() {
        ItemCounts left = depositable();
        if (left.isEmpty()) {
            return Step.success(stored > 0 ? "stored " + stored + " items" : "nothing to store");
        }
        String dim = alto.storage().dimension();
        double px = ctx.player().getX();
        double py = ctx.player().getY();
        double pz = ctx.player().getZ();
        Predicate<ContainerRecord> usable = alto.storage().usable();
        List<BlockPos> candidates = new ArrayList<>();
        for (ContainerRecord r : alto.storage().index().withSpace(dim, px, py, pz, usable)) {
            if (!tried.contains(r.key.id()) && acceptsAny(r.key, left)) {
                candidates.add(new BlockPos(r.key.x(), r.key.y(), r.key.z()));
            }
        }
        if (candidates.isEmpty()) {
            // Containers inside storage zones that were never opened.
            List<BlockPos> scan = BaritoneAPI.getProvider().getWorldScanner()
                    .scanChunkRadius(ctx, alto.storage().storageBlocks(), 64, -1, 6);
            BlockPos feet = ctx.playerFeet();
            scan.sort(Comparator.comparingDouble(p -> p.distSqr(feet)));
            for (BlockPos p : scan) {
                ContainerRecord.Key k = new ContainerRecord.Key(dim, p.getX(), p.getY(), p.getZ());
                ContainerRecord known = alto.storage().index().get(k);
                if (known != null) {
                    k = known.key;
                }
                if (!tried.contains(k.id()) && alto.storage().usable(p) && acceptsAny(k, left)
                        && (known == null || known.freeSlots > 0 && known.depositAllowed)) {
                    candidates.add(p);
                }
            }
        }
        if (candidates.isEmpty()) {
            if (zoneWalks++ < 1 && nearestZone().isPresent()) {
                phase = Phase.WALK_ZONE;
                return Step.stay();
            }
            return Step.failure("no storage with free space left for " + left);
        }
        target = candidates.get(0);
        targetKey = new ContainerRecord.Key(dim, target.getX(), target.getY(), target.getZ());
        ContainerRecord known = alto.storage().index().get(targetKey);
        if (known != null) {
            targetKey = known.key;
        }
        Optional<Zones.Zone> zone = alto.storage().zones().zoneAt(dim, target.getX(), target.getY(), target.getZ());
        accepts = zone.<Predicate<ItemKey>>map(z -> z::accepts).orElse(k -> true);
        approach = new Approach(target);
        phase = Phase.APPROACH;
        return Step.stay();
    }

    private boolean acceptsAny(ContainerRecord.Key key, ItemCounts left) {
        Optional<Zones.Zone> zone = alto.storage().zones().zoneAt(key.dimension(), key.x(), key.y(), key.z());
        if (zone.isEmpty()) {
            return true;
        }
        for (ItemKey k : left.asMap().keySet()) {
            if (zone.get().accepts(k)) {
                return true;
            }
        }
        return false;
    }

    private Optional<Zones.Zone> nearestZone() {
        return alto.storage().zones().nearestZone(alto.storage().dimension(), ctx.player().getX(), ctx.player().getY(), ctx.player().getZ());
    }

    @Override
    public void cancel() {
        session.closeNow();
    }

    static List<Block> blocks(List<Block> l) {
        return l;
    }
}
