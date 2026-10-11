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

import baritone.alto.core.ItemKey;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Home points and storage zones for one world/server (feature F). A storage zone is a box: containers inside it are
 * "your storage" — the only containers Alto fetches from and deposits into (unless altoStorageAnywhere is on), so it
 * never takes from someone else's chest on a server. A zone may have item rules (only these items go in).
 */
public final class Zones {

    public record Home(String name, String dimension, int x, int y, int z) {
    }

    public record Zone(String name, String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                       List<String> rules) {
        public Zone {
            rules = List.copyOf(rules);
        }

        public boolean contains(String dim, int x, int y, int z) {
            return dimension.equals(dim) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        /** Whether this zone accepts an item (no rules = accepts everything). */
        public boolean accepts(ItemKey item) {
            return rules.isEmpty() || rules.contains(item.id()) || rules.contains(item.shortName());
        }

        public Zone withRules(List<String> newRules) {
            return new Zone(name, dimension, minX, minY, minZ, maxX, maxY, maxZ, newRules);
        }

        public double distanceTo(String dim, double x, double y, double z) {
            if (!dimension.equals(dim)) {
                return Double.POSITIVE_INFINITY;
            }
            double dx = Math.max(Math.max(minX - x, 0), x - (maxX + 1));
            double dy = Math.max(Math.max(minY - y, 0), y - (maxY + 1));
            double dz = Math.max(Math.max(minZ - z, 0), z - (maxZ + 1));
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public String describe() {
            return name + " (" + minX + " " + minY + " " + minZ + " to " + maxX + " " + maxY + " " + maxZ + ", " + dimension
                    + (rules.isEmpty() ? "" : ", only " + String.join(" ", rules)) + ")";
        }
    }

    private final Map<String, Home> homes = new LinkedHashMap<>();
    private final Map<String, Zone> zones = new LinkedHashMap<>();

    private static String norm(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public void setHome(Home h) {
        homes.put(norm(h.name()), h);
    }

    public boolean clearHome(String name) {
        return homes.remove(norm(name)) != null;
    }

    public Optional<Home> home(String name) {
        return Optional.ofNullable(homes.get(norm(name)));
    }

    public List<Home> homes() {
        return new ArrayList<>(homes.values());
    }

    public void putZone(Zone z) {
        zones.put(norm(z.name()), z);
    }

    public boolean removeZone(String name) {
        return zones.remove(norm(name)) != null;
    }

    public Optional<Zone> zone(String name) {
        return Optional.ofNullable(zones.get(norm(name)));
    }

    public List<Zone> zones() {
        return new ArrayList<>(zones.values());
    }

    /** The zone containing a position, if any (first defined wins). */
    public Optional<Zone> zoneAt(String dim, int x, int y, int z) {
        for (Zone zone : zones.values()) {
            if (zone.contains(dim, x, y, z)) {
                return Optional.of(zone);
            }
        }
        return Optional.empty();
    }

    public boolean inAnyZone(ContainerRecord.Key key) {
        return key.isEnder() || zoneAt(key.dimension(), key.x(), key.y(), key.z()).isPresent();
    }

    public Optional<Zone> nearestZone(String dim, double x, double y, double z) {
        return zones.values().stream().filter(zn -> zn.dimension().equals(dim))
                .min(Comparator.comparingDouble(zn -> zn.distanceTo(dim, x, y, z)));
    }

    public boolean isEmpty() {
        return zones.isEmpty();
    }

    // ------------------------------------------------------------------------------------------ persistence

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("format", "alto-zones");
        root.addProperty("version", 1);
        JsonArray h = new JsonArray();
        for (Home home : homes.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", home.name());
            o.addProperty("dimension", home.dimension());
            o.addProperty("x", home.x());
            o.addProperty("y", home.y());
            o.addProperty("z", home.z());
            h.add(o);
        }
        JsonArray z = new JsonArray();
        for (Zone zone : zones.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", zone.name());
            o.addProperty("dimension", zone.dimension());
            JsonArray min = new JsonArray();
            min.add(zone.minX());
            min.add(zone.minY());
            min.add(zone.minZ());
            JsonArray max = new JsonArray();
            max.add(zone.maxX());
            max.add(zone.maxY());
            max.add(zone.maxZ());
            o.add("min", min);
            o.add("max", max);
            JsonArray rules = new JsonArray();
            zone.rules().forEach(rules::add);
            o.add("rules", rules);
            z.add(o);
        }
        root.add("homes", h);
        root.add("zones", z);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    public static Zones fromJson(String json) {
        Zones zs = new Zones();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!"alto-zones".equals(root.get("format").getAsString())) {
            throw new IllegalArgumentException("not an alto-zones file");
        }
        for (JsonElement e : root.getAsJsonArray("homes")) {
            JsonObject o = e.getAsJsonObject();
            zs.setHome(new Home(o.get("name").getAsString(), o.get("dimension").getAsString(),
                    o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()));
        }
        for (JsonElement e : root.getAsJsonArray("zones")) {
            JsonObject o = e.getAsJsonObject();
            JsonArray min = o.getAsJsonArray("min");
            JsonArray max = o.getAsJsonArray("max");
            List<String> rules = new ArrayList<>();
            if (o.has("rules")) {
                o.getAsJsonArray("rules").forEach(r -> rules.add(r.getAsString()));
            }
            zs.putZone(new Zone(o.get("name").getAsString(), o.get("dimension").getAsString(),
                    min.get(0).getAsInt(), min.get(1).getAsInt(), min.get(2).getAsInt(),
                    max.get(0).getAsInt(), max.get(1).getAsInt(), max.get(2).getAsInt(), rules));
        }
        return zs;
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, toJson(), StandardCharsets.UTF_8);
    }

    public static Zones load(Path file) throws IOException {
        return Files.exists(file) ? fromJson(Files.readString(file, StandardCharsets.UTF_8)) : new Zones();
    }
}
