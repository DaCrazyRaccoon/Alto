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

package baritone.alto.travel;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.api.BaritoneAPI;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Learns nether portals (feature 6): portals seen within a few chunks, and where a portal led when you walked
 * through it (per world, in baritone/alto/worlds/&lt;id&gt;/portals.json). Reads only what the client sees.
 */
public final class PortalManager {

    private final Alto alto;
    private PortalBook book = new PortalBook();
    private Path file;
    private String worldId = "";
    private String lastDim = "";
    private PortalBook.Pos lastPortal;
    private int sincePortal = 1000;
    private PortalBook.Pos pendingFrom;
    private int pendingTicks;
    private int ticks;

    public PortalManager(Alto alto) {
        this.alto = alto;
        alto.addTickListener(a -> tick());
    }

    public PortalBook book() {
        ensureLoaded();
        return book;
    }

    private void ensureLoaded() {
        String id = alto.storage().worldId();
        if (id.equals(worldId)) {
            return;
        }
        save();
        worldId = id;
        Path dir = alto.storage().worldDir();
        file = dir == null ? null : dir.resolve("portals.json");
        try {
            book = file == null ? new PortalBook() : PortalBook.load(file);
        } catch (IOException | RuntimeException e) {
            AltoLog.warn("Could not read portals.json (" + e.getMessage() + "); starting empty.");
            book = new PortalBook();
            file = file.resolveSibling("portals-recovered.json");
        }
    }

    public void save() {
        if (file != null && book.dirty()) {
            try {
                book.save(file);
            } catch (IOException e) {
                AltoLog.info("could not save portals: " + e);
            }
        }
    }

    private void tick() {
        LocalPlayer p = alto.ctx().player();
        Level level = alto.ctx().world();
        if (p == null || level == null || !Baritone.settings().altoPortals.value) {
            return;
        }
        ensureLoaded();
        String dim = level.dimension().location().toString();
        BlockPos feet = p.blockPosition();
        if (level.getBlockState(feet).is(Blocks.NETHER_PORTAL) || level.getBlockState(feet.above()).is(Blocks.NETHER_PORTAL)) {
            lastPortal = representative(level, dim, feet);
            sincePortal = 0;
        } else {
            sincePortal++;
        }
        if (!dim.equals(lastDim)) {
            if (!lastDim.isEmpty() && lastPortal != null && !lastPortal.dim().equals(dim) && sincePortal < 400) {
                pendingFrom = lastPortal; // arrived through a portal: link it once the new area has loaded
                pendingTicks = 0;
            }
            lastDim = dim;
            lastPortal = null;
        }
        if (pendingFrom != null && ++pendingTicks >= 20) {
            PortalBook.Pos to = nearestPortalBlock(level, dim, feet).orElse(new PortalBook.Pos(dim, feet.getX(), feet.getY(), feet.getZ()));
            book.addLink(pendingFrom, to);
            AltoLog.debug("portal link " + pendingFrom + " -> " + to);
            pendingFrom = null;
            save();
        }
        if (++ticks % 100 == 0) {
            scan(level, dim);
        }
        if (ticks % 1200 == 0) {
            save();
        }
    }

    /** Portals in the loaded chunks around you (cheap: at most 32 blocks, every 5 seconds). */
    private void scan(Level level, String dim) {
        List<BlockPos> found = BaritoneAPI.getProvider().getWorldScanner()
                .scanChunkRadius(alto.ctx(), List.of(Blocks.NETHER_PORTAL), 32, -1, 4);
        for (BlockPos b : found) {
            book.addPortal(representative(level, dim, b));
        }
    }

    /** The lowest, most negative portal block of the portal containing {@code b}: stable for the whole portal. */
    static PortalBook.Pos representative(Level level, String dim, BlockPos b) {
        BlockPos cur = b;
        for (int i = 0; i < 24; i++) {
            BlockPos next = null;
            for (BlockPos n : new BlockPos[]{cur.below(), cur.west(), cur.north()}) {
                if (level.getBlockState(n).is(Blocks.NETHER_PORTAL)) {
                    next = n;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            cur = next;
        }
        return new PortalBook.Pos(dim, cur.getX(), cur.getY(), cur.getZ());
    }

    private static Optional<PortalBook.Pos> nearestPortalBlock(Level level, String dim, BlockPos feet) {
        return BlockPos.betweenClosedStream(feet.offset(-3, -3, -3), feet.offset(3, 3, 3))
                .filter(b -> level.getBlockState(b).is(Blocks.NETHER_PORTAL))
                .map(BlockPos::immutable)
                .min(Comparator.comparingDouble(b -> b.distSqr(feet)))
                .map(b -> representative(level, dim, b));
    }

    /** A portal block of the remembered portal, if it still exists (null if it is gone or not loaded). */
    public BlockPos standingSpot(Level level, PortalBook.Pos portal) {
        BlockPos b = new BlockPos(portal.x(), portal.y(), portal.z());
        if (!level.isLoaded(b)) {
            return b; // walk there first
        }
        if (level.getBlockState(b).is(Blocks.NETHER_PORTAL)) {
            return b;
        }
        Optional<PortalBook.Pos> near = nearestPortalBlock(level, portal.dim(), b);
        if (near.isEmpty()) {
            book.forget(portal);
            return null;
        }
        return new BlockPos(near.get().x(), near.get().y(), near.get().z());
    }
}
