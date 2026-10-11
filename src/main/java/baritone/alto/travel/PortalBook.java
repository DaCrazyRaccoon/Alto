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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Known nether portals and where they lead (feature 6). Pure Java so the route choice is unit tested.
 * <p>
 * A portal is remembered when you see it or walk through it; a link is remembered when you walk through a portal
 * and arrive in another dimension. Without a link, the arrival point is estimated with the 8:1 nether scale.
 */
public final class PortalBook {

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    /** Portal blocks closer than this belong to the same portal. */
    static final int SAME_PORTAL = 4;

    public record Pos(String dim, int x, int y, int z) {
        double distSq(double px, double py, double pz) {
            double dx = x + 0.5 - px;
            double dy = y - py;
            double dz = z + 0.5 - pz;
            return dx * dx + dy * dy + dz * dz;
        }

        boolean near(Pos o, int r) {
            return dim.equals(o.dim) && Math.abs(x - o.x) <= r && Math.abs(y - o.y) <= r && Math.abs(z - o.z) <= r;
        }

        @Override
        public String toString() {
            return x + " " + y + " " + z + " (" + shortDim(dim) + ")";
        }
    }

    public record Link(Pos from, Pos to) {
    }

    /** A planned trip: walk to {@code portal}, arrive near {@code arrival} (known or estimated). */
    public record Route(Pos portal, Pos arrival, boolean arrivalKnown, double cost) {
    }

    private final List<Pos> portals = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    private boolean dirty;

    public List<Pos> portals() {
        return List.copyOf(portals);
    }

    public List<Link> links() {
        return List.copyOf(links);
    }

    public boolean dirty() {
        return dirty;
    }

    /** Remember a portal (no-op if a portal block close to it is already known). True if it was new. */
    public boolean addPortal(Pos p) {
        for (Pos q : portals) {
            if (q.near(p, SAME_PORTAL)) {
                return false;
            }
        }
        portals.add(p);
        dirty = true;
        return true;
    }

    /** Remember that walking through {@code from} arrives at {@code to} (replaces an older link of that portal). */
    public void addLink(Pos from, Pos to) {
        addPortal(from);
        addPortal(to);
        links.removeIf(l -> l.from().near(from, SAME_PORTAL));
        links.add(new Link(from, to));
        dirty = true;
    }

    /** Forget portals near {@code p} (it was broken) and their links. */
    public void forget(Pos p) {
        if (portals.removeIf(q -> q.near(p, SAME_PORTAL)) | links.removeIf(l -> l.from().near(p, SAME_PORTAL) || l.to().near(p, SAME_PORTAL))) {
            dirty = true;
        }
    }

    public Optional<Link> linkFrom(Pos portal) {
        return links.stream().filter(l -> l.from().near(portal, SAME_PORTAL)).findFirst();
    }

    /** Coordinate scale when going from one dimension to the other through a nether portal, or NaN. */
    public static double scale(String from, String to) {
        if (from.equals(OVERWORLD) && to.equals(NETHER)) {
            return 1.0 / 8;
        }
        if (from.equals(NETHER) && to.equals(OVERWORLD)) {
            return 8;
        }
        return Double.NaN;
    }

    /**
     * The best portal in {@code fromDim} for reaching {@code (tx, tz)} in {@code toDim}: the walk to the portal plus
     * the walk from the (known or estimated) arrival point to the target.
     */
    public Optional<Route> best(String fromDim, double px, double py, double pz, String toDim, double tx, double tz) {
        double s = scale(fromDim, toDim);
        if (Double.isNaN(s)) {
            return Optional.empty();
        }
        Route best = null;
        for (Pos p : portals) {
            if (!p.dim().equals(fromDim)) {
                continue;
            }
            Optional<Link> link = linkFrom(p);
            Pos arrival = link.map(Link::to).filter(t -> t.dim().equals(toDim))
                    .orElse(new Pos(toDim, (int) Math.floor(p.x() * s), p.y(), (int) Math.floor(p.z() * s)));
            boolean known = link.isPresent() && link.get().to().dim().equals(toDim);
            double here = Math.sqrt(p.distSq(px, py, pz));
            double dx = arrival.x() - tx;
            double dz = arrival.z() - tz;
            double there = Math.sqrt(dx * dx + dz * dz);
            // An unknown arrival may be up to 128 blocks off (a new portal is made where there is room): small penalty.
            double cost = here + there + (known ? 0 : 16);
            if (best == null || cost < best.cost()) {
                best = new Route(p, arrival, known, cost);
            }
        }
        return Optional.ofNullable(best);
    }

    static String shortDim(String dim) {
        return switch (dim) {
            case OVERWORLD -> "overworld";
            case NETHER -> "nether";
            case "minecraft:the_end" -> "end";
            default -> dim;
        };
    }

    /** "nether", "the_nether", "minecraft:the_nether", "overworld", "end"... to a dimension id, or null. */
    public static String parseDimension(String s) {
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "overworld", "minecraft:overworld", "ow" -> OVERWORLD;
            case "nether", "the_nether", "minecraft:the_nether" -> NETHER;
            case "end", "the_end", "minecraft:the_end" -> "minecraft:the_end";
            default -> null;
        };
    }

    // ------------------------------------------------------------------------------------------ JSON

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray ps = new JsonArray();
        for (Pos p : portals) {
            ps.add(pos(p));
        }
        root.add("portals", ps);
        JsonArray ls = new JsonArray();
        for (Link l : links) {
            JsonObject o = new JsonObject();
            o.add("from", pos(l.from()));
            o.add("to", pos(l.to()));
            ls.add(o);
        }
        root.add("links", ls);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    public static PortalBook fromJson(String json) {
        PortalBook b = new PortalBook();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("portals")) {
            root.getAsJsonArray("portals").forEach(e -> b.portals.add(pos(e.getAsJsonObject())));
        }
        if (root.has("links")) {
            root.getAsJsonArray("links").forEach(e -> b.links.add(new Link(pos(e.getAsJsonObject().getAsJsonObject("from")),
                    pos(e.getAsJsonObject().getAsJsonObject("to")))));
        }
        return b;
    }

    private static JsonObject pos(Pos p) {
        JsonObject o = new JsonObject();
        o.addProperty("dim", p.dim());
        o.addProperty("x", p.x());
        o.addProperty("y", p.y());
        o.addProperty("z", p.z());
        return o;
    }

    private static Pos pos(JsonObject o) {
        return new Pos(o.get("dim").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        dirty = false;
    }

    public static PortalBook load(Path file) throws IOException {
        return Files.exists(file) ? fromJson(Files.readString(file, StandardCharsets.UTF_8)) : new PortalBook();
    }
}
