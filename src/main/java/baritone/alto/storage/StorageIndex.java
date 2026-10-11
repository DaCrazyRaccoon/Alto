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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Persistent index of what was seen in which container, per world or server (feature F; replaces upstream's
 * removed container memory). Records are written only from menus the client actually had open and synced
 * (research.md §2: the old memory guessed from packets on the network thread and broke silently).
 * <p>
 * Pure Java + Gson so it can be unit tested; the Minecraft side ({@code StorageRecorder}) feeds it.
 * File format: {@code {"format":"alto-storage","version":1,"containers":[...]}}, written atomically.
 */
public final class StorageIndex {

    public static final int VERSION = 1;

    private final Map<String, ContainerRecord> byKey = new LinkedHashMap<>();
    private boolean dirty;

    /** Adds or replaces a record (both halves of a double chest point to it). */
    public void put(ContainerRecord r) {
        for (ContainerRecord.Key k : List.copyOf(keysOf(r))) {
            ContainerRecord old = byKey.get(k.id());
            if (old != null && old != r) {
                old.keys().forEach(ok -> byKey.remove(ok.id()));
            }
        }
        for (ContainerRecord.Key k : r.keys()) {
            byKey.put(k.id(), r);
        }
        dirty = true;
    }

    private static List<ContainerRecord.Key> keysOf(ContainerRecord r) {
        return r.keys();
    }

    public ContainerRecord get(ContainerRecord.Key key) {
        return byKey.get(key.id());
    }

    /** Forgets a container (its block was broken or replaced). */
    public boolean remove(ContainerRecord.Key key) {
        ContainerRecord r = byKey.get(key.id());
        if (r == null) {
            return false;
        }
        r.keys().forEach(k -> byKey.remove(k.id()));
        dirty = true;
        return true;
    }

    /** Distinct records. */
    public Collection<ContainerRecord> all() {
        return new ArrayList<>(new java.util.LinkedHashSet<>(byKey.values()));
    }

    public int size() {
        return all().size();
    }

    /** Containers in {@code dimension} (plus the ender chest) that hold {@code item}, nearest first. */
    public List<ContainerRecord> containing(ItemKey item, String dimension, double x, double y, double z, Predicate<ContainerRecord> allowed) {
        List<ContainerRecord> out = new ArrayList<>();
        for (ContainerRecord r : all()) {
            if (r.contents.get(item) > 0 && allowed.test(r) && (r.key.isEnder() || r.key.dimension().equals(dimension))) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingDouble(r -> r.key.distanceTo(dimension, x, y, z)));
        return out;
    }

    /** Containers that had free slots (or space in partial stacks of {@code item}) when last seen, nearest first. */
    public List<ContainerRecord> withSpace(String dimension, double x, double y, double z, Predicate<ContainerRecord> allowed) {
        List<ContainerRecord> out = new ArrayList<>();
        for (ContainerRecord r : all()) {
            if (r.depositAllowed && r.freeSlots > 0 && allowed.test(r) && !r.key.isEnder() && r.key.dimension().equals(dimension)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingDouble(r -> r.key.distanceTo(dimension, x, y, z)));
        return out;
    }

    /** Sum of an item over the given records. */
    public static int total(Collection<ContainerRecord> records, ItemKey item) {
        int n = 0;
        for (ContainerRecord r : records) {
            n += r.contents.get(item);
        }
        return n;
    }

    public ItemCounts totals(Predicate<ContainerRecord> allowed) {
        ItemCounts c = new ItemCounts();
        for (ContainerRecord r : all()) {
            if (allowed.test(r)) {
                c.addAll(r.contents);
            }
        }
        return c;
    }

    public boolean dirty() {
        return dirty;
    }

    // ------------------------------------------------------------------------------------------ persistence

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("format", "alto-storage");
        root.addProperty("version", VERSION);
        JsonArray list = new JsonArray();
        for (ContainerRecord r : all()) {
            JsonObject o = new JsonObject();
            o.addProperty("key", r.key.id());
            if (r.partner != null) {
                o.addProperty("partner", r.partner.id());
            }
            o.addProperty("block", r.blockId);
            o.addProperty("adapter", r.adapter);
            o.addProperty("label", r.label);
            o.addProperty("totalSlots", r.totalSlots);
            o.addProperty("freeSlots", r.freeSlots);
            o.addProperty("lastSeen", r.lastSeenMillis);
            o.addProperty("depositAllowed", r.depositAllowed);
            o.add("contents", counts(r.contents));
            if (!r.nested.isEmpty()) {
                o.add("nested", counts(r.nested));
            }
            list.add(o);
        }
        root.add("containers", list);
        return gson().toJson(root);
    }

    public static StorageIndex fromJson(String json) {
        StorageIndex index = new StorageIndex();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!"alto-storage".equals(str(root, "format"))) {
            throw new IllegalArgumentException("not an alto-storage file");
        }
        int version = root.has("version") ? root.get("version").getAsInt() : 0;
        if (version > VERSION) {
            throw new IllegalArgumentException("storage file version " + version + " is newer than this Alto (" + VERSION + ")");
        }
        for (JsonElement e : root.getAsJsonArray("containers")) {
            JsonObject o = e.getAsJsonObject();
            ContainerRecord r = new ContainerRecord(ContainerRecord.Key.parse(str(o, "key")));
            if (o.has("partner")) {
                r.partner = ContainerRecord.Key.parse(str(o, "partner"));
            }
            r.blockId = str(o, "block");
            r.adapter = str(o, "adapter");
            r.label = str(o, "label");
            r.totalSlots = o.has("totalSlots") ? o.get("totalSlots").getAsInt() : 0;
            r.freeSlots = o.has("freeSlots") ? o.get("freeSlots").getAsInt() : 0;
            r.lastSeenMillis = o.has("lastSeen") ? o.get("lastSeen").getAsLong() : 0;
            r.depositAllowed = !o.has("depositAllowed") || o.get("depositAllowed").getAsBoolean();
            r.contents = counts(o.getAsJsonObject("contents"));
            r.nested = o.has("nested") ? counts(o.getAsJsonObject("nested")) : new ItemCounts();
            index.put(r);
        }
        index.dirty = false;
        return index;
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }

    public static StorageIndex load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new StorageIndex();
        }
        return fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static JsonObject counts(ItemCounts c) {
        JsonObject o = new JsonObject();
        c.forEach((k, v) -> o.addProperty(k.toString(), v));
        return o;
    }

    private static ItemCounts counts(JsonObject o) {
        ItemCounts c = new ItemCounts();
        if (o == null) {
            return c;
        }
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String k = e.getKey();
            int brace = k.indexOf('{');
            ItemKey key = brace < 0 ? ItemKey.of(k) : ItemKey.of(k.substring(0, brace), k.substring(brace + 1, k.length() - 1));
            c.add(key, e.getValue().getAsInt());
        }
        return c;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static Gson gson() {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    }
}
