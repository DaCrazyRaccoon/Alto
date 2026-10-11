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

import baritone.alto.Alto;
import baritone.alto.container.mc.ContainerAdapters;
import baritone.alto.container.mc.ContainerSession;
import baritone.alto.task.AltoTask;
import baritone.alto.task.Approach;
import baritone.alto.task.Step;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.InventoryMenu;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Opens every container inside a storage zone once, so the storage index knows what is where (#home zone scan).
 * Double chests are opened once. Takes nothing; opening is paced like everything else.
 */
public final class ZoneScanTask extends AltoTask {

    private enum Phase { WALK, LIST, APPROACH, OPEN, CLOSE }

    private final Zones.Zone zone;
    private final Deque<BlockPos> todo = new ArrayDeque<>();
    private final Set<String> seen = new HashSet<>();
    private final ContainerSession session;
    private Phase phase = Phase.WALK;
    private BlockPos current;
    private Approach approach;
    private int opened;
    private int total;
    private int failed;

    public ZoneScanTask(Alto alto, Zones.Zone zone) {
        super(alto);
        this.zone = zone;
        this.session = new ContainerSession(alto);
    }

    @Override
    public String name() {
        return "Index storage zone " + zone.name();
    }

    private BlockPos center() {
        return new BlockPos((zone.minX() + zone.maxX()) / 2, (zone.minY() + zone.maxY()) / 2, (zone.minZ() + zone.maxZ()) / 2);
    }

    @Override
    public Step tick(boolean calcFailed) {
        switch (phase) {
            case WALK: {
                if (!zone.dimension().equals(alto.storage().dimension())) {
                    return Step.failure("the zone is in " + zone.dimension());
                }
                if (ctx.world().isLoaded(center()) && ctx.playerFeet().distSqr(center()) < 48 * 48 || calcFailed) {
                    phase = Phase.LIST;
                    return Step.stay();
                }
                return Step.walk(new GoalNear(center(), 8));
            }
            case LIST: {
                List<BlockPos> found = BaritoneAPI.getProvider().getWorldScanner()
                        .scanChunkRadius(ctx, alto.storage().storageBlocks(), 256, -1, 8);
                found.removeIf(p -> !zone.contains(alto.storage().dimension(), p.getX(), p.getY(), p.getZ()));
                BlockPos feet = ctx.playerFeet();
                found.sort(Comparator.comparingDouble(p -> p.distSqr(feet)));
                todo.addAll(found);
                total = found.size();
                if (total == 0) {
                    return Step.success("no containers found in zone " + zone.name());
                }
                return next();
            }
            case APPROACH: {
                Step s = approach.tick(ctx, calcFailed);
                if (s != null) {
                    return s.kind() == Step.Kind.FAILURE ? skip() : s;
                }
                session.openBlock(current, "container", m -> !(m instanceof InventoryMenu) && ContainerAdapters.forMenu(m).indexable(m));
                phase = Phase.OPEN;
                return Step.stay();
            }
            case OPEN: {
                ContainerSession.Status st = session.tickOpen();
                if (st == ContainerSession.Status.FAILED) {
                    return skip();
                }
                if (st == ContainerSession.Status.READY && !session.confirming()) {
                    alto.storage().recorder().recordNow();
                    ContainerRecord.Key k = alto.storage().recorder().currentKey();
                    if (k != null) {
                        ContainerRecord r = alto.storage().index().get(k);
                        if (r != null) {
                            r.keys().forEach(key -> seen.add(key.id()));
                        }
                    }
                    opened++;
                    phase = Phase.CLOSE;
                }
                return Step.stay();
            }
            case CLOSE: {
                if (session.close() == ContainerSession.Status.DONE) {
                    return next();
                }
                return Step.stay();
            }
            default:
                return Step.failure("unknown phase");
        }
    }

    private Step skip() {
        failed++;
        session.closeNow();
        return next();
    }

    private Step next() {
        progress(opened + failed, total);
        while (!todo.isEmpty()) {
            BlockPos p = todo.poll();
            String id = new ContainerRecord.Key(alto.storage().dimension(), p.getX(), p.getY(), p.getZ()).id();
            if (seen.contains(id)) {
                continue; // other half of a double chest
            }
            current = p;
            approach = new Approach(p);
            phase = Phase.APPROACH;
            detail("container " + (opened + failed + 1) + "/" + total);
            return Step.stay();
        }
        return Step.success("indexed " + opened + " container(s)" + (failed > 0 ? ", " + failed + " could not be opened" : ""));
    }

    @Override
    public void cancel() {
        session.closeNow();
    }
}
