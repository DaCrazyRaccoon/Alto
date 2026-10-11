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

package baritone.alto.supply;

import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoppingListTest {

    private static final ItemKey STONE = ItemKey.of("stone");
    private static final ItemKey GLASS = ItemKey.of("glass");
    private static final ItemKey DOOR = ItemKey.of("oak_door");

    @Test
    void splitsIntoCarriedStorageAndMissing() {
        ShoppingList l = ShoppingList.compute(ItemCounts.of("stone", 200, "glass", 20, "oak_door", 2),
                ItemCounts.of("stone", 50), ItemCounts.of("stone", 100, "glass", 64), k -> k.equals(DOOR));
        ShoppingList.Line stone = l.lines().stream().filter(x -> x.item().equals(STONE)).findFirst().orElseThrow();
        assertEquals(100, stone.fromStorage());
        assertEquals(50, stone.missing());
        ShoppingList.Line glass = l.lines().stream().filter(x -> x.item().equals(GLASS)).findFirst().orElseThrow();
        assertEquals(20, glass.fromStorage());
        assertEquals(0, glass.missing());
        ShoppingList.Line door = l.lines().stream().filter(x -> x.item().equals(DOOR)).findFirst().orElseThrow();
        assertEquals(2, door.missing());
        assertTrue(door.craftable());
        assertEquals(52, l.totalMissing());
        assertEquals(STONE, l.lines().get(0).item()); // most missing first
    }

    @Test
    void supplyRunFitsTheFreeSlots() {
        ShoppingList l = ShoppingList.compute(ItemCounts.of("stone", 640, "glass", 20), new ItemCounts(),
                ItemCounts.of("stone", 640, "glass", 64), k -> false);
        ItemCounts run = l.supplyRun(4, k -> 64);
        assertEquals(256, run.get(STONE)); // the biggest need first, 4 stacks fill the 4 slots
        assertEquals(0, run.get(GLASS));
        ItemCounts big = l.supplyRun(20, k -> 64);
        assertEquals(640, big.get(STONE));
        assertEquals(20, big.get(GLASS));
    }
}
