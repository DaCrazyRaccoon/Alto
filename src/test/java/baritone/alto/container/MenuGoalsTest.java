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
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuGoalsTest {

    private static final ItemKey LOG = ItemKey.of("oak_log");
    private static final ItemKey COBBLE = ItemKey.of("cobblestone");
    private static final Set<SlotRole> STORAGE = MenuGoals.roles(SlotRole.STORAGE);

    @Test
    void takeMovesWholeStacksAndStopsWhenEnough() {
        MenuSim sim = MenuSim.chest().put(0, "oak_log", 64).put(1, "oak_log", 64).put(2, "oak_log", 10);
        MenuGoal.Decision d = sim.run(MenuGoals.take(LOG::equals, "oak_log", 70, STORAGE, false), 20);
        assertInstanceOf(MenuGoal.Done.class, d);
        int got = sim.view().count(LOG, MenuView.PLAYER_STORAGE);
        assertTrue(got >= 70 && got <= 74 + 64, "got " + got);
        // first the 64 stack (<= remaining), then the 10 stack (smallest overshoot)
        assertEquals(74, got);
        assertEquals(2, sim.clicks);
    }

    @Test
    void exactTakeSplitsTheLastStack() {
        MenuSim sim = MenuSim.chest().put(0, "oak_log", 64);
        MenuGoal.Decision d = sim.run(MenuGoals.take(LOG::equals, "oak_log", 5, STORAGE, true), 30);
        assertInstanceOf(MenuGoal.Done.class, d);
        assertEquals(5, sim.view().count(LOG, MenuView.PLAYER_STORAGE));
        assertEquals(59, sim.view().count(LOG, STORAGE));
        assertFalse(sim.view().carrying());
    }

    @Test
    void exactHalfOfAStackTakesTwoClicks() {
        // 8 of 16: right click picks up 8, one left click puts them into the inventory; the chest keeps its 8.
        MenuSim sim = MenuSim.chest().put(0, "oak_log", 16);
        MenuGoal.Decision d = sim.run(MenuGoals.take(LOG::equals, "oak_log", 8, STORAGE, true), 30);
        assertInstanceOf(MenuGoal.Done.class, d);
        assertEquals(8, sim.view().count(LOG, MenuView.PLAYER_STORAGE));
        assertEquals(8, sim.view().count(LOG, STORAGE));
        assertFalse(sim.view().carrying());
        assertEquals(2, sim.clicks);
    }

    @Test
    void exactNearlyAllPutsTheFewBack() {
        // 60 of 64: pick up all, put 4 back one by one, put the 60 into the inventory (6 clicks, not 60).
        MenuSim sim = MenuSim.chest().put(0, "oak_log", 64);
        MenuGoal.Decision d = sim.run(MenuGoals.take(LOG::equals, "oak_log", 60, STORAGE, true), 40);
        assertInstanceOf(MenuGoal.Done.class, d);
        assertEquals(60, sim.view().count(LOG, MenuView.PLAYER_STORAGE));
        assertEquals(4, sim.view().count(LOG, STORAGE));
        assertFalse(sim.view().carrying());
        assertTrue(sim.clicks <= 6, "clicks " + sim.clicks);
    }

    @Test
    void takeReportsWhenTheContainerRunsOut() {
        MenuSim sim = MenuSim.chest().put(3, "oak_log", 4);
        MenuGoal.Decision d = sim.run(MenuGoals.take(LOG::equals, "oak_log", 20, STORAGE, false), 10);
        MenuGoal.Done done = assertInstanceOf(MenuGoal.Done.class, d);
        assertTrue(done.summary().contains("no more"), done.summary());
        assertEquals(4, sim.view().count(LOG, MenuView.PLAYER_STORAGE));
    }

    @Test
    void takeFailsWhenTheInventoryIsFull() {
        MenuSim sim = MenuSim.chest().put(0, "oak_log", 64);
        for (int i = 27; i < 63; i++) {
            sim.put(i, "dirt", 64);
        }
        MenuGoal.Decision d = sim.run(MenuGoals.take(LOG::equals, "oak_log", 10, STORAGE, false), 5);
        assertEquals("inventory full", assertInstanceOf(MenuGoal.Fail.class, d).reason());
    }

    @Test
    void storeRespectsTheKeepPredicateAndPrefersMainInventory() {
        MenuSim sim = MenuSim.chest().put(27, "cobblestone", 64).put(28, "diamond_pickaxe", 1).put(54, "cobblestone", 32);
        MenuGoal goal = MenuGoals.store(k -> !k.id().contains("pickaxe"), "everything but tools", -1, STORAGE);
        MenuGoal.Decision d = sim.run(goal, 10);
        assertInstanceOf(MenuGoal.Done.class, d);
        assertEquals(96, sim.view().count(COBBLE, STORAGE));
        assertEquals(1, sim.view().count(ItemKey.of("diamond_pickaxe"), MenuView.PLAYER_STORAGE));
    }

    @Test
    void storeStopsCleanlyWhenTheChestIsFull() {
        MenuSim sim = MenuSim.chest();
        for (int i = 0; i < 27; i++) {
            sim.put(i, "dirt", 64);
        }
        sim.put(30, "cobblestone", 64);
        MenuGoal.Decision d = sim.run(MenuGoals.store(COBBLE::equals, "cobblestone", -1, STORAGE), 5);
        assertTrue(assertInstanceOf(MenuGoal.Done.class, d).summary().contains("container full"));
        assertEquals(0, sim.clicks);
    }

    @Test
    void fillSlotPlacesExactCounts() {
        MenuSim sim = MenuSim.craftingTable().put(10, "oak_log", 64);
        MenuGoal.Decision d = sim.run(MenuGoals.fillSlot(1, LOG, 3, false), 20);
        assertInstanceOf(MenuGoal.Done.class, d);
        assertEquals(3, sim.view().slot(1).count());
        assertEquals(61, sim.view().count(LOG, MenuView.PLAYER_STORAGE));
        assertFalse(sim.view().carrying());
    }

    @Test
    void fillSlotPlacesWholeSmallStacksInOneClick() {
        MenuSim sim = MenuSim.craftingTable().put(10, "oak_log", 5);
        sim.run(MenuGoals.fillSlot(1, LOG, 5, false), 20);
        assertEquals(5, sim.view().slot(1).count());
        assertEquals(2, sim.clicks); // pick up + put down
    }

    @Test
    void chestGridTakesOnePickUpEightRightClicksAndOnePutBack() {
        // The 8 planks of a chest: keep the stack in hand from slot to slot (was 3 clicks per slot).
        ItemKey planks = ItemKey.of("oak_planks");
        MenuSim sim = MenuSim.craftingTable().put(10, "oak_planks", 64);
        java.util.List<MenuGoal> goals = new java.util.ArrayList<>();
        for (int slot : new int[]{1, 2, 3, 4, 6, 7, 8, 9}) {
            goals.add(MenuGoals.fillSlot(slot, planks, 1, false, true));
        }
        goals.add(MenuGoals.putBackCarried());
        MenuGoal.Decision d = sim.run(MenuGoals.sequence(goals.toArray(new MenuGoal[0])), 40);
        assertInstanceOf(MenuGoal.Done.class, d);
        for (int slot : new int[]{1, 2, 3, 4, 6, 7, 8, 9}) {
            assertEquals(1, sim.view().slot(slot).count());
        }
        assertTrue(sim.view().slot(5).isEmpty());
        assertEquals(56, sim.view().count(planks, MenuView.PLAYER_STORAGE));
        assertFalse(sim.view().carrying());
        assertEquals(10, sim.clicks);
    }

    @Test
    void keptStackIsPutBackBeforeADifferentItem() {
        // torches: coal on top, stick below; the coal stack goes back before the sticks are picked up
        MenuSim sim = MenuSim.craftingTable().put(10, "coal", 10).put(11, "stick", 10);
        MenuGoal goal = MenuGoals.sequence(MenuGoals.fillSlot(2, ItemKey.of("coal"), 1, false, true),
                MenuGoals.fillSlot(5, ItemKey.of("stick"), 1, false, true), MenuGoals.putBackCarried());
        assertInstanceOf(MenuGoal.Done.class, sim.run(goal, 20));
        assertEquals(1, sim.view().slot(2).count());
        assertEquals(1, sim.view().slot(5).count());
        assertEquals(9, sim.view().count(ItemKey.of("coal"), MenuView.PLAYER_STORAGE));
        assertEquals(9, sim.view().count(ItemKey.of("stick"), MenuView.PLAYER_STORAGE));
        assertFalse(sim.view().carrying());
    }

    @Test
    void fillSlotRefusesForeignItems() {
        MenuSim sim = MenuSim.craftingTable().put(1, "dirt", 1).put(10, "oak_log", 5);
        assertInstanceOf(MenuGoal.Fail.class, sim.run(MenuGoals.fillSlot(1, LOG, 2, false), 5));
    }

    @Test
    void takeOutputChecksTheResultItem() {
        MenuSim sim = MenuSim.craftingTable().put(0, "spruce_planks", 4);
        MenuGoal.Decision d = sim.run(MenuGoals.takeOutput(0, ItemKey.of("oak_planks"), 4), 5);
        assertTrue(assertInstanceOf(MenuGoal.Fail.class, d).reason().contains("Polymorph"));
    }

    @Test
    void clearEmptiesTheGrid() {
        MenuSim sim = MenuSim.craftingTable().put(1, "oak_log", 3).put(5, "stick", 2);
        sim.run(MenuGoals.clear(MenuGoals.roles(SlotRole.CRAFT_GRID), "crafting grid"), 5);
        assertTrue(sim.view().withRole(SlotRole.CRAFT_GRID).stream().allMatch(SlotView::isEmpty));
        assertEquals(3, sim.view().count(LOG, MenuView.PLAYER_STORAGE));
    }

    @Test
    void goalsRecoverFromAnExternalChange() {
        // A mod (or lag correction) moves items between decisions: the goal just re-decides.
        MenuSim sim = MenuSim.chest().put(0, "oak_log", 64).put(1, "oak_log", 64);
        MenuGoal goal = MenuGoals.take(LOG::equals, "oak_log", 100, STORAGE, false);
        MenuGoal.ClickNext first = assertInstanceOf(MenuGoal.ClickNext.class, goal.decide(sim.view()));
        sim.apply(first.click());
        sim.put(1, "oak_log", 0); // someone else emptied slot 1
        MenuGoal.Decision d = sim.run(goal, 10);
        assertInstanceOf(MenuGoal.Done.class, d);
        assertEquals(64, sim.view().count(LOG, MenuView.PLAYER_STORAGE));
    }

    @Test
    void carriedItemsAreNeverDroppedOrLost() {
        MenuSim sim = MenuSim.chest().put(0, "oak_log", 10);
        sim.apply(Click.left(0, "test: pick up"));
        assertTrue(sim.view().carrying());
        MenuGoal.Decision d = sim.run(MenuGoals.store(COBBLE::equals, "cobblestone", -1, STORAGE), 5);
        assertInstanceOf(MenuGoal.Done.class, d);
        assertFalse(sim.view().carrying());
        assertEquals(10, sim.view().count(LOG, MenuView.PLAYER_STORAGE));
    }

    @Test
    void insertCountsWhatLeftTheInventoryEvenWhenTheSlotIsConsumed() {
        // A furnace burns fuel the moment it arrives: the fuel slot looks empty again right after the click.
        MenuSim sim = MenuSim.craftingTable().put(10, "coal", 5);
        MenuGoal goal = MenuGoals.insert(1, ItemKey.of("coal"), 2);
        int guard = 0;
        MenuGoal.Decision d;
        while ((d = goal.decide(sim.view())) instanceof MenuGoal.ClickNext n && guard++ < 20) {
            sim.apply(n.click());
            if (sim.view().slot(1).count() > 0) {
                sim.put(1, "coal", 0); // burned
            }
        }
        assertInstanceOf(MenuGoal.Done.class, d);
        assertEquals(3, sim.view().count(ItemKey.of("coal"), MenuView.PLAYER_STORAGE));
        assertFalse(sim.view().carrying());
    }
}
