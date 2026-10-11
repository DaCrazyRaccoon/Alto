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

import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageIndexTest {

    private static final String OW = "minecraft:overworld";

    private static ContainerRecord chest(int x, int y, int z, Object... contents) {
        ContainerRecord r = new ContainerRecord(new ContainerRecord.Key(OW, x, y, z));
        r.blockId = "minecraft:chest";
        r.contents = ItemCounts.of(contents);
        r.totalSlots = 27;
        r.freeSlots = 27 - r.contents.asMap().size();
        r.lastSeenMillis = 1_000L;
        return r;
    }

    @Test
    void doubleChestHalvesShareOneRecord() {
        StorageIndex idx = new StorageIndex();
        ContainerRecord r = chest(0, 64, 0, "oak_log", 64);
        r.partner = new ContainerRecord.Key(OW, 1, 64, 0);
        idx.put(r);
        assertSame(idx.get(new ContainerRecord.Key(OW, 1, 64, 0)), idx.get(new ContainerRecord.Key(OW, 0, 64, 0)));
        assertEquals(1, idx.size());
        assertTrue(idx.remove(new ContainerRecord.Key(OW, 1, 64, 0)));
        assertNull(idx.get(new ContainerRecord.Key(OW, 0, 64, 0)));
        assertEquals(0, idx.size());
    }

    @Test
    void reRecordingReplacesOldContents() {
        StorageIndex idx = new StorageIndex();
        idx.put(chest(5, 64, 5, "cobblestone", 64));
        idx.put(chest(5, 64, 5, "cobblestone", 10));
        assertEquals(1, idx.size());
        assertEquals(10, idx.get(new ContainerRecord.Key(OW, 5, 64, 5)).contents.get(ItemKey.of("cobblestone")));
    }

    @Test
    void containingIsNearestFirstAndRespectsTheFilterAndDimension() {
        StorageIndex idx = new StorageIndex();
        idx.put(chest(100, 64, 0, "iron_ingot", 5));
        idx.put(chest(10, 64, 0, "iron_ingot", 3));
        ContainerRecord nether = chest(1, 64, 0, "iron_ingot", 99);
        nether.key = new ContainerRecord.Key("minecraft:the_nether", 1, 64, 0);
        idx.put(nether);
        idx.put(chest(2, 64, 0, "dirt", 5));
        List<ContainerRecord> found = idx.containing(ItemKey.of("iron_ingot"), OW, 0, 64, 0, r -> true);
        assertEquals(2, found.size());
        assertEquals(10, found.get(0).key.x());
        assertEquals(1, idx.containing(ItemKey.of("iron_ingot"), OW, 0, 64, 0, r -> r.key.x() > 50).size());
    }

    @Test
    void enderChestIsReachableFromEveryDimension() {
        StorageIndex idx = new StorageIndex();
        ContainerRecord ender = chest(0, 0, 0, "diamond", 2);
        ender.key = ContainerRecord.Key.ENDER;
        idx.put(ender);
        assertEquals(1, idx.containing(ItemKey.of("diamond"), "minecraft:the_end", 0, 0, 0, r -> true).size());
        assertTrue(idx.withSpace("minecraft:overworld", 0, 0, 0, r -> true).isEmpty(), "never deposit into the ender chest automatically");
    }

    @Test
    void withSpaceSkipsFullAndLootContainers() {
        StorageIndex idx = new StorageIndex();
        ContainerRecord full = chest(1, 64, 0, "dirt", 64);
        full.freeSlots = 0;
        ContainerRecord loot = chest(2, 64, 0);
        loot.depositAllowed = false;
        ContainerRecord ok = chest(3, 64, 0);
        idx.put(full);
        idx.put(loot);
        idx.put(ok);
        List<ContainerRecord> space = idx.withSpace(OW, 0, 64, 0, r -> true);
        assertEquals(1, space.size());
        assertEquals(3, space.get(0).key.x());
    }

    @Test
    void jsonRoundTripKeepsEverything(@TempDir Path dir) throws IOException {
        StorageIndex idx = new StorageIndex();
        ContainerRecord a = chest(0, -10, 0, "oak_log", 64, "torch", 12);
        a.partner = new ContainerRecord.Key(OW, 0, -10, 1);
        a.nested = ItemCounts.of("diamond", 3);
        a.label = "wood";
        idx.put(a);
        ContainerRecord ender = chest(0, 0, 0, "ender_pearl", 4);
        ender.key = ContainerRecord.Key.ENDER;
        idx.put(ender);
        ContainerRecord variant = chest(9, 9, 9);
        variant.contents = new ItemCounts();
        variant.contents.add(ItemKey.of("minecraft:potion", "ab12cd34ef"), 2);
        idx.put(variant);
        Path file = dir.resolve("storage.json");
        idx.save(file);
        assertFalse(idx.dirty());
        StorageIndex back = StorageIndex.load(file);
        assertEquals(3, back.size());
        ContainerRecord b = back.get(new ContainerRecord.Key(OW, 0, -10, 1));
        assertNotNull(b);
        assertEquals(a, b);
        assertEquals(2, back.get(new ContainerRecord.Key(OW, 9, 9, 9)).contents.get(ItemKey.of("minecraft:potion", "ab12cd34ef")));
        assertEquals(4, back.get(ContainerRecord.Key.ENDER).contents.get(ItemKey.of("ender_pearl")));
        assertFalse(Files.exists(dir.resolve("storage.json.tmp")));
    }

    @Test
    void rejectsForeignAndNewerFiles() {
        assertThrows(IllegalArgumentException.class, () -> StorageIndex.fromJson("{\"format\":\"something\",\"containers\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> StorageIndex.fromJson("{\"format\":\"alto-storage\",\"version\":99,\"containers\":[]}"));
    }

    @Test
    void missingFileIsAnEmptyIndex(@TempDir Path dir) throws IOException {
        assertEquals(0, StorageIndex.load(dir.resolve("nope.json")).size());
    }

    @Test
    void zonesContainRulesAndPersist(@TempDir Path dir) throws IOException {
        Zones z = new Zones();
        z.putZone(new Zones.Zone("Ores", OW, 0, 60, 0, 10, 70, 10, List.of("minecraft:raw_iron", "minecraft:coal")));
        z.putZone(new Zones.Zone("Main", OW, 20, 60, 20, 30, 70, 30, List.of()));
        z.setHome(new Zones.Home("home", OW, 5, 64, 5));
        assertTrue(z.zoneAt(OW, 5, 65, 5).isPresent());
        assertFalse(z.zoneAt("minecraft:the_nether", 5, 65, 5).isPresent());
        assertTrue(z.zone("ores").get().accepts(ItemKey.of("coal")));
        assertFalse(z.zone("ORES").get().accepts(ItemKey.of("dirt")));
        assertTrue(z.zone("main").get().accepts(ItemKey.of("dirt")));
        assertTrue(z.inAnyZone(new ContainerRecord.Key(OW, 25, 65, 25)));
        assertFalse(z.inAnyZone(new ContainerRecord.Key(OW, 50, 65, 50)));
        assertTrue(z.inAnyZone(ContainerRecord.Key.ENDER));
        assertEquals("Main", z.nearestZone(OW, 40, 64, 40).get().name());
        Path f = dir.resolve("zones.json");
        z.save(f);
        Zones back = Zones.load(f);
        assertEquals(2, back.zones().size());
        assertEquals(List.of("minecraft:raw_iron", "minecraft:coal"), back.zone("ores").get().rules());
        assertEquals(5, back.home("HOME").get().x());
        assertTrue(back.removeZone("main"));
        assertTrue(back.clearHome("home"));
    }

    @Test
    void keyParsing() {
        ContainerRecord.Key k = new ContainerRecord.Key("modded:dim", -5, -64, 7);
        assertEquals(k, ContainerRecord.Key.parse(k.id()));
        assertTrue(ContainerRecord.Key.parse("enderchest").isEnder());
    }
}
