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

package baritone.alto.planner;

import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import baritone.alto.planner.PlanStep.StationAction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

    private static final RecipeBook BOOK = VanillaFixtures.book();

    private static Plan plan(PlannerOptions o, WorldModel w, String item, int count) {
        return new Planner(BOOK, o).plan(ItemKey.of(item), count, w);
    }

    private static Plan plan(WorldModel w, String item, int count) {
        return plan(new PlannerOptions(), w, item, count);
    }

    private static String steps(Plan p) {
        List<String> s = new ArrayList<>();
        for (PlanStep step : p.steps()) {
            s.add(step.describe());
        }
        return String.join(" | ", s);
    }

    // ------------------------------------------------------------------------------- the planks example

    @Test
    void planksFromLogsUseTheInventoryGridByDefault() {
        WorldModel w = VanillaFixtures.world().inventory("oak_log", 2).build();
        Plan p = plan(w, "oak_planks", 8);
        assertTrue(p.isComplete(), p.describe().toString());
        assertEquals(1, p.steps().size(), steps(p));
        PlanStep.Craft craft = assertInstanceOf(PlanStep.Craft.class, p.steps().get(0));
        assertEquals("minecraft:oak_planks", craft.recipe().id());
        assertEquals(2, craft.crafts());
        assertEquals(StationKind.INVENTORY_GRID, craft.station());
        assertEquals(ItemCounts.of("oak_log", 2), p.fromInventory());
    }

    @Test
    void preferredRecipeWinsWhenCostIsClose() {
        WorldModel w = VanillaFixtures.world().inventory("iron_block", 1).inventory("iron_nugget", 9)
                .station(StationKind.CRAFTING_TABLE, "overworld|5,64,5", 4).build();
        Plan normal = plan(w, "iron_ingot", 1);
        assertTrue(normal.isComplete(), normal.describe().toString());
        assertEquals(ItemCounts.of("iron_block", 1), normal.fromInventory(), steps(normal)); // cheapest without a preference
        PlannerOptions o = new PlannerOptions();
        o.preferredRecipes = java.util.Map.of(ItemKey.of("iron_ingot"), "minecraft:iron_ingot_from_nuggets");
        Plan preferred = plan(o, w, "iron_ingot", 1);
        assertTrue(preferred.isComplete(), preferred.describe().toString());
        assertEquals(ItemCounts.of("iron_nugget", 9), preferred.fromInventory(), steps(preferred));
    }

    @Test
    void preferredRecipeNeverBeatsAvailableItems() {
        WorldModel w = VanillaFixtures.world().inventory("iron_block", 1).build();
        PlannerOptions o = new PlannerOptions();
        o.preferredRecipes = java.util.Map.of(ItemKey.of("iron_ingot"), "minecraft:iron_ingot_from_nuggets");
        Plan p = plan(o, w, "iron_ingot", 1);
        assertTrue(p.isComplete(), p.describe().toString());
        assertEquals(ItemCounts.of("iron_block", 1), p.fromInventory(), steps(p));
    }

    @Test
    void alreadyHavingTheItemNeedsNoSteps() {
        WorldModel w = VanillaFixtures.world().inventory("oak_planks", 10).inventory("oak_log", 5).build();
        Plan p = plan(w, "oak_planks", 8);
        assertTrue(p.isComplete());
        assertTrue(p.isEmpty(), steps(p));
    }

    @Test
    void forcedStationUsesAKnownTable() {
        PlannerOptions o = new PlannerOptions();
        o.alwaysUseCraftingStation = true;
        WorldModel w = VanillaFixtures.world().inventory("oak_log", 2)
                .station(StationKind.CRAFTING_TABLE, "overworld|5,64,5", 6).build();
        Plan p = plan(o, w, "oak_planks", 8);
        assertTrue(p.isComplete());
        assertEquals(2, p.steps().size(), steps(p));
        PlanStep.Station use = assertInstanceOf(PlanStep.Station.class, p.steps().get(0));
        assertEquals(StationAction.USE_EXISTING, use.action());
        assertEquals(StationKind.CRAFTING_TABLE, ((PlanStep.Craft) p.steps().get(1)).station());
    }

    @Test
    void forcedStationBootstrapsPlacesAndPicksUpATable() {
        PlannerOptions o = new PlannerOptions();
        o.alwaysUseCraftingStation = true;
        WorldModel w = VanillaFixtures.world().inventory("oak_log", 3).build();
        Plan p = plan(o, w, "oak_planks", 8);
        assertTrue(p.isComplete(), p.describe().toString());
        // craft 4 planks in the grid -> craft a table in the grid -> place it -> craft 8 planks on it -> pick it up
        assertEquals("Craft 4x oak_planks (1 craft, inventory grid) | Craft 1x crafting_table (1 craft, inventory grid)"
                + " | Place a crafting table | Craft 8x oak_planks (2 crafts, crafting table) | Pick up the crafting table", steps(p));
        assertEquals(ItemCounts.of("oak_log", 3), p.fromInventory());
    }

    @Test
    void threeByThreeRecipeNeedsATableEvenByDefault() {
        WorldModel w = VanillaFixtures.world().inventory("oak_log", 3).build();
        Plan p = plan(w, "chest", 1);
        assertTrue(p.isComplete(), p.describe().toString());
        String s = steps(p);
        assertTrue(s.contains("Craft 1x crafting_table (1 craft, inventory grid)"), s);
        assertTrue(s.contains("Place a crafting table"), s);
        assertTrue(s.contains("Craft 1x chest (1 craft, crafting table)"), s);
        assertTrue(s.endsWith("Pick up the crafting table"), s);
        assertEquals(ItemCounts.of("oak_log", 3), p.fromInventory());
    }

    @Test
    void pickupCanBeTurnedOff() {
        PlannerOptions o = new PlannerOptions();
        o.pickUpPlacedStations = false;
        WorldModel w = VanillaFixtures.world().inventory("oak_log", 3).build();
        Plan p = plan(o, w, "chest", 1);
        assertFalse(steps(p).contains("Pick up"), steps(p));
    }

    @Test
    void carriedTableIsPlacedInsteadOfCrafted() {
        WorldModel w = VanillaFixtures.world().inventory("oak_planks", 8).inventory("crafting_table", 1).build();
        Plan p = plan(w, "chest", 1);
        assertEquals("Place a crafting table | Craft 1x chest (1 craft, crafting table) | Pick up the crafting table", steps(p));
    }

    // ------------------------------------------------------------------------------- preferences

    @Test
    void nearStorageIsPreferredOverCrafting() {
        WorldModel w = VanillaFixtures.world().inventory("oak_log", 2)
                .storage("c1", "chest at 3 64 3", 10, ItemCounts.of("oak_planks", 64)).build();
        Plan p = plan(w, "oak_planks", 8);
        assertEquals("Fetch 8x oak_planks from chest at 3 64 3", steps(p));
    }

    @Test
    void farStorageLosesToCraftingFromInventory() {
        WorldModel w = VanillaFixtures.world().inventory("oak_log", 2)
                .storage("c1", "chest far away", 500, ItemCounts.of("oak_planks", 64)).build();
        Plan p = plan(w, "oak_planks", 8);
        assertEquals("Craft 8x oak_planks (2 crafts, inventory grid)", steps(p));
    }

    @Test
    void farStorageBeatsMining() {
        WorldModel w = VanillaFixtures.world()
                .storage("c1", "chest far away", 500, ItemCounts.of("diamond", 10))
                .gatherable("diamond", 600, "minecraft:diamond_ore").build();
        Plan p = plan(w, "diamond", 2);
        assertEquals("Fetch 2x diamond from chest far away", steps(p));
    }

    @Test
    void inventoryShulkerContentsCount() {
        WorldModel w = VanillaFixtures.world().inShulker("oak_planks", 16).build();
        Plan p = plan(w, "oak_planks", 8);
        assertEquals("Take 8x oak_planks out of a carried shulker box", steps(p));
    }

    @Test
    void reservedItemsAreNotConsumed() {
        // The planks are reserved for the build; the table must come from the log.
        WorldModel w = VanillaFixtures.world().inventory("oak_planks", 10).reserve("oak_planks", 10).inventory("oak_log", 1).build();
        Plan p = plan(w, "crafting_table", 1);
        assertTrue(p.isComplete(), p.describe().toString());
        assertEquals("Craft 4x oak_planks (1 craft, inventory grid) | Craft 1x crafting_table (1 craft, inventory grid)", steps(p));
        assertEquals(ItemCounts.of("oak_log", 1), p.fromInventory());
    }

    @Test
    void tagIngredientUsesWhateverPlanksAreAtHand() {
        // 8 planks for the chest + 4 for the table it needs, mixed wood types.
        WorldModel w = VanillaFixtures.world().inventory("spruce_planks", 3).inventory("birch_planks", 9).build();
        Plan p = plan(w, "chest", 1);
        assertTrue(p.isComplete(), p.describe().toString());
        assertEquals(ItemCounts.of("spruce_planks", 3, "birch_planks", 9), p.fromInventory());
    }

    @Test
    void tagIngredientPicksTheWoodThatIsAvailable() {
        // Only spruce logs: sticks must come from spruce planks, not oak.
        WorldModel w = VanillaFixtures.world().inventory("spruce_log", 1).build();
        Plan p = plan(w, "stick", 4);
        assertTrue(p.isComplete(), p.describe().toString());
        assertEquals("Craft 4x spruce_planks (1 craft, inventory grid) | Craft 4x stick (1 craft, inventory grid)", steps(p));
    }

    // ------------------------------------------------------------------------------- smelting & fuel

    @Test
    void smeltingPlacesACarriedFurnaceAndUsesCoal() {
        WorldModel w = VanillaFixtures.world().inventory("raw_iron", 3).inventory("coal", 1).inventory("furnace", 1).build();
        Plan p = plan(w, "iron_ingot", 3);
        assertTrue(p.isComplete(), p.describe().toString());
        assertEquals("Place a furnace | Smelt 3x raw_iron -> iron_ingot (furnace, 1x coal) | Pick up the furnace", steps(p));
    }

    @Test
    void furnaceStaysWithoutAPickaxe() {
        WorldModel w = VanillaFixtures.world().inventory("raw_iron", 3).inventory("coal", 1).inventory("furnace", 1)
                .hasPickaxe(false).build();
        Plan p = plan(w, "iron_ingot", 3);
        assertFalse(steps(p).contains("Pick up"));
        assertTrue(p.warnings().toString().contains("no pickaxe"), p.warnings().toString());
    }

    @Test
    void fuelFallsBackToAllowedPlanks() {
        WorldModel w = VanillaFixtures.world().inventory("raw_iron", 3).inventory("oak_planks", 10)
                .station(StationKind.FURNACE, "f1", 4).build();
        Plan p = plan(w, "iron_ingot", 3);
        assertTrue(p.isComplete(), p.describe().toString());
        // 3 items x 200 ticks = 600 burn ticks, planks burn 300 each -> 2 planks
        assertTrue(steps(p).contains("Smelt 3x raw_iron -> iron_ingot (furnace, 2x oak_planks)"), steps(p));
    }

    @Test
    void knownBlastFurnaceIsUsedForOres() {
        WorldModel w = VanillaFixtures.world().inventory("raw_iron", 8).inventory("coal", 2)
                .station(StationKind.FURNACE, "f1", 10).station(StationKind.BLAST_FURNACE, "b1", 10).build();
        Plan p = plan(w, "iron_ingot", 8);
        assertTrue(steps(p).contains("blast furnace"), steps(p));
    }

    @Test
    void smeltingTwoLevelsDeep() {
        WorldModel w = VanillaFixtures.world().inventory("cobblestone", 16).inventory("coal", 4)
                .station(StationKind.FURNACE, "f1", 4).build();
        Plan p = plan(w, "smooth_stone", 8);
        assertTrue(p.isComplete(), p.describe().toString());
        String s = steps(p);
        assertTrue(s.indexOf("cobblestone -> stone") < s.indexOf("stone -> smooth_stone"), s);
    }

    // ------------------------------------------------------------------------------- missing, mining, cycles

    @Test
    void impossibleItemsBecomeAShoppingList() {
        PlannerOptions o = new PlannerOptions();
        o.allowGather = false;
        WorldModel w = VanillaFixtures.world().gatherable("diamond", 600, "minecraft:diamond_ore").build();
        Plan p = plan(o, w, "diamond", 2);
        assertFalse(p.isComplete());
        assertEquals(ItemCounts.of("diamond", 2), p.missing());
    }

    @Test
    void miningIsTheLastResort() {
        WorldModel w = VanillaFixtures.world().gatherable("oak_log", 60, "minecraft:oak_log").build();
        Plan p = plan(w, "oak_planks", 8);
        assertTrue(p.isComplete(), p.describe().toString());
        assertEquals("Mine 2x oak_log (from minecraft:oak_log) | Craft 8x oak_planks (2 crafts, inventory grid)", steps(p));
    }

    @Test
    void compressionCyclesTerminate() {
        // nuggets -> ingots -> block; both are 3x3 so a table is bootstrapped from the log.
        WorldModel w = VanillaFixtures.world().inventory("iron_nugget", 81).inventory("oak_log", 1).build();
        Plan p = plan(w, "iron_block", 1);
        assertTrue(p.isComplete(), p.describe().toString());
        String s = steps(p);
        assertTrue(s.contains("Craft 9x iron_ingot (9 crafts, crafting table)"), s);
        assertTrue(s.indexOf("iron_ingot") < s.indexOf("Craft 1x iron_block"), s);
        assertEquals(ItemCounts.of("iron_nugget", 81, "oak_log", 1), p.fromInventory());
    }

    @Test
    void cyclesWithNothingAtHandReportMissingWithoutLooping() {
        WorldModel w = VanillaFixtures.world().build();
        Plan p = plan(w, "iron_block", 1);
        assertFalse(p.isComplete());
        // The shopping list names raw materials, never the item being made itself.
        assertEquals(0, p.missing().get(ItemKey.of("iron_block")), p.missing().toString());
        assertTrue(p.missing().get(ItemKey.of("raw_iron")) > 0, p.missing().toString());
    }

    @Test
    void remaindersAreReportedAsLeftovers() {
        WorldModel w = VanillaFixtures.world().inventory("milk_bucket", 3).inventory("sugar", 2).inventory("egg", 1)
                .inventory("wheat", 3).inventory("crafting_table", 1).build();
        Plan p = plan(w, "cake", 1);
        assertTrue(p.isComplete(), p.describe().toString());
        assertEquals(3, p.leftovers().get(ItemKey.of("bucket")));
    }

    @Test
    void surplusIsReusedWithinOnePlan() {
        // 1 log -> 4 planks: 2 for sticks (4 sticks), torches need 1 stick per 4 torches.
        WorldModel w = VanillaFixtures.world().inventory("oak_log", 1).inventory("coal", 2).build();
        Plan p = new Planner(BOOK, new PlannerOptions()).plan(ItemCounts.of("torch", 8, "stick", 2), w);
        assertTrue(p.isComplete(), p.describe().toString());
        assertEquals(ItemCounts.of("oak_log", 1, "coal", 2), p.fromInventory());
    }

    @Test
    void plansAreDeterministic() {
        WorldModel w = VanillaFixtures.world().inventory("oak_log", 5).inventory("cobblestone", 8).inventory("coal", 3).build();
        Plan a = plan(w, "torch", 16);
        Plan b = plan(w, "torch", 16);
        assertEquals(a.describe(), b.describe());
    }

    @Test
    void budgetStopsRunawayPlanning() {
        PlannerOptions o = new PlannerOptions();
        o.nodeBudget = 50;
        WorldModel w = VanillaFixtures.world().build();
        long start = System.nanoTime();
        Plan p = plan(o, w, "blast_furnace", 4);
        assertTrue(System.nanoTime() - start < 2_000_000_000L);
        assertFalse(p.isComplete());
    }

    @Test
    void timeEstimateGrowsWithSmeltingAmount() {
        WorldModel w1 = VanillaFixtures.world().inventory("raw_iron", 8).inventory("coal", 8).station(StationKind.FURNACE, "f", 2).build();
        WorldModel w2 = VanillaFixtures.world().inventory("raw_iron", 64).inventory("coal", 8).station(StationKind.FURNACE, "f", 2).build();
        assertTrue(plan(w2, "iron_ingot", 64).estimatedTicks() > plan(w1, "iron_ingot", 8).estimatedTicks());
    }
}
