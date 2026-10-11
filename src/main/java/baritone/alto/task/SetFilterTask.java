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

package baritone.alto.task;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.alto.container.MenuGoals;
import baritone.alto.core.Backoff;
import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import baritone.alto.core.Pacer;
import baritone.alto.integration.FilterAccess;
import baritone.alto.mc.McItems;
import baritone.alto.planner.Plan;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Sets the filter of a block face (Create deployers, brass funnels/tunnels, smart chutes...) the way a player does:
 * hold the item (or an empty hand to clear), stand in front of the face, look exactly at the slot, right-click, keep
 * looking for a moment (slots with an amount are confirmed by the mod's own client code), then check that the
 * block now shows the new filter. If the item is not carried, it is fetched or crafted first; if that is impossible
 * the task stops and says what to bring.
 */
public final class SetFilterTask extends AltoTask {

    private enum Phase { CHECK, OBTAIN, HOTBAR, APPROACH, AIM, HOLD, VERIFY }

    private final BlockPos pos;
    private final ItemKey item;
    private final Direction wantedFace;
    private Phase phase = Phase.CHECK;
    private FilterAccess access;
    private FilterAccess.FilterSlot slot;
    private Rotation aim;
    private PlanTask obtain;
    private MenuGoalTask hotbar;
    private final Backoff backoff = new Backoff(10, 60, 4);
    private int ticks;
    private int approachTries;

    /**
     * @param item EMPTY to clear the filter
     * @param face the face to use, or null for any face with a slot
     */
    public SetFilterTask(Alto alto, BlockPos pos, ItemKey item, Direction face) {
        super(alto);
        this.pos = pos.immutable();
        this.item = item;
        this.wantedFace = face;
    }

    @Override
    public String name() {
        return (item.isEmpty() ? "Clear the filter" : "Set the filter to " + item.shortName()) + " at " + pos.toShortString();
    }

    private boolean clearing() {
        return item.isEmpty();
    }

    @Override
    public Step tick(boolean calcFailed) {
        if (!Baritone.settings().altoFilters.value) {
            return Step.failure("setting filters is switched off (altoFilters)");
        }
        switch (phase) {
            case CHECK: {
                access = alto.filterAccess(ctx.world(), pos);
                if (access == null) {
                    return Step.failure("no filter slot at " + pos.toShortString() + " (or the chunk is not loaded / Create is not installed)");
                }
                if (alreadySet()) {
                    return Step.success("the filter already is " + label());
                }
                if (!clearing() && McItems.count(ctx.player().getInventory(), McItems.item(item)) <= 0) {
                    Plan plan = alto.plan(ItemCounts.of(item, 1), new ItemCounts());
                    if (!plan.isComplete()) {
                        return Step.failure("I need 1x " + item.shortName() + " to set this filter and cannot get one"
                                + " (missing: " + plan.missing() + "). Please bring one.");
                    }
                    obtain = new PlanTask(alto, plan, new PlanContext(new ItemCounts(), p -> false), ItemCounts.of(item, 1));
                    phase = Phase.OBTAIN;
                } else {
                    phase = Phase.HOTBAR;
                }
                return Step.stay();
            }
            case OBTAIN: {
                Step s = obtain.tick(calcFailed);
                if (s.kind() == Step.Kind.FAILURE) {
                    return Step.failure("could not get " + item.shortName() + ": " + s.message());
                }
                if (s.kind() == Step.Kind.SUCCESS) {
                    obtain.cancel();
                    phase = Phase.HOTBAR;
                    return Step.stay();
                }
                return s;
            }
            case HOTBAR:
                return hotbar(calcFailed);
            case APPROACH: {
                aim = findAim();
                if (aim != null) {
                    phase = Phase.AIM;
                    ticks = 0;
                    return Step.stay();
                }
                if (calcFailed && ++approachTries > 3) {
                    return Step.failure("cannot get to a spot from which the filter slot is visible");
                }
                List<FilterAccess.FilterSlot> slots = slots();
                if (slots.isEmpty()) {
                    return Step.failure("the block at " + pos.toShortString() + " has no filter slot" + (wantedFace == null ? "" : " on " + wantedFace));
                }
                detail("walking to the " + slots.get(0).label());
                BlockPos stand = pos.relative(slots.get(0).face(), 2);
                if (ctx.playerFeet().distSqr(stand) <= 2 && approachTries > 0) {
                    return Step.walk(new GoalGetToBlock(pos));
                }
                return Step.walk(new GoalNear(stand, 1));
            }
            case AIM: {
                if (backoff.waiting()) {
                    return Step.stay();
                }
                Rotation fresh = findAim();
                if (fresh == null) {
                    phase = Phase.APPROACH;
                    return Step.stay();
                }
                aim = fresh;
                if (!selectHand()) {
                    phase = Phase.HOTBAR;
                    return Step.stay();
                }
                alto.baritone().getLookBehavior().updateTarget(aim, true);
                HitResult hit = ctx.objectMouseOver();
                if (!onSlot(hit)) {
                    if (++ticks > 40) {
                        backoff.fail();
                        if (backoff.exhausted()) {
                            return Step.failure("could not aim at the filter slot");
                        }
                        ticks = 0;
                    }
                    return Step.stay();
                }
                if (ctx.player().isShiftKeyDown() || !ctx.player().getDeltaMovement().multiply(1, 0, 1).equals(Vec3.ZERO)
                        && ctx.player().getDeltaMovement().horizontalDistanceSqr() > 1e-4) {
                    return Step.stay(); // must not sneak (sneaking bypasses filter slots), stand still
                }
                if (!alto.pacer().tryAcquire(Pacer.Kind.BLOCK_USE)) {
                    return Step.stay();
                }
                InteractionResult r = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, (BlockHitResult) hit);
                if (r.shouldSwing()) {
                    ctx.player().swing(InteractionHand.MAIN_HAND);
                }
                AltoLog.debug("filter click on " + slot.label() + " with " + item + " -> " + r);
                phase = Phase.HOLD;
                ticks = 0;
                return Step.stay();
            }
            case HOLD: {
                // Keep looking at the slot without holding "use": slots with an amount are confirmed by the mod's
                // own client a tick later (Create ValueSettingsClient).
                alto.baritone().getLookBehavior().updateTarget(aim, true);
                if (++ticks >= (slot.countUi() ? 6 : 2)) {
                    phase = Phase.VERIFY;
                    ticks = 0;
                }
                return Step.stay();
            }
            case VERIFY: {
                if (alreadySet()) {
                    return Step.success("filter set to " + label());
                }
                if (++ticks > 30 + alto.lag().confirmTicks(Baritone.settings())) {
                    backoff.fail();
                    if (backoff.exhausted()) {
                        return Step.failure("the filter did not change (the block may belong to someone else, or it rejects " + label() + ")");
                    }
                    phase = Phase.AIM;
                    ticks = 0;
                }
                return Step.stay();
            }
            default:
                return Step.failure("unknown phase");
        }
    }

    private String label() {
        return clearing() ? "empty" : item.shortName();
    }

    private Step hotbar(boolean calcFailed) {
        if (clearing()) {
            if (emptyHotbarSlot() < 0) {
                return Step.failure("need an empty hotbar slot to clear the filter (an empty hand clears it)");
            }
            phase = Phase.APPROACH;
            return Step.stay();
        }
        if (hotbarSlotOf() >= 0) {
            phase = Phase.APPROACH;
            return Step.stay();
        }
        if (hotbar == null) {
            hotbar = new MenuGoalTask(alto, "Put " + item.shortName() + " on the hotbar",
                    MenuGoals.toHotbar(McItems.key(McItems.item(item)), k -> !(McItems.item(k) instanceof TieredItem)));
        }
        Step s = hotbar.tick(calcFailed);
        if (s.finished()) {
            hotbar.cancel();
            hotbar = null;
            if (s.kind() == Step.Kind.FAILURE) {
                return s;
            }
        }
        return Step.stay();
    }

    private boolean selectHand() {
        Inventory inv = ctx.player().getInventory();
        int slotIndex = clearing() ? emptyHotbarSlot() : hotbarSlotOf();
        if (slotIndex < 0) {
            return false;
        }
        inv.selected = slotIndex;
        return true;
    }

    private int hotbarSlotOf() {
        Inventory inv = ctx.player().getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(McItems.item(item))) {
                return i;
            }
        }
        return -1;
    }

    private int emptyHotbarSlot() {
        Inventory inv = ctx.player().getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private List<FilterAccess.FilterSlot> slots() {
        List<FilterAccess.FilterSlot> all = access.slots(ctx.world(), pos);
        List<FilterAccess.FilterSlot> out = new ArrayList<>();
        for (FilterAccess.FilterSlot s : all) {
            if (wantedFace == null || s.face() == wantedFace) {
                out.add(s);
            }
        }
        return out;
    }

    private boolean alreadySet() {
        for (FilterAccess.FilterSlot s : slots()) {
            ItemStack cur = access.current(ctx.world(), pos, s.face());
            boolean match = clearing() ? cur.isEmpty() : !cur.isEmpty() && McItems.key(cur.getItem()).equals(item.plain());
            if (match && (wantedFace != null || slot == null || s.face() == slot.face())) {
                return slot != null || wantedFace != null || slots().size() == 1;
            }
        }
        return false;
    }

    /** A rotation from the current eye position whose ray hits a filter slot on its face, or null. */
    private Rotation findAim() {
        double reach = ctx.playerController().getBlockReachDistance();
        Vec3 eye = ctx.player().getEyePosition();
        for (FilterAccess.FilterSlot s : slots()) {
            for (Vec3 p : candidates(s)) {
                if (eye.distanceTo(p) > reach) {
                    continue;
                }
                Rotation r = RotationUtils.calcRotationFromVec3d(eye, p, ctx.playerRotations());
                HitResult hit = RayTraceUtils.rayTraceTowards(ctx.player(), r, reach);
                if (hitsSlot(hit, s)) {
                    slot = s;
                    return r;
                }
            }
        }
        return null;
    }

    private boolean onSlot(HitResult hit) {
        return slot != null && hitsSlot(hit, slot);
    }

    private boolean hitsSlot(HitResult hit, FilterAccess.FilterSlot s) {
        return hit instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(pos)
                && b.getDirection() == s.face() && b.getLocation().distanceTo(s.center()) < s.radius() * 0.95;
    }

    /** The slot centre projected onto its face, plus a few nearby points (sloped models such as funnels). */
    private List<Vec3> candidates(FilterAccess.FilterSlot s) {
        List<Vec3> out = new ArrayList<>();
        Direction f = s.face();
        Vec3 c = s.center();
        double plane = f.getAxisDirection() == Direction.AxisDirection.POSITIVE ? pos.get(f.getAxis()) + 1.0 : pos.get(f.getAxis());
        Vec3 projected = switch (f.getAxis()) {
            case X -> new Vec3(plane, c.y, c.z);
            case Y -> new Vec3(c.x, plane, c.z);
            case Z -> new Vec3(c.x, c.y, plane);
        };
        out.add(projected);
        out.add(c);
        double step = s.radius() / 3;
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) {
                    continue;
                }
                Vec3 off = switch (f.getAxis()) {
                    case X -> new Vec3(0, a * step, b * step);
                    case Y -> new Vec3(a * step, 0, b * step);
                    case Z -> new Vec3(a * step, b * step, 0);
                };
                out.add(projected.add(off));
            }
        }
        return out;
    }

    @Override
    public void cancel() {
        if (obtain != null) {
            obtain.cancel();
        }
        if (hotbar != null) {
            hotbar.cancel();
        }
    }
}
