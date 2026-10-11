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
import baritone.alto.planner.WorldModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Owns the storage index and the home/zone list of the current world or server, loads them on join and saves them
 * (atomically) every 30 seconds when changed and on leaving. Files live in
 * {@code baritone/alto/worlds/<world id>/} where the id is {@code sp-<save folder>} or {@code mp-<server address>}.
 */
public final class StorageManager {

    private final Alto alto;
    private final StorageRecorder recorder;
    private String worldId = "";
    private Path dir;
    private StorageIndex index = new StorageIndex();
    private Zones zones = new Zones();
    private boolean zonesDirty;
    private int ticks;

    public StorageManager(Alto alto) {
        this.alto = alto;
        this.recorder = new StorageRecorder(alto, this);
        alto.addTickListener(a -> tick());
        alto.addWorldModelContributor(this::contribute);
    }

    private final java.util.List<net.minecraft.world.level.block.Block> extraStorageBlocks = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Integrations register their storage blocks here (scanned when looking for unopened containers in zones). */
    public void addStorageBlocks(java.util.Collection<net.minecraft.world.level.block.Block> blocks) {
        extraStorageBlocks.addAll(blocks);
    }

    /** Block types that count as storage when scanning zones. */
    public java.util.List<net.minecraft.world.level.block.Block> storageBlocks() {
        java.util.List<net.minecraft.world.level.block.Block> list = new java.util.ArrayList<>(java.util.List.of(
                net.minecraft.world.level.block.Blocks.CHEST, net.minecraft.world.level.block.Blocks.TRAPPED_CHEST,
                net.minecraft.world.level.block.Blocks.BARREL));
        list.addAll(extraStorageBlocks);
        list.addAll(Baritone.settings().altoStorageBlocks.value);
        return list;
    }

    public StorageRecorder recorder() {
        return recorder;
    }

    public StorageIndex index() {
        ensureLoaded();
        return index;
    }

    public Zones zones() {
        ensureLoaded();
        return zones;
    }

    public void zonesChanged() {
        zonesDirty = true;
        save();
    }

    public String worldId() {
        ensureLoaded();
        return worldId;
    }

    /** Dimension id of the player's level. */
    public String dimension() {
        return alto.ctx().world() == null ? "" : alto.ctx().world().dimension().location().toString();
    }

    /** Containers Alto may take from / put into: inside a storage zone (or anywhere, if the user allows it). */
    public Predicate<ContainerRecord> usable() {
        boolean anywhere = Baritone.settings().altoStorageAnywhere.value;
        return r -> anywhere || zones().inAnyZone(r.key);
    }

    public boolean usable(BlockPos pos) {
        return Baritone.settings().altoStorageAnywhere.value || zones().zoneAt(dimension(), pos.getX(), pos.getY(), pos.getZ()).isPresent();
    }

    private void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (!worldId.isEmpty()) {
                save();
                worldId = "";
            }
            return;
        }
        ensureLoaded();
        recorder.tick();
        if (++ticks % 600 == 0) {
            save();
        }
    }

    private void ensureLoaded() {
        String id = currentWorldId();
        if (id.equals(worldId)) {
            return;
        }
        if (!worldId.isEmpty()) {
            save();
        }
        worldId = id;
        dir = alto.directory().resolve("worlds").resolve(id);
        try {
            index = StorageIndex.load(dir.resolve("storage.json"));
            zones = Zones.load(dir.resolve("zones.json"));
            if (index.size() > 0 || !zones.isEmpty()) {
                AltoLog.info("loaded storage index (" + index.size() + " containers) and " + zones.zones().size() + " zone(s) for " + id);
            }
        } catch (IOException | RuntimeException e) {
            AltoLog.warn("Could not read Alto storage data for " + id + " (" + e.getMessage() + "); starting empty. The old file is kept.");
            index = new StorageIndex();
            zones = new Zones();
            dir = dir.resolveSibling(id + "-recovered");
        }
    }

    public void save() {
        if (dir == null || worldId.isEmpty()) {
            return;
        }
        try {
            if (index.dirty()) {
                index.save(dir.resolve("storage.json"));
            }
            if (zonesDirty) {
                zones.save(dir.resolve("zones.json"));
                zonesDirty = false;
            }
        } catch (IOException e) {
            AltoLog.info("could not save storage data: " + e);
        }
    }

    /** Planner hook: known, usable containers become storage sources. */
    private void contribute(WorldModel.Builder b) {
        if (!Baritone.settings().altoPlannerUseStorage.value || alto.ctx().player() == null) {
            return;
        }
        String dim = dimension();
        double px = alto.ctx().player().getX();
        double py = alto.ctx().player().getY();
        double pz = alto.ctx().player().getZ();
        Predicate<ContainerRecord> ok = usable();
        for (ContainerRecord r : index().all()) {
            if (!ok.test(r) || r.contents.isEmpty()) {
                continue;
            }
            double d = r.key.distanceTo(dim, px, py, pz);
            if (r.key.isEnder()) {
                d = alto.stations().nearestEnderChestDistance();
                if (Double.isInfinite(d)) {
                    continue;
                }
            }
            if (Double.isFinite(d)) {
                b.storage(r.key.id(), r.describe(), d, r.contents);
            }
        }
    }

    static String currentWorldId() {
        Minecraft mc = Minecraft.getInstance();
        String id;
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            Path root = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path name = root.getFileName() == null || root.getFileName().toString().equals(".") ? root.getParent().getFileName() : root.getFileName();
            id = "sp-" + name;
        } else {
            ServerData sd = mc.getCurrentServer();
            id = sd == null ? "unknown" : "mp-" + (sd.isRealm() ? "realms" : sd.ip);
        }
        return id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }
}
