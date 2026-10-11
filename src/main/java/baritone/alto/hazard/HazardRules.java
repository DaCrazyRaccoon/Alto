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

package baritone.alto.hazard;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The editable hazard list ({@code baritone/alto/hazards.json}): blocks and entities the pathfinder should keep away
 * from. Pure data + JSON so it can be unit tested. Defaults describe Create 6.0.10 (research/create.md §3.2);
 * entries for mods that are not installed are simply ignored.
 *
 * @see HazardManager
 */
public final class HazardRules {

    public enum When {
        /** Always a hazard. */
        ALWAYS,
        /** Only while the block entity reports rotation (a condition an integration provides, e.g. Create). */
        ROTATING,
        /** Rule disabled. */
        NEVER;

        static When parse(String s) {
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                return ALWAYS;
            }
        }
    }

    /**
     * @param id            block id
     * @param when          when the area around it is avoided
     * @param neverWalkInto the pathfinder never moves through this block (state-only check, always on)
     * @param neverWalkOn   the pathfinder never stands on this block
     * @param radius        avoided radius in blocks around it while {@code when} holds
     * @param cost          cost multiplier inside that radius (Baritone "favoring" coefficient, >1 = avoid)
     * @param airCurrent    also avoid the air current in front (fans)
     */
    public record BlockRule(String id, When when, boolean neverWalkInto, boolean neverWalkOn, int radius, double cost, boolean airCurrent) {
    }

    /** Entities (moving contraptions, trains) treated as moving obstacles. */
    public record EntityRule(String type, int radius, double cost) {
    }

    public enum BeltMode { AVOID, ALLOW }

    private final List<BlockRule> blocks;
    private final List<EntityRule> entities;
    private final BeltMode beltMode;

    public HazardRules(List<BlockRule> blocks, List<EntityRule> entities, BeltMode beltMode) {
        this.blocks = List.copyOf(blocks);
        this.entities = List.copyOf(entities);
        this.beltMode = beltMode;
    }

    public List<BlockRule> blocks() {
        return blocks;
    }

    public List<EntityRule> entities() {
        return entities;
    }

    public BeltMode beltMode() {
        return beltMode;
    }

    /** The shipped defaults (Create 6.0.10). */
    public static HazardRules defaults() {
        List<BlockRule> b = new ArrayList<>();
        b.add(new BlockRule("create:mechanical_drill", When.ROTATING, true, true, 2, 20, false));
        b.add(new BlockRule("create:mechanical_saw", When.ROTATING, true, true, 2, 20, false));
        b.add(new BlockRule("create:crushing_wheel_controller", When.ALWAYS, true, true, 2, 50, false));
        b.add(new BlockRule("create:crushing_wheel", When.ROTATING, true, true, 1, 50, false));
        b.add(new BlockRule("create:encased_fan", When.ROTATING, false, false, 1, 10, true));
        b.add(new BlockRule("create:weighted_ejector", When.ALWAYS, false, true, 1, 5, false));
        b.add(new BlockRule("create:deployer", When.ROTATING, false, false, 2, 20, false));
        b.add(new BlockRule("create:turntable", When.ROTATING, false, true, 1, 5, false));
        b.add(new BlockRule("create:mysterious_cuckoo_clock", When.ALWAYS, false, false, 4, 20, false));
        List<EntityRule> e = new ArrayList<>();
        e.add(new EntityRule("create:contraption", 3, 20));
        e.add(new EntityRule("create:stationary_contraption", 4, 20));
        e.add(new EntityRule("create:gantry_contraption", 3, 20));
        e.add(new EntityRule("create:carriage_contraption", 4, 50));
        return new HazardRules(b, e, BeltMode.AVOID);
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("format", "alto-hazards");
        root.addProperty("version", 1);
        root.addProperty("_help", "Edit freely, then run #alto hazards reload. when: always | rotating | never. "
                + "radius/cost: area the pathfinder avoids (cost > 1 = avoid). neverWalkInto/neverWalkOn: hard rules. "
                + "beltMode: avoid (never stand on create:belt) or allow.");
        root.addProperty("beltMode", beltMode.name().toLowerCase(Locale.ROOT));
        JsonArray b = new JsonArray();
        for (BlockRule r : blocks) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("when", r.when().name().toLowerCase(Locale.ROOT));
            o.addProperty("neverWalkInto", r.neverWalkInto());
            o.addProperty("neverWalkOn", r.neverWalkOn());
            o.addProperty("radius", r.radius());
            o.addProperty("cost", r.cost());
            if (r.airCurrent()) {
                o.addProperty("airCurrent", true);
            }
            b.add(o);
        }
        JsonArray e = new JsonArray();
        for (EntityRule r : entities) {
            JsonObject o = new JsonObject();
            o.addProperty("type", r.type());
            o.addProperty("radius", r.radius());
            o.addProperty("cost", r.cost());
            e.add(o);
        }
        root.add("blocks", b);
        root.add("entities", e);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    public static HazardRules fromJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!"alto-hazards".equals(root.get("format").getAsString())) {
            throw new IllegalArgumentException("not an alto-hazards file");
        }
        List<BlockRule> b = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("blocks")) {
            JsonObject o = el.getAsJsonObject();
            b.add(new BlockRule(o.get("id").getAsString(), When.parse(str(o, "when", "always")), bool(o, "neverWalkInto"),
                    bool(o, "neverWalkOn"), Math.max(0, Math.min(16, num(o, "radius", 1).intValue())),
                    Math.max(1.0, Math.min(1000, num(o, "cost", 10).doubleValue())), bool(o, "airCurrent")));
        }
        List<EntityRule> e = new ArrayList<>();
        if (root.has("entities")) {
            for (JsonElement el : root.getAsJsonArray("entities")) {
                JsonObject o = el.getAsJsonObject();
                e.add(new EntityRule(o.get("type").getAsString(), Math.max(0, Math.min(16, num(o, "radius", 3).intValue())),
                        Math.max(1.0, Math.min(1000, num(o, "cost", 20).doubleValue()))));
            }
        }
        BeltMode mode = "allow".equalsIgnoreCase(str(root, "beltMode", "avoid")) ? BeltMode.ALLOW : BeltMode.AVOID;
        return new HazardRules(b, e, mode);
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }

    private static boolean bool(JsonObject o, String k) {
        return o.has(k) && o.get(k).getAsBoolean();
    }

    private static Number num(JsonObject o, String k, Number def) {
        return o.has(k) ? o.get(k).getAsNumber() : def;
    }
}
