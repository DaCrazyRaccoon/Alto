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

package baritone.alto.container.mc;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.alto.container.Click;
import baritone.alto.container.MenuGoal;
import baritone.alto.container.MenuView;
import baritone.alto.core.Backoff;
import baritone.alto.core.Pacer;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Drives one menu from opening to closing. Tick-driven, client thread only.
 * <p>
 * Rules it enforces (research.md §2, upstream maintainer guidance):
 * <ul>
 *     <li>clicks only by menu slot through {@code MultiPlayerGameMode.handleInventoryMouseClick}, only while standing
 *     still, paced by {@link Pacer};</li>
 *     <li>after each click, wait a confirmation window (one round trip, longer under lag) before deciding again, so a
 *     server correction is seen; a click that changes nothing three times in a row fails the session (desync);</li>
 *     <li>opening is retried with doubling waits and a hard limit; an unexpected menu is closed, never used;</li>
 *     <li>never clicks while the chat screen is open; never closes a menu with an item on the cursor.</li>
 * </ul>
 */
public final class ContainerSession {

    public enum Status { WORKING, READY, DONE, FAILED }

    private enum State { IDLE, AIMING, OPENING, OPEN, CLOSED, FAILED }

    private final Alto alto;
    private final IPlayerContext ctx;
    private State state = State.IDLE;
    private BlockPos target;
    private net.minecraft.world.entity.Entity entityTarget;
    private Predicate<AbstractContainerMenu> expected = m -> true;
    private String label = "container";
    private int containerId = -1;
    private boolean ownsInventoryScreen;
    private int waitTicks;
    private int confirmWait;
    private int noProgress;
    private Click lastClick;
    private MenuView lastView;
    private String failure = "";
    private Backoff openBackoff;
    private int chatWaitTicks;

    public ContainerSession(Alto alto) {
        this.alto = alto;
        this.ctx = alto.ctx();
    }

    // ---------------------------------------------------------------------------------------------- opening

    /** Starts opening the block at {@code pos}. The caller must already stand within reach. */
    public void openBlock(BlockPos pos, String label, Predicate<AbstractContainerMenu> expected) {
        this.entityTarget = null;
        this.target = pos;
        this.label = label;
        this.expected = expected;
        this.state = State.AIMING;
        this.openBackoff = new Backoff(10, 80, Math.max(1, Baritone.settings().altoMaxRetries.value));
        this.waitTicks = 0;
    }

    /** Starts opening an entity's menu (a corpse, a chest minecart) with a normal right-click. Caller stands within reach. */
    public void openEntity(net.minecraft.world.entity.Entity entity, String label, Predicate<AbstractContainerMenu> expected) {
        openBlock(entity.blockPosition(), label, expected);
        this.entityTarget = entity;
    }

    /** Uses the player's own inventory menu (2x2 crafting grid). Optionally shows the inventory screen. */
    public void openInventory() {
        LocalPlayer p = ctx.player();
        this.label = "inventory";
        this.target = null;
        this.containerId = p.inventoryMenu.containerId;
        Minecraft mc = Minecraft.getInstance();
        if (Baritone.settings().altoShowScreens.value && mc.screen == null && !p.isCreative()) {
            mc.setScreen(new InventoryScreen(p));
            ownsInventoryScreen = true;
        }
        state = State.OPEN;
    }

    /**
     * Advances opening. READY once the expected menu is open and its contents arrived.
     */
    public Status tickOpen() {
        LocalPlayer p = ctx.player();
        if (p == null) {
            return fail("no player");
        }
        switch (state) {
            case OPEN:
                return menuStillOpen() ? Status.READY : fail(label + " was closed");
            case FAILED:
                return Status.FAILED;
            case AIMING:
                return tickAim(p);
            case OPENING:
                return tickWaitOpen(p);
            default:
                return fail("session not started");
        }
    }

    private Status tickAim(LocalPlayer p) {
        if (openBackoff.waiting()) {
            return Status.WORKING;
        }
        if (userScreenOpen()) {
            return Status.WORKING;
        }
        if (p.containerMenu != p.inventoryMenu) {
            // Something else is open (left over or opened by the user): close it first, never click in it.
            p.closeContainer();
            return Status.WORKING;
        }
        if (entityTarget != null) {
            return tickAimEntity(p);
        }
        double reach = ctx.playerController().getBlockReachDistance();
        Optional<Rotation> rot = RotationUtils.reachable(ctx, target, reach);
        if (rot.isEmpty()) {
            return fail("the " + label + " at " + target.toShortString() + " is out of reach");
        }
        alto.baritone().getLookBehavior().updateTarget(rot.get(), true);
        HitResult hit = ctx.objectMouseOver();
        if (!(hit instanceof BlockHitResult bhr) || !bhr.getBlockPos().equals(target)) {
            waitTicks++;
            if (waitTicks > 40) {
                return fail("could not aim at the " + label);
            }
            return Status.WORKING;
        }
        if (!stationary(p) || p.isShiftKeyDown()) {
            return Status.WORKING; // never sneak-click (Carry On would pick the container up)
        }
        if (!alto.pacer().ready(Pacer.Kind.CONTAINER_OPEN) || !alto.pacer().ready(Pacer.Kind.BLOCK_USE)) {
            return Status.WORKING;
        }
        int safe = baritone.alto.mc.McItems.safeHotbarSlot(p.getInventory());
        if (safe != p.getInventory().selected) {
            p.getInventory().selected = safe; // the held item must not interact with the container block
            return Status.WORKING;
        }
        alto.pacer().tryAcquire(Pacer.Kind.CONTAINER_OPEN);
        alto.pacer().tryAcquire(Pacer.Kind.BLOCK_USE);
        InteractionResult r = ctx.playerController().processRightClickBlock(p, ctx.world(), InteractionHand.MAIN_HAND, bhr);
        if (r.shouldSwing()) {
            p.swing(InteractionHand.MAIN_HAND);
        }
        alto.storage().recorder().expect(target);
        AltoLog.debug("use " + label + " at " + target.toShortString() + " -> " + r);
        state = State.OPENING;
        waitTicks = 0;
        return Status.WORKING;
    }

    private Status tickAimEntity(LocalPlayer p) {
        net.minecraft.world.entity.Entity e = entityTarget;
        if (e.isRemoved()) {
            return fail("the " + label + " is gone");
        }
        net.minecraft.world.phys.Vec3 center = e.getBoundingBox().getCenter();
        if (p.getEyePosition().distanceTo(center) > p.entityInteractionRange()) {
            return fail("the " + label + " is out of reach");
        }
        alto.baritone().getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(p.getEyePosition(), center, ctx.playerRotations()), true);
        HitResult hit = Minecraft.getInstance().hitResult;
        if (!(hit instanceof net.minecraft.world.phys.EntityHitResult ehr) || ehr.getEntity() != e) {
            if (++waitTicks > 40) {
                return fail("could not aim at the " + label);
            }
            return Status.WORKING;
        }
        if (!stationary(p) || p.isShiftKeyDown()) {
            return Status.WORKING;
        }
        if (!alto.pacer().ready(Pacer.Kind.CONTAINER_OPEN) || !alto.pacer().ready(Pacer.Kind.BLOCK_USE)) {
            return Status.WORKING;
        }
        int safe = baritone.alto.mc.McItems.safeHotbarSlot(p.getInventory());
        if (safe != p.getInventory().selected) {
            p.getInventory().selected = safe;
            return Status.WORKING;
        }
        alto.pacer().tryAcquire(Pacer.Kind.CONTAINER_OPEN);
        alto.pacer().tryAcquire(Pacer.Kind.BLOCK_USE);
        // Same order as a normal right-click on an entity (Minecraft.startUseItem): interactAt, then interact.
        net.minecraft.client.multiplayer.MultiPlayerGameMode gm = Minecraft.getInstance().gameMode;
        InteractionResult r = gm.interactAt(p, e, ehr, InteractionHand.MAIN_HAND);
        if (!r.consumesAction()) {
            r = gm.interact(p, e, InteractionHand.MAIN_HAND);
        }
        if (r.shouldSwing()) {
            p.swing(InteractionHand.MAIN_HAND);
        }
        AltoLog.debug("use " + label + " (entity) -> " + r);
        state = State.OPENING;
        waitTicks = 0;
        return Status.WORKING;
    }

    private Status tickWaitOpen(LocalPlayer p) {
        AbstractContainerMenu menu = p.containerMenu;
        waitTicks++;
        if (menu != p.inventoryMenu) {
            if (!expected.test(menu)) {
                String type = MenuReader.menuTypeId(menu);
                p.closeContainer();
                return fail("an unexpected menu opened (" + type + ") instead of the " + label);
            }
            if (menu.getStateId() > 0) { // contents arrived (the server raises the state id when it syncs)
                containerId = menu.containerId;
                state = State.OPEN;
                openBackoff.reset();
                AltoLog.debug("opened " + label + " id=" + containerId + " type=" + MenuReader.menuTypeId(menu));
                return Status.READY;
            }
        }
        int timeout = Baritone.settings().altoOpenTimeoutTicks.value + alto.lag().confirmTicks(Baritone.settings());
        if (waitTicks > timeout) {
            openBackoff.fail();
            if (openBackoff.exhausted()) {
                return fail("the " + label + " did not open after " + openBackoff.failures() + " tries");
            }
            AltoLog.debug("open timeout for " + label + ", retry " + openBackoff.failures());
            state = State.AIMING;
            waitTicks = 0;
        }
        return Status.WORKING;
    }

    // ---------------------------------------------------------------------------------------------- working

    /** Snapshot of the open menu. */
    public MenuView view() {
        LocalPlayer p = ctx.player();
        return MenuReader.read(p.containerMenu, p.getInventory());
    }

    public AbstractContainerMenu menu() {
        return ctx.player().containerMenu;
    }

    /** True while waiting for the server to confirm the last action. */
    public boolean confirming() {
        if (confirmWait > 0) {
            confirmWait--;
            return true;
        }
        return false;
    }

    /**
     * Makes progress on a goal: at most one click per call, paced and confirmed.
     */
    public Status run(MenuGoal goal) {
        if (state != State.OPEN) {
            return fail("the " + label + " is not open");
        }
        if (!menuStillOpen()) {
            return fail("the " + label + " was closed");
        }
        if (confirming() || userScreenOpen() || !stationary(ctx.player())) {
            return Status.WORKING;
        }
        MenuView v = view();
        if (lastClick != null && lastView != null) {
            if (sameContents(v, lastView)) {
                noProgress++;
                if (noProgress >= 3) {
                    return fail("click had no effect three times (" + lastClick + "); the menu is out of sync");
                }
            } else {
                noProgress = 0;
            }
        }
        MenuGoal.Decision d = goal.decide(v);
        if (d instanceof MenuGoal.Done done) {
            lastClick = null;
            lastView = null;
            AltoLog.debug(goal.describe() + ": " + done.summary());
            return Status.DONE;
        }
        if (d instanceof MenuGoal.Fail f) {
            lastClick = null;
            lastView = null;
            return fail(goal.describe() + ": " + f.reason());
        }
        Click c = ((MenuGoal.ClickNext) d).click();
        if (!alto.pacer().tryAcquire(Pacer.Kind.CONTAINER_CLICK)) {
            return Status.WORKING;
        }
        send(c);
        lastClick = c;
        lastView = v;
        return Status.WORKING;
    }

    /** One raw click (paced by the caller). */
    public void send(Click c) {
        LocalPlayer p = ctx.player();
        ctx.playerController().windowClick(p.containerMenu.containerId, c.slot(), c.button(), toVanilla(c), p);
        confirmWait = alto.lag().confirmTicks(Baritone.settings());
        AltoLog.debug("click " + c + " in " + label);
    }

    /**
     * Sends a recipe-book placement if pacing allows. Returns false when it has to wait.
     */
    public boolean placeRecipe(RecipeHolder<?> recipe, boolean all) {
        if (confirming() || userScreenOpen() || !alto.pacer().tryAcquire(Pacer.Kind.RECIPE_PLACE)) {
            return false;
        }
        Minecraft.getInstance().gameMode.handlePlaceRecipe(ctx.player().containerMenu.containerId, recipe, all);
        confirmWait = alto.lag().confirmTicks(Baritone.settings()) + 2;
        AltoLog.debug("place recipe " + recipe.id() + (all ? " (all)" : "") + " in " + label);
        return true;
    }

    // ---------------------------------------------------------------------------------------------- closing

    /**
     * Closes the menu. Puts any carried stack back first (never closes with an item on the cursor).
     * Returns DONE when closed.
     */
    public Status close() {
        LocalPlayer p = ctx.player();
        if (p == null || state == State.CLOSED || state == State.IDLE) {
            state = State.CLOSED;
            return Status.DONE;
        }
        if (state == State.OPEN && menuStillOpen() && target != null && entityTarget == null) {
            alto.storage().recorder().recordNow();
        }
        if (state == State.OPEN && menuStillOpen() && !p.containerMenu.getCarried().isEmpty()) {
            Status s = run(new MenuGoal() {
                @Override
                public MenuGoal.Decision decide(MenuView v) {
                    MenuGoal.Decision d = baritone.alto.container.MenuGoals.returnCarried(v);
                    return d == null ? MenuGoal.done("nothing carried") : d;
                }

                @Override
                public String describe() {
                    return "put back the carried stack";
                }
            });
            if (s == Status.WORKING) {
                return Status.WORKING;
            }
        }
        closeNow();
        return Status.DONE;
    }

    /** Immediate close for cancellation (best effort, no pacing). */
    public void closeNow() {
        LocalPlayer p = ctx.player();
        Minecraft mc = Minecraft.getInstance();
        if (p != null && state != State.CLOSED && state != State.IDLE) {
            if (p.containerMenu != p.inventoryMenu && (containerId < 0 || p.containerMenu.containerId == containerId)) {
                p.closeContainer();
            } else if (ownsInventoryScreen && mc.screen instanceof InventoryScreen) {
                p.closeContainer();
            }
        }
        ownsInventoryScreen = false;
        if (state != State.FAILED) {
            state = State.CLOSED;
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    public String failure() {
        return failure;
    }

    public boolean isOpen() {
        return state == State.OPEN && menuStillOpen();
    }

    private Status fail(String why) {
        failure = why;
        state = State.FAILED;
        return Status.FAILED;
    }

    private boolean menuStillOpen() {
        LocalPlayer p = ctx.player();
        if (p == null) {
            return false;
        }
        if (target == null) {
            return p.containerMenu == p.inventoryMenu; // inventory session
        }
        return p.containerMenu != p.inventoryMenu && p.containerMenu.containerId == containerId;
    }

    /** True while the user is typing in chat (wait, never click into their way). Logs once. */
    private boolean userScreenOpen() {
        if (Minecraft.getInstance().screen instanceof ChatScreen) {
            if (chatWaitTicks++ == 0) {
                AltoLog.step("Waiting while the chat is open.");
            }
            return true;
        }
        chatWaitTicks = 0;
        return false;
    }

    static boolean stationary(LocalPlayer p) {
        return p.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4;
    }

    private static boolean sameContents(MenuView a, MenuView b) {
        return a.slots().equals(b.slots()) && a.carriedCount() == b.carriedCount() && Objects.equals(a.carriedItem(), b.carriedItem());
    }

    private static ClickType toVanilla(Click c) {
        return switch (c.kind()) {
            case PICKUP -> ClickType.PICKUP;
            case QUICK_MOVE -> ClickType.QUICK_MOVE;
            case SWAP -> ClickType.SWAP;
            case THROW -> ClickType.THROW;
            case QUICK_CRAFT -> ClickType.QUICK_CRAFT;
            case PICKUP_ALL -> ClickType.PICKUP_ALL;
        };
    }
}
