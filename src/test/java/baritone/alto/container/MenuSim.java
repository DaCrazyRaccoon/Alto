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
import java.util.List;

/**
 * Test double for a container menu: applies vanilla's PICKUP and QUICK_MOVE rules to a mutable copy of a
 * {@link MenuView}. QUICK_MOVE follows the vanilla chest rule (container to player fills from the hotbar end,
 * player to container fills from the first slot, merging into equal stacks before empty slots).
 */
final class MenuSim {

    private final List<SlotView> slots;
    private ItemKey carried = ItemKey.EMPTY;
    private int carriedCount;
    private int stateId = 1;
    int clicks;

    MenuSim(List<SlotView> slots) {
        this.slots = new ArrayList<>(slots);
    }

    /** A 27-slot chest above the 36 player slots (vanilla generic_9x3 layout). */
    static MenuSim chest() {
        List<SlotView> s = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            s.add(SlotView.empty(i, SlotRole.STORAGE, -1));
        }
        for (int i = 0; i < 27; i++) {
            s.add(SlotView.empty(27 + i, SlotRole.PLAYER_MAIN, 9 + i));
        }
        for (int i = 0; i < 9; i++) {
            s.add(SlotView.empty(54 + i, SlotRole.PLAYER_HOTBAR, i));
        }
        return new MenuSim(s);
    }

    /** Vanilla crafting table: 0 result, 1-9 grid, 10-36 main, 37-45 hotbar. */
    static MenuSim craftingTable() {
        List<SlotView> s = new ArrayList<>();
        s.add(SlotView.empty(0, SlotRole.CRAFT_RESULT, -1));
        for (int i = 1; i <= 9; i++) {
            s.add(SlotView.empty(i, SlotRole.CRAFT_GRID, -1));
        }
        for (int i = 0; i < 27; i++) {
            s.add(SlotView.empty(10 + i, SlotRole.PLAYER_MAIN, 9 + i));
        }
        for (int i = 0; i < 9; i++) {
            s.add(SlotView.empty(37 + i, SlotRole.PLAYER_HOTBAR, i));
        }
        return new MenuSim(s);
    }

    MenuSim put(int index, String item, int count) {
        SlotView old = slots.get(index);
        slots.set(index, new SlotView(index, old.role(), ItemKey.of(item), count, 64, old.inventoryIndex()));
        return this;
    }

    MenuView view() {
        return new MenuView(1, stateId, "test", "sim", slots, carried, carriedCount);
    }

    void apply(Click c) {
        clicks++;
        stateId++;
        switch (c.kind()) {
            case PICKUP -> pickup(c.slot(), c.button());
            case QUICK_MOVE -> quickMove(c.slot());
            default -> throw new UnsupportedOperationException(c.kind().toString());
        }
    }

    /** Runs a goal until it is done or fails; returns the final decision. */
    MenuGoal.Decision run(MenuGoal goal, int maxClicks) {
        for (int i = 0; i < maxClicks; i++) {
            MenuGoal.Decision d = goal.decide(view());
            if (d instanceof MenuGoal.ClickNext n) {
                apply(n.click());
            } else {
                return d;
            }
        }
        throw new AssertionError("goal did not finish within " + maxClicks + " clicks: " + goal.describe());
    }

    private void set(int index, ItemKey item, int count) {
        SlotView old = slots.get(index);
        if (count <= 0) {
            slots.set(index, SlotView.empty(index, old.role(), old.inventoryIndex()));
        } else {
            slots.set(index, new SlotView(index, old.role(), item, count, 64, old.inventoryIndex()));
        }
    }

    private void pickup(int index, int button) {
        SlotView s = slots.get(index);
        boolean outputOnly = s.role().isOutput();
        if (carriedCount == 0) {
            if (s.isEmpty()) {
                return;
            }
            int take = button == 0 ? s.count() : (s.count() + 1) / 2;
            carried = s.item();
            carriedCount = take;
            set(index, s.item(), s.count() - take);
            return;
        }
        if (outputOnly) {
            return;
        }
        if (s.isEmpty() || s.item().equals(carried)) {
            int room = 64 - s.count();
            int give = Math.min(room, button == 0 ? carriedCount : 1);
            set(index, carried, s.count() + give);
            carriedCount -= give;
            if (carriedCount == 0) {
                carried = ItemKey.EMPTY;
            }
        } else if (button == 0) {
            ItemKey swapItem = s.item();
            int swapCount = s.count();
            set(index, carried, carriedCount);
            carried = swapItem;
            carriedCount = swapCount;
        }
    }

    private void quickMove(int index) {
        SlotView s = slots.get(index);
        if (s.isEmpty()) {
            return;
        }
        boolean toPlayer = !s.role().player;
        List<SlotView> targets = new ArrayList<>();
        for (SlotView t : slots) {
            if (toPlayer ? t.role().player && t.role() != SlotRole.PLAYER_ARMOR && t.role() != SlotRole.PLAYER_OFFHAND
                    : t.role() == SlotRole.STORAGE) {
                targets.add(t);
            }
        }
        if (toPlayer) {
            java.util.Collections.reverse(targets);
        }
        int left = s.count();
        for (SlotView t : targets) {
            SlotView cur = slots.get(t.index());
            if (!cur.isEmpty() && cur.item().equals(s.item()) && cur.count() < 64) {
                int give = Math.min(left, 64 - cur.count());
                set(t.index(), s.item(), cur.count() + give);
                left -= give;
            }
        }
        for (SlotView t : targets) {
            SlotView cur = slots.get(t.index());
            if (left > 0 && cur.isEmpty()) {
                set(t.index(), s.item(), left);
                left = 0;
            }
        }
        set(index, s.item(), left);
    }
}
