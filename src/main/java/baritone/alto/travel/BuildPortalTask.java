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
    private static final int SCAFFOLD = 6;

    private Phase phase = Phase.MATERIALS;
    private AltoTask sub;
    private BlockPos base;
    private Direction.Axis axis;
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
                // Corners may be throwaway blocks, but Baritone also needs a few to tower up for the top of the frame.
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
                if (!fits(level, b, along, side)) {
                    continue;
                }
                double d = b.distSqr(feet);
                if (d < best && d > 4) { // not right where you stand
                    best = d;
                    base = b;
                    axis = ax;
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
            // Room to walk in on one side of the middle columns.
            if ((i == 1 || i == 2) && !level.getBlockState(col.relative(side).above()).isAir()
                    && !level.getBlockState(col.relative(side.getOpposite()).above()).isAir()) {
                return false;
            }
        }
        return true;
    }

    private void planPlacements() {
        Direction along = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        Predicate<Item> obsidian = i -> i == Items.OBSIDIAN;
        Predicate<Item> corner = throwawayCount() >= 4 + SCAFFOLD ? throwaway().and(i -> i != Items.OBSIDIAN) : obsidian;
        // Bottom corners, bottom row, the two sides from the bottom up, top corners, top row: every block rests on one placed before.
        placements.add(stand(new PlaceAtTask(alto, base, corner, "a corner block")));
        placements.add(stand(new PlaceAtTask(alto, base.relative(along, 3), corner, "a corner block")));
        placements.add(stand(new PlaceAtTask(alto, base.relative(along, 1), obsidian, "obsidian")));
        placements.add(stand(new PlaceAtTask(alto, base.relative(along, 2), obsidian, "obsidian")));
        for (int h = 1; h <= 3; h++) {
            placements.add(stand(new PlaceAtTask(alto, base.above(h), obsidian, "obsidian")));
            placements.add(stand(new PlaceAtTask(alto, base.relative(along, 3).above(h), obsidian, "obsidian")));
        }
        placements.add(stand(new PlaceAtTask(alto, base.above(4), corner, "a corner block")));
        placements.add(stand(new PlaceAtTask(alto, base.relative(along, 3).above(4), corner, "a corner block")));
        placements.add(stand(new PlaceAtTask(alto, base.relative(along, 1).above(4), obsidian, "obsidian")));
        placements.add(stand(new PlaceAtTask(alto, base.relative(along, 2).above(4), obsidian, "obsidian")));
    }

    private AltoTask stand(PlaceAtTask t) {
        return t.pos().getY() <= base.getY() + 1 ? t.standingAt(base.getY()) : t;
    }

    @Override
    public void cancel() {
        if (sub != null) {
            sub.cancel();
        }
    }
}
