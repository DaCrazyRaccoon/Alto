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
import baritone.alto.AltoHooks;
import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import baritone.alto.planner.Plan;
import baritone.alto.task.AltoTask;
import baritone.alto.task.PlaceAtTask;
import baritone.alto.task.PlanContext;
import baritone.alto.task.PlanTask;
import baritone.alto.task.Step;
import baritone.alto.task.UseItemOnBlockTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Builds and lights a nether portal next to you (setting {@code altoBuildPortals}, off by default): a 4x5 frame of
 * 10 obsidian with the four corners from a throwaway block (or obsidian), lit with flint and steel. Missing obsidian
 * or flint and steel are planned like any other item (storage, crafting). Never inside someone else's claim.
 */
public final class BuildPortalTask extends AltoTask {

    private enum Phase { MATERIALS, SPOT, PLACE, LIGHT, WAIT }

    private static final ItemKey OBSIDIAN = McItems.key(Items.OBSIDIAN);
    private static final ItemKey FLINT_AND_STEEL = McItems.key(Items.FLINT_AND_STEEL);
    /** Throwaway blocks kept for towering up to the top of the frame. */
    private static final int SCAFFOLD = 4;

    private Phase phase = Phase.MATERIALS;
    private AltoTask sub;
    private BlockPos base;
    private Direction.Axis axis;
    /** The open side in front of the frame, where the bot towers up to place the upper part. */
    private Direction front;
    private final List<AltoTask> placements = new ArrayList<>();
    private int ticks;

    public BuildPortalTask(Alto alto) {
        super(alto);
    }

    @Override
    public String name() {
        return "Build a nether portal";
    }

    private static Predicate<Item> throwaway() {
        return i -> i == Items.OBSIDIAN || Baritone.settings().acceptableThrowawayItems.value.contains(i);
    }

    private int throwawayCount() {
        var inv = ctx.player().getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            if (!inv.getItem(i).isEmpty() && inv.getItem(i).getItem() != Items.OBSIDIAN
                    && Baritone.settings().acceptableThrowawayItems.value.contains(inv.getItem(i).getItem())) {
                n += inv.getItem(i).getCount();
            }
        }
        return n;
    }

    @Override
    public Step tick(boolean calcFailed) {
        if (sub != null) {
            Step s = sub.tick(calcFailed);
            if (!s.finished()) {
                return s;
            }
            sub.cancel();
            sub = null;
            if (s.kind() == Step.Kind.FAILURE) {
                return Step.failure("portal: " + s.message());
            }
        }
        switch (phase) {
            case MATERIALS: {
                var inv = ctx.player().getInventory();
                int obsidian = McItems.count(inv, Items.OBSIDIAN);
                // Corners may be throwaway blocks; Baritone also needs a few to tower up in front of the frame.
                int corners = throwawayCount() >= 4 + SCAFFOLD ? 4 : 0;
                int needObsidian = 10 + (4 - corners);
                ItemCounts target = new ItemCounts();
                if (obsidian < needObsidian) {
                    target.add(OBSIDIAN, needObsidian);
                }
                if (McItems.count(inv, Items.FLINT_AND_STEEL) < 1) {
                    target.add(FLINT_AND_STEEL, 1);
                }
                if (!target.isEmpty()) {
                    Plan plan = alto.plan(target, new ItemCounts());
                    if (!plan.isComplete()) {
                        return Step.failure("a portal needs " + needObsidian + " obsidian and a flint and steel; missing " + plan.missing());
                    }
                    sub = new PlanTask(alto, plan, new PlanContext(new ItemCounts(), p -> false), target);
                    return Step.stay();
                }
                phase = Phase.SPOT;
                return Step.stay();
            }
            case SPOT: {
                if (!findSpot()) {
                    return Step.failure("no flat, clear 4x5 spot for a portal within 6 blocks");
                }
                planPlacements();
                phase = Phase.PLACE;
                return Step.stay();
            }
            case PLACE: {
                detail("building the frame (" + (14 - placements.size()) + "/14)");
                if (placements.isEmpty()) {
                    phase = Phase.LIGHT;
                    return Step.stay();
                }
                sub = placements.remove(0);
                return Step.stay();
            }
            case LIGHT: {
                BlockPos floor = base.relative(axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH);
                sub = new UseItemOnBlockTask(alto, floor, Direction.UP, FLINT_AND_STEEL);
                phase = Phase.WAIT;
                ticks = 0;
                return Step.stay();
            }
            case WAIT: {
                BlockPos inside = base.relative(axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH).above();
                if (ctx.world().getBlockState(inside).is(Blocks.NETHER_PORTAL)) {
                    alto.portals().book().addPortal(new PortalBook.Pos(ctx.world().dimension().location().toString(),
                            inside.getX(), inside.getY(), inside.getZ()));
                    alto.portals().save();
                    return Step.success("portal lit at " + inside.toShortString());
                }
                if (++ticks > 60) {
                    return Step.failure("the portal did not light");
                }
                return Step.stay();
            }
            default:
                return Step.failure("unknown phase");
        }
    }

    /** base = bottom-left corner of the frame (y = first frame row, on solid ground). */
    private boolean findSpot() {
        Level level = ctx.world();
        BlockPos feet = ctx.playerFeet();
        double best = Double.MAX_VALUE;
        for (Direction.Axis ax : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
            Direction along = ax == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
            Direction side = ax == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
            for (BlockPos c : BlockPos.betweenClosed(feet.offset(-6, -2, -6), feet.offset(6, 2, 6))) {
                BlockPos b = c.immutable();
                for (Direction f : new Direction[]{side, side.getOpposite()}) {
                    if (!fits(level, b, along, f)) {
                        continue;
                    }
                    double d = b.distSqr(feet);
                    if (d < best && d > 4) { // not right where you stand
                        best = d;
                        base = b;
                        axis = ax;
                        front = f;
                    }
                }
            }
        }
        return base != null;
    }

    private static boolean fits(Level level, BlockPos b, Direction along, Direction side) {
        for (int i = 0; i < 4; i++) {
            BlockPos col = b.relative(along, i);
            BlockState ground = level.getBlockState(col.below());
            if (!ground.isSolidRender(level, col.below()) || ground.getBlock() instanceof FallingBlock) {
                return false;
            }
            for (int h = 0; h < 5; h++) {
                BlockPos p = col.above(h);
                BlockState s = level.getBlockState(p);
                if (!(s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty()) || AltoHooks.isProtected(p.getX(), p.getY(), p.getZ())) {
                    return false;
                }
            }
            // In front of the middle columns: solid ground and 5 blocks of air (walking in, and the pillar to build from).
            if (i == 1 || i == 2) {
                BlockPos f = col.relative(side);
                BlockState g = level.getBlockState(f.below());
                if (!g.isSolidRender(level, f.below()) || g.getBlock() instanceof FallingBlock) {
                    return false;
                }
                for (int h = 0; h < 6; h++) {
                    if (!level.getBlockState(f.above(h)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void planPlacements() {
        Direction along = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        Predicate<Item> obsidian = i -> i == Items.OBSIDIAN;
        Predicate<Item> corner = throwawayCount() >= 4 + SCAFFOLD ? throwaway().and(i -> i != Items.OBSIDIAN) : obsidian;
        // From the ground: the bottom corners, the bottom row and the first block of each side (every face needed is
        // below eye level). Everything higher needs a face above eye level, so the bot towers up 3 blocks in front of
        // the frame (like a player would) and places the rest from there, ending with the top row.
        placements.add(low(base, corner, "a corner block"));
        placements.add(low(base.relative(along, 3), corner, "a corner block"));
        placements.add(low(base.relative(along, 1), obsidian, "obsidian"));
        placements.add(low(base.relative(along, 2), obsidian, "obsidian"));
        placements.add(low(base.above(1), obsidian, "obsidian"));
        placements.add(low(base.relative(along, 3).above(1), obsidian, "obsidian"));
        for (int h = 2; h <= 3; h++) {
            placements.add(high(base.above(h), obsidian, "obsidian"));
            placements.add(high(base.relative(along, 3).above(h), obsidian, "obsidian"));
        }
        placements.add(high(base.above(4), corner, "a corner block"));
        placements.add(high(base.relative(along, 3).above(4), corner, "a corner block"));
        placements.add(high(base.relative(along, 1).above(4), obsidian, "obsidian"));
        placements.add(high(base.relative(along, 2).above(4), obsidian, "obsidian"));
    }

    private AltoTask low(BlockPos pos, Predicate<Item> item, String label) {
        return new PlaceAtTask(alto, pos, item, label).standingAt(base.getY());
    }

    private AltoTask high(BlockPos pos, Predicate<Item> item, String label) {
        return new PlaceAtTask(alto, pos, item, label).standingIn(new baritone.api.pathing.goals.GoalBlock(pillarTop()));
    }

    /** Where the bot stands for the upper part: on a 3-block pillar in front of the second column. */
    BlockPos pillarTop() {
        Direction along = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        return base.relative(along, 1).relative(front).above(3);
    }

    @Override
    public void cancel() {
        if (sub != null) {
            sub.cancel();
        }
    }
}
