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

package baritone.alto.container;

import baritone.alto.core.ItemKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static baritone.alto.container.MenuGoal.click;
import static baritone.alto.container.MenuGoal.done;
import static baritone.alto.container.MenuGoal.fail;

/**
 * The menu goals Alto uses. All are pure and unit tested against {@link MenuSim}.
 */
public final class MenuGoals {

    private MenuGoals() {
    }

    /** Clicks used for an exact partial amount are capped; above this, whole stacks are moved instead. */
    public static final int EXACT_CLICK_LIMIT = 16;

    // ------------------------------------------------------------------------------------------ helpers

    /** Put whatever is on the cursor into the player inventory (never drops anything). */
    public static MenuGoal.Decision returnCarried(MenuView v) {
        if (!v.carrying()) {
            return null;
        }
        SlotView best = null;
        for (SlotView s : v.withRoles(MenuView.PLAYER_STORAGE)) {
            if (s.holds(v.carriedItem()) && s.space() > 0) {
                best = s;
                break;
            }
        }
        if (best == null) {
            for (SlotView s : v.withRoles(MenuView.PLAYER_STORAGE)) {
                if (s.isEmpty()) {
                    best = s;
                    break;
                }
            }
        }
        if (best == null) {
            return fail("no room in the inventory to put down the carried " + v.carriedItem().shortName());
        }
        return click(Click.left(best.index(), "put carried items back"));
    }

    static int playerCount(MenuView v, Predicate<ItemKey> match) {
        return v.count(match, MenuView.PLAYER_STORAGE);
    }

    // ------------------------------------------------------------------------------------------ goals

    /**
     * Take at least {@code amount} matching items from the source roles into the player inventory using shift-clicks.
     * With {@code exact}, the last partial stack is split with single right-clicks (up to {@link #EXACT_CLICK_LIMIT}).
     */
    public static MenuGoal take(Predicate<ItemKey> match, String label, int amount, Set<SlotRole> from, boolean exact) {
        return new MenuGoal() {
            int baseline = -1;

            @Override
            public MenuGoal.Decision decide(MenuView v) {
                int have = playerCount(v, match);
                if (baseline < 0) {
                    baseline = have;
                }
                int moved = have - baseline;
                int remaining = amount - moved;
                if (v.carrying()) {
                    if (exact && match.test(v.carriedItem()) && remaining > 0) {
                        // Exact mode, carrying c and needing r (c >= r is the normal case): whichever is fewer clicks,
                        // single items into the inventory, or single items back into the container, then the rest at once.
                        int c = v.carriedCount();
                        SlotView target = targetFor(v, v.carriedItem());
                        if (target == null) {
                            return fail("inventory full");
                        }
                        if (c <= remaining) {
                            return click(Click.left(target.index(), "put " + c + " " + label + " into the inventory"));
                        }
                        SlotView back = sourceSlotFor(v, v.carriedItem(), from);
                        if (remaining <= c - remaining || back == null) {
                            return click(Click.right(target.index(), "place 1 " + label));
                        }
                        return click(Click.right(back.index(), "put 1 " + label + " back"));
                    }
                    if (exact && match.test(v.carriedItem())) {
                        // Done: put the rest back where it came from.
                        SlotView back = sourceSlotFor(v, v.carriedItem(), from);
                        if (back != null) {
                            return click(Click.left(back.index(), "return the rest of " + label));
                        }
                    }
                    return returnCarried(v);
                }
                if (remaining <= 0) {
                    return done("took " + moved + "x " + label);
                }
                List<SlotView> sources = new ArrayList<>();
                for (SlotView s : v.withRoles(from)) {
                    if (!s.isEmpty() && match.test(s.item())) {
                        sources.add(s);
                    }
                }
                if (sources.isEmpty()) {
                    return done(moved > 0 ? "took " + moved + "x " + label + " (no more in the container)" : "no " + label + " in the container");
                }
                // Prefer the largest stack that does not overshoot; otherwise the smallest one.
                SlotView pick = sources.stream().filter(s -> s.count() <= remaining).max(Comparator.comparingInt(SlotView::count))
                        .orElse(sources.stream().min(Comparator.comparingInt(SlotView::count)).orElseThrow());
                if (v.spaceFor(pick.item(), pick.maxStack(), MenuView.PLAYER_STORAGE) <= 0) {
                    return fail("inventory full");
                }
                if (exact && pick.count() > remaining) {
                    // Pick up half (right click) or all (left click), whichever leaves fewer clicks to split exactly.
                    int all = pick.count();
                    int half = (all + 1) / 2;
                    int costAll = splitClicks(all, remaining);
                    int costHalf = half >= remaining ? splitClicks(half, remaining) : Integer.MAX_VALUE;
                    if (Math.min(costAll, costHalf) <= EXACT_CLICK_LIMIT) {
                        return costHalf <= costAll
                                ? click(Click.right(pick.index(), "pick up half of the " + label))
                                : click(Click.left(pick.index(), "pick up " + label + " to split"));
                    }
                }
                return click(Click.shift(pick.index(), "take " + label));
            }

            @Override
            public String describe() {
                return "take " + amount + "x " + label;
            }
        };
    }

    /** Clicks to end up with exactly {@code need} of {@code carried} in the inventory (single clicks plus one). */
    static int splitClicks(int carried, int need) {
        return carried == need ? 1 : Math.min(need, carried - need) + 1;
    }

    /**
     * Move matching items from the player inventory into the container's storage slots (shift-clicks).
     * {@code amount < 0} means "all of them".
     */
    public static MenuGoal store(Predicate<ItemKey> match, String label, int amount, Set<SlotRole> into) {
        return new MenuGoal() {
            int baseline = -1;

            @Override
            public MenuGoal.Decision decide(MenuView v) {
                MenuGoal.Decision ret = returnCarried(v);
                if (ret != null) {
                    return ret;
                }
                int have = playerCount(v, match);
                if (baseline < 0) {
                    baseline = have;
                }
                int moved = baseline - have;
                if (amount >= 0 && moved >= amount) {
                    return done("stored " + moved + "x " + label);
                }
                // Main inventory first, hotbar last (the hotbar holds what the job is using).
                List<SlotView> candidates = new ArrayList<>();
                for (SlotView s : v.withRole(SlotRole.PLAYER_MAIN)) {
                    if (!s.isEmpty() && match.test(s.item())) {
                        candidates.add(s);
                    }
                }
                for (SlotView s : v.withRole(SlotRole.PLAYER_HOTBAR)) {
                    if (!s.isEmpty() && match.test(s.item())) {
                        candidates.add(s);
                    }
                }
                for (SlotView s : candidates) {
                    if (v.spaceFor(s.item(), s.maxStack(), into) > 0) {
                        return click(Click.shift(s.index(), "store " + s.item().shortName()));
                    }
                }
                if (candidates.isEmpty()) {
                    return done(moved > 0 ? "stored " + moved + "x " + label : "nothing to store");
                }
                return done("container full (stored " + moved + "x " + label + ")");
            }

            @Override
            public String describe() {
                return "store " + (amount < 0 ? "all" : amount + "x") + " " + label;
            }
        };
    }

    /**
     * Put exactly {@code target} items of {@code item} into slot {@code slot} (crafting grid, furnace input/fuel).
     * Whole stacks are placed with a left click when they fit; the remainder with single right clicks.
     * With {@code allowOvershoot}, a final partial amount above {@link #EXACT_CLICK_LIMIT} places a whole stack instead.
     */
    public static MenuGoal fillSlot(int slot, ItemKey item, int target, boolean allowOvershoot) {
        return fillSlot(slot, item, target, allowOvershoot, false);
    }

    /**
     * Like {@link #fillSlot(int, ItemKey, int, boolean)}; with {@code keepCarried} the rest of the stack stays on the
     * cursor when the slot is full, so the next slot of the same item needs only one more click (end the sequence
     * with {@link #putBackCarried()}). A crafting grid of 8 planks: 1 pick-up + 8 right clicks + 1 put-back.
     */
    public static MenuGoal fillSlot(int slot, ItemKey item, int target, boolean allowOvershoot, boolean keepCarried) {
        return new MenuGoal() {
            @Override
            public MenuGoal.Decision decide(MenuView v) {
                SlotView dest = v.slot(slot);
                if (!dest.isEmpty() && !dest.item().equals(item)) {
                    return fail("slot " + slot + " holds " + dest.item().shortName() + ", expected " + item.shortName());
                }
                int have = dest.isEmpty() ? 0 : dest.count();
                int need = target - have;
                if (v.carrying()) {
                    if (need <= 0 && keepCarried && v.carriedItem().equals(item)) {
                        return done("slot " + slot + " has " + have + "x " + item.shortName() + " (still holding the rest)");
                    }
                    if (!v.carriedItem().equals(item) || need <= 0) {
                        return returnCarried(v);
                    }
                    if (v.carriedCount() <= need || allowOvershoot && need > EXACT_CLICK_LIMIT) {
                        return click(Click.left(slot, "place " + item.shortName()));
                    }
                    return click(Click.right(slot, "place 1 " + item.shortName()));
                }
                if (need <= 0) {
                    return done("slot " + slot + " has " + have + "x " + item.shortName());
                }
                SlotView src = null;
                for (SlotView s : v.withRoles(MenuView.PLAYER_STORAGE)) {
                    if (s.holds(item) && (src == null || s.count() > src.count())) {
                        src = s;
                    }
                }
                if (src == null) {
                    return fail("no " + item.shortName() + " left in the inventory");
                }
                return click(Click.left(src.index(), "pick up " + item.shortName()));
            }

            @Override
            public String describe() {
                return "put " + target + "x " + item.shortName() + " into slot " + slot;
            }
        };
    }

    /**
     * Put {@code count} items into slot {@code slot}, counting what left the inventory rather than what the slot
     * shows: a furnace burns fuel (and eventually input) as soon as it arrives, so its slot can look empty again.
     * Whole stacks are placed with a left click when they fit; the remainder with single right clicks.
     */
    public static MenuGoal insert(int slot, ItemKey item, int count) {
        return new MenuGoal() {
            int baseline = -1;

            @Override
            public MenuGoal.Decision decide(MenuView v) {
                int inInventory = v.count(item, MenuView.PLAYER_STORAGE) + (v.carrying() && v.carriedItem().equals(item) ? v.carriedCount() : 0);
                if (baseline < 0) {
                    baseline = inInventory;
                }
                int placed = baseline - inInventory;
                int need = count - placed;
                SlotView dest = v.slot(slot);
                if (!dest.isEmpty() && !dest.item().equals(item)) {
                    return fail("slot " + slot + " holds " + dest.item().shortName() + ", expected " + item.shortName());
                }
                if (v.carrying()) {
                    if (!v.carriedItem().equals(item) || need <= 0 || dest.space() <= 0) {
                        return returnCarried(v);
                    }
                    if (v.carriedCount() <= need) {
                        return click(Click.left(slot, "put " + item.shortName()));
                    }
                    return click(Click.right(slot, "put 1 " + item.shortName()));
                }
                if (need <= 0) {
                    return done("put " + placed + "x " + item.shortName() + " into slot " + slot);
                }
                if (dest.space() <= 0) {
                    return fail("slot " + slot + " is full");
                }
                SlotView src = null;
                for (SlotView s : v.withRoles(MenuView.PLAYER_STORAGE)) {
                    if (s.holds(item) && (src == null || s.count() > src.count())) {
                        src = s;
                    }
                }
                if (src == null) {
                    return fail("no " + item.shortName() + " left in the inventory (" + placed + "/" + count + " put in)");
                }
                return click(Click.left(src.index(), "pick up " + item.shortName()));
            }

            @Override
            public String describe() {
                return "put " + count + "x " + item.shortName() + " into slot " + slot;
            }
        };
    }

    /** Shift-click an output slot until it is empty, checking that it holds what we expect. */
    public static MenuGoal takeOutput(int slot, ItemKey expected, int maxClicks) {
        return new MenuGoal() {
            int clicks;

            @Override
            public MenuGoal.Decision decide(MenuView v) {
                MenuGoal.Decision ret = returnCarried(v);
                if (ret != null) {
                    return ret;
                }
                SlotView out = v.slot(slot);
                if (out.isEmpty()) {
                    return done("output taken");
                }
                if (!out.item().equals(expected)) {
                    return fail("output slot holds " + out.item().shortName() + " instead of " + expected.shortName()
                            + " (a mod such as Polymorph may have chosen a different recipe)");
                }
                if (clicks >= maxClicks) {
                    return done("output left in slot after " + clicks + " clicks");
                }
                if (v.spaceFor(out.item(), out.maxStack(), MenuView.PLAYER_STORAGE) < out.count()) {
                    return fail("inventory full");
                }
                clicks++;
                return click(Click.shift(slot, "take " + expected.shortName()));
            }

            @Override
            public String describe() {
                return "take " + expected.shortName() + " from slot " + slot;
            }
        };
    }

    /** Shift-click every non-empty slot of the given roles back into the player inventory. */
    public static MenuGoal clear(Set<SlotRole> roles, String label) {
        return new MenuGoal() {
            @Override
            public MenuGoal.Decision decide(MenuView v) {
                MenuGoal.Decision ret = returnCarried(v);
                if (ret != null) {
                    return ret;
                }
                for (SlotView s : v.withRoles(roles)) {
                    if (!s.isEmpty()) {
                        if (v.spaceFor(s.item(), s.maxStack(), MenuView.PLAYER_STORAGE) <= 0) {
                            return fail("inventory full while emptying the " + label);
                        }
                        return click(Click.shift(s.index(), "empty " + label));
                    }
                }
                return done(label + " empty");
            }

            @Override
            public String describe() {
                return "empty the " + label;
            }
        };
    }

    /**
     * Make sure {@code item} is on the hotbar: swap it in from the main inventory (number-key swap) if needed.
     * The hotbar slot used is an empty one, else one whose item {@code replaceable} allows (never tools in use).
     */
    public static MenuGoal toHotbar(ItemKey item, Predicate<ItemKey> replaceable) {
        return new MenuGoal() {
            @Override
            public MenuGoal.Decision decide(MenuView v) {
                MenuGoal.Decision ret = returnCarried(v);
                if (ret != null) {
                    return ret;
                }
                for (SlotView s : v.withRole(SlotRole.PLAYER_HOTBAR)) {
                    if (s.holds(item)) {
                        return done(item.shortName() + " is on the hotbar");
                    }
                }
                SlotView source = null;
                for (SlotView s : v.withRole(SlotRole.PLAYER_MAIN)) {
                    if (s.holds(item) && (source == null || s.count() > source.count())) {
                        source = s;
                    }
                }
                if (source == null) {
                    return fail("no " + item.shortName() + " in the inventory");
                }
                SlotView target = null;
                for (SlotView s : v.withRole(SlotRole.PLAYER_HOTBAR)) {
                    if (s.isEmpty()) {
                        target = s;
                        break;
                    }
                }
                if (target == null) {
                    for (SlotView s : v.withRole(SlotRole.PLAYER_HOTBAR)) {
                        if (replaceable.test(s.item())) {
                            target = s;
                            break;
                        }
                    }
                }
                if (target == null || target.inventoryIndex() < 0 || target.inventoryIndex() > 8) {
                    return fail("no free hotbar slot for " + item.shortName());
                }
                return click(Click.swap(source.index(), target.inventoryIndex(), "move " + item.shortName() + " to the hotbar"));
            }

            @Override
            public String describe() {
                return "put " + item.shortName() + " on the hotbar";
            }
        };
    }

    /** Runs goals one after another; fails as soon as one fails. */
    /** Puts whatever is on the cursor back into the inventory (the end of a sequence of keep-carried fills). */
    public static MenuGoal putBackCarried() {
        return new MenuGoal() {
            @Override
            public MenuGoal.Decision decide(MenuView v) {
                MenuGoal.Decision ret = returnCarried(v);
                return ret != null ? ret : done("nothing in hand");
            }

            @Override
            public String describe() {
                return "put the carried items back";
            }
        };
    }

    public static MenuGoal sequence(MenuGoal... goals) {
        return new MenuGoal() {
            int index;

            @Override
            public MenuGoal.Decision decide(MenuView v) {
                while (index < goals.length) {
                    MenuGoal.Decision d = goals[index].decide(v);
                    if (d instanceof MenuGoal.Done) {
                        index++;
                        continue;
                    }
                    return d;
                }
                return done("all steps done");
            }

            @Override
            public String describe() {
                return index < goals.length ? goals[index].describe() : "done";
            }
        };
    }

    // ------------------------------------------------------------------------------------------ private

    private static SlotView targetFor(MenuView v, ItemKey item) {
        for (SlotView s : v.withRoles(MenuView.PLAYER_STORAGE)) {
            if (s.holds(item) && s.space() > 0) {
                return s;
            }
        }
        for (SlotView s : v.withRoles(MenuView.PLAYER_STORAGE)) {
            if (s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    private static SlotView sourceSlotFor(MenuView v, ItemKey item, Set<SlotRole> from) {
        for (SlotView s : v.withRoles(from)) {
            if (s.isEmpty() || s.holds(item) && s.space() > 0) {
                return s;
            }
        }
        return null;
    }

    public static Set<SlotRole> roles(SlotRole... r) {
        Set<SlotRole> s = EnumSet.noneOf(SlotRole.class);
        s.addAll(List.of(r));
        return s;
    }
}
