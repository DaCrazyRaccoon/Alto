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

import baritone.alto.Alto;
import baritone.alto.container.MenuGoals;
import baritone.alto.container.MenuView;
import baritone.alto.container.SlotRole;
import baritone.alto.container.SlotView;
import baritone.alto.container.mc.ContainerSession;
import net.minecraft.world.inventory.AbstractFurnaceMenu;

/**
 * Feature E, part 2: waits at a loaded furnace and takes the output until the expected amount was collected.
 * While the menu is open, output is taken in batches so the furnace never stops because its output is full.
 * Ends early (with what it got) when the input is used up and the furnace went cold, e.g. because fuel ran out.
 */
public final class SmeltCollectTask extends AltoTask {

    private enum Phase { APPROACH, OPEN, WATCH, TAKE, CLOSE }

    private final PlanContext.PendingSmelt pending;
    private final PlanContext pctx;
    private final ContainerSession session;
    private Phase phase = Phase.APPROACH;
    private Approach approach;
    private int collected;
    private int coldTicks;
    private int watchTicks;
    private String endMessage;
    private boolean success;

    public SmeltCollectTask(Alto alto, PlanContext.PendingSmelt pending, PlanContext pctx) {
        super(alto);
        this.pending = pending;
        this.pctx = pctx;
        this.session = new ContainerSession(alto);
        this.approach = new Approach(pending.furnace());
    }

    @Override
    public String name() {
        return "Collect " + pending.expected() + "x " + pending.output().shortName() + " from the " + pending.kind().displayName;
    }

    @Override
    public Step tick(boolean calcFailed) {
        progress(collected, pending.expected());
        switch (phase) {
            case APPROACH: {
                Step s = approach.tick(ctx, calcFailed);
                if (s != null) {
                    return s;
                }
                session.openBlock(pending.furnace(), pending.kind().displayName, m -> m instanceof AbstractFurnaceMenu);
                phase = Phase.OPEN;
                return Step.stay();
            }
            case OPEN: {
                ContainerSession.Status st = session.tickOpen();
                if (st == ContainerSession.Status.FAILED) {
                    return Step.failure(session.failure());
                }
                if (st == ContainerSession.Status.READY) {
                    startCount = session.view().count(pending.output(), MenuView.PLAYER_STORAGE);
                    phase = Phase.WATCH;
                }
                return Step.stay();
            }
            case WATCH: {
                if (session.confirming() || ++watchTicks % 10 != 0) {
                    return Step.stay();
                }
                MenuView v = session.view();
                SlotView out = SmeltTask.slot(v, SlotRole.FURNACE_RESULT);
                SlotView in = SmeltTask.slot(v, SlotRole.FURNACE_INPUT);
                int ready = out.holds(pending.output()) ? out.count() : 0;
                boolean lit = session.menu() instanceof AbstractFurnaceMenu fm && fm.isLit();
                detail("cooking: " + (collected + ready) + "/" + pending.expected());
                if (ready > 0 && (collected + ready >= pending.expected() || ready >= 16 || in.isEmpty())) {
                    phase = Phase.TAKE;
                    return Step.stay();
                }
                if (in.isEmpty() && !lit) {
                    coldTicks += 10;
                    if (coldTicks > 60) {
                        endMessage = collected >= pending.expected() ? null : "the " + pending.kind().displayName
                                + " stopped (input used up or no fuel) after " + collected + "/" + pending.expected();
                        success = collected > 0;
                        phase = Phase.CLOSE;
                    }
                } else if (!in.isEmpty() && !lit) {
                    coldTicks += 10;
                    if (coldTicks > 100) {
                        endMessage = "the " + pending.kind().displayName + " ran out of fuel with " + in.count() + " items left";
                        success = false;
                        phase = Phase.CLOSE;
                    }
                } else {
                    coldTicks = 0;
                }
                return Step.stay();
            }
            case TAKE: {
                ContainerSession.Status st = session.run(MenuGoals.takeOutput(SmeltTask.slot(session.view(), SlotRole.FURNACE_RESULT).index(),
                        pending.output(), 2));
                if (st == ContainerSession.Status.FAILED) {
                    return Step.failure(session.failure());
                }
                if (st == ContainerSession.Status.DONE) {
                    collected = Math.max(0, session.view().count(pending.output(), MenuView.PLAYER_STORAGE) - startCount);
                    if (collected >= pending.expected()) {
                        success = true;
                        phase = Phase.CLOSE;
                    } else {
                        phase = Phase.WATCH;
                    }
                }
                return Step.stay();
            }
            case CLOSE: {
                if (session.close() != ContainerSession.Status.DONE) {
                    return Step.stay();
                }
                pctx.removePendingSmelt(pending);
                if (success && endMessage == null) {
                    return Step.success("collected " + collected + "x " + pending.output().shortName());
                }
                return success ? Step.success(endMessage) : Step.failure(endMessage == null ? "nothing collected" : endMessage);
            }
            default:
                return Step.failure("unknown phase");
        }
    }

    /** Inventory count of the output when the furnace was opened (collected = now - start). */
    private int startCount;

    @Override
    public void cancel() {
        session.closeNow();
    }
}
