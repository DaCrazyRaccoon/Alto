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

import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Immutable snapshot of an open menu: every slot with its role and contents, plus the stack on the cursor.
 * Built by {@code MenuReader} from the live {@code AbstractContainerMenu}; built by hand in tests.
 *
 * @param containerId  the menu's window id (0 = player inventory)
 * @param stateId      the menu's state id (raised by the server on every change it sends)
 * @param menuType     registry id of the menu type ({@code minecraft:generic_9x3}); "minecraft:player" for id 0
 * @param adapter      id of the adapter that classified the slots
 * @param slots        all slots, indexed by {@link SlotView#index()}
 * @param carriedItem  item on the cursor
 * @param carriedCount count on the cursor
 */
public record MenuView(int containerId, int stateId, String menuType, String adapter, List<SlotView> slots,
                       ItemKey carriedItem, int carriedCount) {

    public static final Set<SlotRole> PLAYER_STORAGE = EnumSet.of(SlotRole.PLAYER_MAIN, SlotRole.PLAYER_HOTBAR);

    public MenuView {
        slots = List.copyOf(slots);
    }

    public SlotView slot(int index) {
        return slots.get(index);
    }

    public boolean carrying() {
        return carriedCount > 0 && !carriedItem.isEmpty();
    }

    public List<SlotView> withRole(SlotRole... roles) {
        Set<SlotRole> set = EnumSet.noneOf(SlotRole.class);
        set.addAll(List.of(roles));
        return withRoles(set);
    }

    public List<SlotView> withRoles(Set<SlotRole> roles) {
        List<SlotView> out = new ArrayList<>();
        for (SlotView s : slots) {
            if (roles.contains(s.role())) {
                out.add(s);
            }
        }
        return out;
    }

    /** Total count of items matching {@code match} in slots with the given roles. */
    public int count(Predicate<ItemKey> match, Set<SlotRole> roles) {
        int n = 0;
        for (SlotView s : slots) {
            if (roles.contains(s.role()) && !s.isEmpty() && match.test(s.item())) {
                n += s.count();
            }
        }
        return n;
    }

    public int count(ItemKey item, Set<SlotRole> roles) {
        return count(item::equals, roles);
    }

    /** Contents of the slots with the given roles. */
    public ItemCounts contents(Set<SlotRole> roles) {
        ItemCounts c = new ItemCounts();
        for (SlotView s : slots) {
            if (roles.contains(s.role()) && !s.isEmpty()) {
                c.add(s.item(), s.count());
            }
        }
        return c;
    }

    /** How many more of {@code item} fit into slots of the given roles (stacking + empty slots). */
    public int spaceFor(ItemKey item, int maxStack, Set<SlotRole> roles) {
        int n = 0;
        for (SlotView s : slots) {
            if (!roles.contains(s.role())) {
                continue;
            }
            if (s.isEmpty()) {
                n += maxStack;
            } else if (s.item().equals(item)) {
                n += Math.max(0, Math.min(maxStack, s.maxStack()) - s.count());
            }
        }
        return n;
    }

    public int emptySlots(Set<SlotRole> roles) {
        int n = 0;
        for (SlotView s : slots) {
            if (roles.contains(s.role()) && s.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    public boolean hasRole(SlotRole role) {
        for (SlotView s : slots) {
            if (s.role() == role) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "MenuView[" + menuType + " id=" + containerId + " state=" + stateId + " slots=" + slots.size()
                + (carrying() ? " carried=" + carriedCount + "x " + carriedItem.shortName() : "") + "]";
    }
}
