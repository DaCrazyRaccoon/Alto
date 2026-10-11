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

package baritone.alto;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import baritone.utils.pathing.Avoidance;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The few static entry points that upstream Baritone code calls (each call site is marked "ALTO FORK").
 * <p>
 * Thread safety: the pathfinder runs off the client thread and calls the block checks here. They only read
 * immutable sets published through volatile fields, which the client thread replaces as a whole
 * ({@link #publishBlockRules}, {@link #publishProtection}). {@link #extraAvoidances} is called while a path search
 * is being set up on the client thread.
 */
public final class AltoHooks {

    private static volatile Set<Block> neverWalkInto = Set.of();
    private static volatile Set<Block> neverWalkOn = Set.of();
    private static volatile ProtectionSnapshot protection = ProtectionSnapshot.NONE;
    private static final List<Function<IPlayerContext, List<Avoidance>>> AVOIDANCE_PROVIDERS = new CopyOnWriteArrayList<>();

    private AltoHooks() {
    }

    /** Positions the bot must neither break nor place in (claims of other players). Immutable. */
    public interface ProtectionSnapshot {
        ProtectionSnapshot NONE = (x, y, z) -> false;

        boolean isProtected(int x, int y, int z);
    }

    private static boolean on() {
        return Baritone.settings().altoEnabled.value;
    }

    // ------------------------------------------------------------------------------ publishing (client thread)

    public static void publishBlockRules(Set<Block> walkInto, Set<Block> walkOn) {
        neverWalkInto = Set.copyOf(walkInto);
        neverWalkOn = Set.copyOf(walkOn);
    }

    public static void publishProtection(ProtectionSnapshot snapshot) {
        protection = snapshot == null ? ProtectionSnapshot.NONE : snapshot;
    }

    public static void addAvoidanceProvider(Function<IPlayerContext, List<Avoidance>> provider) {
        AVOIDANCE_PROVIDERS.add(provider);
    }

    // ------------------------------------------------------------------------------ called by upstream code

    /** MovementHelper.avoidWalkingInto: hazard blocks (e.g. Create saws and crushing wheels). */
    public static boolean avoidWalkingInto(Block block) {
        return on() && neverWalkInto.contains(block);
    }

    /** MovementHelper.canWalkOnBlockState: blocks never stood on (e.g. Create belts in "avoid" mode). */
    public static boolean neverWalkOn(Block block) {
        return on() && neverWalkOn.contains(block);
    }

    /** CalculationContext.isPossiblyProtected: never break or place here (other players' claims). */
    public static boolean isProtected(int x, int y, int z) {
        return on() && protection.isProtected(x, y, z);
    }

    /** Avoidance.create: extra areas to avoid (running machines, moving contraptions). Client thread. */
    public static List<Avoidance> extraAvoidances(IPlayerContext ctx) {
        if (!on() || AVOIDANCE_PROVIDERS.isEmpty()) {
            return List.of();
        }
        List<Avoidance> out = new ArrayList<>();
        for (Function<IPlayerContext, List<Avoidance>> p : AVOIDANCE_PROVIDERS) {
            try {
                out.addAll(p.apply(ctx));
            } catch (Throwable t) {
                AltoLog.debug("avoidance provider failed: " + t);
            }
        }
        return out;
    }

    /** Ladders, including modded ones (Create metal ladders, copycats...) when altoCompatClimbables is on. */
    public static boolean isLadder(Block block) {
        return block == Blocks.LADDER || block instanceof LadderBlock && on() && Baritone.settings().altoCompatClimbables.value;
    }

    /** Scaffolding, including modded subclasses (Create metal scaffolding). */
    public static boolean isScaffolding(Block block) {
        return block == Blocks.SCAFFOLDING || block instanceof ScaffoldingBlock && on() && Baritone.settings().altoCompatClimbables.value;
    }

    /** Farmland, including modded farmland (Farmer's Delight rich soil farmland). */
    public static boolean isFarmland(Block block) {
        return block == Blocks.FARMLAND || block instanceof FarmBlock && on() && Baritone.settings().altoCompatCrops.value;
    }
}
