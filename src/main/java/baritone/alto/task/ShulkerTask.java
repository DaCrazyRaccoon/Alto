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
import baritone.alto.container.MenuGoal;
import baritone.alto.container.MenuGoals;
import baritone.alto.container.SlotRole;
import baritone.alto.container.mc.ContainerSession;
import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.function.Predicate;

/**
 * Extra 3: uses a shulker box carried in the inventory. UNPACK takes items out of the box that holds them; PACK puts
 * items (never keep-list items, never other shulker boxes) into the emptiest box. Either way the box is placed at a
 * safe spot with air above it (so it can open), used, broken and picked up again; shulker boxes keep their contents
 * when broken, so nothing is lost. Only boxes Alto placed are broken.
 */
public final class ShulkerTask extends AltoTask {

    public enum Mode { UNPACK, PACK }

    private enum Phase { CHOOSE, PLACE, OPEN, WORK, CLOSE, PICKUP }

    private final Mode mode;
    private final Predicate<ItemKey> match;
    private final String label;
    private final int count;
    private final Predicate<BlockPos> forbidden;
    private final ContainerSession session;
    private Phase phase = Phase.CHOOSE;
    private PlaceBlockTask place;
    private BreakAndCollectTask pickup;
    private BlockPos pos;
    private Block block;
    private MenuGoal goal;
    private String result = "";

    public ShulkerTask(Alto alto, Mode mode, Predicate<ItemKey> match, String label, int count, Predicate<BlockPos> forbidden) {
        super(alto);
        this.mode = mode;
        this.match = match;
        this.label = label;
        this.count = count;
        this.forbidden = forbidden == null ? p -> false : forbidden;
        this.session = new ContainerSession(alto);
    }

    @Override
    public String name() {
        return (mode == Mode.UNPACK ? "Take " + (count > 0 ? count + "x " : "") + label + " out of a shulker box"
                : "Pack " + label + " into a shulker box");
    }

    @Override
    public Step tick(boolean calcFailed) {
        if (!Baritone.settings().altoShulkers.value) {
            return Step.failure("shulker box handling is switched off (altoShulkers)");
        }
        switch (phase) {
            case CHOOSE: {
                ItemStack box = chooseBox();
                if (box == null) {
                    return Step.failure(mode == Mode.UNPACK ? "no carried shulker box holds " + label : "no carried shulker box has room");
                }
                block = ((BlockItem) box.getItem()).getBlock();
                place = new PlaceBlockTask(alto, McItems.key(box), "shulker box", true, forbidden);
                phase = Phase.PLACE;
                return Step.stay();
            }
            case PLACE: {
                Step s = place.tick(calcFailed);
                if (s.kind() == Step.Kind.FAILURE) {
                    return s;
                }
                if (s.kind() == Step.Kind.SUCCESS) {
                    pos = place.placedAt();
                    session.openBlock(pos, "shulker box", m -> m instanceof ShulkerBoxMenu);
                    phase = Phase.OPEN;
                }
                return s.kind() == Step.Kind.WALK ? s : Step.stay();
            }
            case OPEN: {
                ContainerSession.Status st = session.tickOpen();
                if (st == ContainerSession.Status.FAILED) {
                    startPickup();
                    result = session.failure();
                    return Step.stay();
                }
                if (st == ContainerSession.Status.READY) {
                    goal = mode == Mode.UNPACK
                            ? MenuGoals.take(match, label, count, MenuGoals.roles(SlotRole.STORAGE), false)
                            : MenuGoals.store(k -> match.test(k) && !McItems.isShulkerBox(new ItemStack(McItems.item(k))),
                            label, count, MenuGoals.roles(SlotRole.STORAGE));
                    phase = Phase.WORK;
                }
                return Step.stay();
            }
            case WORK: {
                ContainerSession.Status st = session.run(goal);
                if (st == ContainerSession.Status.FAILED) {
                    result = session.failure();
                    phase = Phase.CLOSE;
                } else if (st == ContainerSession.Status.DONE) {
                    phase = Phase.CLOSE;
                }
                return Step.stay();
            }
            case CLOSE: {
                if (session.close() == ContainerSession.Status.DONE) {
                    startPickup();
                }
                return Step.stay();
            }
            case PICKUP: {
                Step s = pickup.tick(calcFailed);
                if (s.kind() == Step.Kind.FAILURE) {
                    return Step.failure(s.message() + " (the shulker box at " + pos.toShortString() + " keeps its contents)");
                }
                if (s.kind() == Step.Kind.SUCCESS) {
                    return result.isEmpty() ? Step.success(name() + ": done") : Step.failure(result);
                }
                return s;
            }
            default:
                return Step.failure("unknown phase");
        }
    }

    private void startPickup() {
        session.closeNow();
        pickup = new BreakAndCollectTask(alto, pos, block,
                item -> item instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock, "shulker box");
        phase = Phase.PICKUP;
    }

    /** UNPACK: the box holding the most matching items. PACK: the emptiest box with room. */
    private ItemStack chooseBox() {
        ItemStack best = null;
        int bestScore = Integer.MIN_VALUE;
        for (ItemStack s : ctx.player().getInventory().items) {
            if (!McItems.isShulkerBox(s) || s.getCount() != 1) {
                continue;
            }
            ItemContainerContents c = s.get(DataComponents.CONTAINER);
            ItemCounts inside = new ItemCounts();
            int stacks = 0;
            if (c != null) {
                for (ItemStack in : c.nonEmptyItems()) {
                    inside.add(McItems.key(in), in.getCount());
                    stacks++;
                }
            }
            int score;
            if (mode == Mode.UNPACK) {
                score = 0;
                for (var e : inside.asMap().entrySet()) {
                    if (match.test(e.getKey())) {
                        score += e.getValue();
                    }
                }
                if (score == 0) {
                    continue;
                }
            } else {
                if (stacks >= 27) {
                    continue;
                }
                score = -stacks;
            }
            if (score > bestScore) {
                best = s;
                bestScore = score;
            }
        }
        return best;
    }

    @Override
    public void cancel() {
        session.closeNow();
        if (place != null) {
            place.cancel();
        }
        if (pickup != null) {
            pickup.cancel();
        }
    }
}
