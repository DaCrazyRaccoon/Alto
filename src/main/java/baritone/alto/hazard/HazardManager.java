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

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoHooks;
import baritone.alto.AltoLog;
import baritone.api.utils.IPlayerContext;
import baritone.utils.pathing.Avoidance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Applies {@link HazardRules} to Baritone's pathfinder (feature G hazard awareness):
 * <ul>
 *     <li>hard rules (never walk into / never stand on) are published to {@link AltoHooks} as block sets, checked by
 *     the path search thread;</li>
 *     <li>areas around active hazards (rotating saws, running fans and their air currents, moving contraptions,
 *     trains) become avoidance zones each time a path search starts, from block entities and entities the client
 *     already knows about.</li>
 * </ul>
 * Mod integrations provide the conditions ("is it rotating", "where does the fan blow"); without them those rules
 * simply never match.
 */
public final class HazardManager {

    private static final int MAX_AVOIDANCES = 256;
    private static final int SCAN_CHUNKS = 2;

    private final Alto alto;
    private final Path file;
    private HazardRules rules = HazardRules.defaults();
    private Map<Block, HazardRules.BlockRule> byBlock = Map.of();
    private Map<String, HazardRules.EntityRule> byEntity = Map.of();
    private BiPredicate<Level, BlockEntity> rotating = (level, be) -> false;
    private Function<BlockEntity, List<BlockPos>> airCurrent = be -> List.of();

    public HazardManager(Alto alto) {
        this.alto = alto;
        this.file = alto.directory().resolve("hazards.json");
        AltoHooks.addAvoidanceProvider(this::avoidances);
    }

    /** Integrations: how to tell that a block entity is running (e.g. Create kinetic speed != 0). */
    public void setRotatingCondition(BiPredicate<Level, BlockEntity> condition) {
        this.rotating = condition;
    }

    /** Integrations: blocks inside a fan's air current. */
    public void setAirCurrent(Function<BlockEntity, List<BlockPos>> provider) {
        this.airCurrent = provider;
    }

    public HazardRules rules() {
        return rules;
    }

    /** Load hazards.json (writing the defaults the first time) and publish the rules. */
    public String reload() {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, HazardRules.defaults().toJson(), StandardCharsets.UTF_8);
            }
            rules = HazardRules.fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            rules = HazardRules.defaults();
            AltoLog.warn("Could not read " + file + " (" + e.getMessage() + "); using the default hazard rules.");
        }
        publish();
        return rules.blocks().size() + " block rules, " + rules.entities().size() + " entity rules, belts: "
                + rules.beltMode().name().toLowerCase(java.util.Locale.ROOT) + " (" + byBlock.size() + " blocks present in this game)";
    }

    public void publish() {
        Map<Block, HazardRules.BlockRule> blocks = new HashMap<>();
        Set<Block> walkInto = new HashSet<>();
        Set<Block> walkOn = new HashSet<>();
        for (HazardRules.BlockRule r : rules.blocks()) {
            Block b = block(r.id());
            if (b == null || r.when() == HazardRules.When.NEVER) {
                continue;
            }
            blocks.put(b, r);
            if (r.neverWalkInto()) {
                walkInto.add(b);
            }
            if (r.neverWalkOn()) {
                walkOn.add(b);
            }
        }
        if (rules.beltMode() == HazardRules.BeltMode.AVOID) {
            Block belt = block("create:belt");
            if (belt != null) {
                walkOn.add(belt);
            }
        }
        Map<String, HazardRules.EntityRule> entities = new HashMap<>();
        for (HazardRules.EntityRule r : rules.entities()) {
            entities.put(r.type(), r);
        }
        byBlock = Map.copyOf(blocks);
        byEntity = Map.copyOf(entities);
        if (Baritone.settings().altoHazards.value) {
            AltoHooks.publishBlockRules(walkInto, walkOn);
        } else {
            AltoHooks.publishBlockRules(Set.of(), Set.of());
        }
    }

    private static Block block(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return null;
        }
        Block b = BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
        return b == Blocks.AIR ? null : b;
    }

    /** Client thread, at the start of every path search. */
    private List<Avoidance> avoidances(IPlayerContext ctx) {
        if (!Baritone.settings().altoHazards.value || ctx.world() == null || ctx.player() == null
                || byBlock.isEmpty() && byEntity.isEmpty()) {
            return List.of();
        }
        List<Avoidance> out = new ArrayList<>();
        Level level = ctx.world();
        BlockPos feet = ctx.playerFeet();
        int pcx = feet.getX() >> 4;
        int pcz = feet.getZ() >> 4;
        for (int cx = pcx - SCAN_CHUNKS; cx <= pcx + SCAN_CHUNKS; cx++) {
            for (int cz = pcz - SCAN_CHUNKS; cz <= pcz + SCAN_CHUNKS; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (out.size() >= MAX_AVOIDANCES) {
                        return out;
                    }
                    HazardRules.BlockRule r = byBlock.get(be.getBlockState().getBlock());
                    if (r == null || r.radius() <= 0 && !r.airCurrent()) {
                        continue;
                    }
                    boolean active = r.when() == HazardRules.When.ALWAYS || r.when() == HazardRules.When.ROTATING && rotating.test(level, be);
                    if (!active) {
                        continue;
                    }
                    if (r.radius() > 0) {
                        out.add(new Avoidance(be.getBlockPos(), r.cost(), r.radius()));
                    }
                    if (r.airCurrent()) {
                        for (BlockPos p : airCurrent.apply(be)) {
                            out.add(new Avoidance(p, r.cost(), 1));
                        }
                    }
                }
            }
        }
        if (!byEntity.isEmpty()) {
            for (Entity e : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
                if (out.size() >= MAX_AVOIDANCES) {
                    break;
                }
                HazardRules.EntityRule r = byEntity.get(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
                if (r != null && e.distanceToSqr(ctx.player()) < 64 * 64) {
                    int extra = (int) Math.ceil(Math.max(e.getBbWidth(), e.getBbHeight()) / 2);
                    out.add(new Avoidance(e.blockPosition(), r.cost(), r.radius() + extra));
                }
            }
        }
        return out;
    }
}
