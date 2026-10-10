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

/**
 * Planner switches and cost knobs. Filled from Baritone settings by {@code AltoSettingsBridge}; tests set fields
 * directly. Mutable on purpose (plain configuration object), copy with {@link #copy()} before changing a shared one.
 */
public final class PlannerOptions {

    /** Force the crafting-table flow even for recipes that fit the 2x2 inventory grid (setting alwaysUseCraftingStation). */
    public boolean alwaysUseCraftingStation = false;
    public boolean useShulkers = true;
    public boolean useStorage = true;
    public boolean allowCrafting = true;
    public boolean allowSmelting = true;
    public boolean allowGather = true;
    public boolean allowPlaceStation = true;
    public boolean reuseExistingStations = true;
    public boolean pickUpPlacedStations = true;

    /** Known stations farther than this (blocks) are ignored. */
    public double stationSearchRadius = 48;
    /**
     * Known storage within this distance is always preferred over crafting; farther storage is used only when it is
     * cheaper than crafting (travel time counts).
     */
    public double storagePreferenceRadius = 48;
    /** How many furnaces may run in parallel for one smelting step (time estimate and executor limit). */
    public int maxFurnaces = 1;

    public int maxDepth = 12;
    /** Hard cap on planner work per plan; when exceeded the planner stops exploring and reports it. */
    public int nodeBudget = 20_000;
    /** How many alternatives of a tag ingredient are compared in detail. */
    public int maxAlternatives = 12;

    /** Time estimates. */
    public int ticksPerClick = 5;
    public double walkBlocksPerTick = 0.2;

    public PlannerOptions copy() {
        PlannerOptions o = new PlannerOptions();
        o.alwaysUseCraftingStation = alwaysUseCraftingStation;
        o.useShulkers = useShulkers;
        o.useStorage = useStorage;
        o.allowCrafting = allowCrafting;
        o.allowSmelting = allowSmelting;
        o.allowGather = allowGather;
        o.allowPlaceStation = allowPlaceStation;
        o.reuseExistingStations = reuseExistingStations;
        o.pickUpPlacedStations = pickUpPlacedStations;
        o.stationSearchRadius = stationSearchRadius;
        o.storagePreferenceRadius = storagePreferenceRadius;
        o.maxFurnaces = maxFurnaces;
        o.maxDepth = maxDepth;
        o.nodeBudget = nodeBudget;
        o.maxAlternatives = maxAlternatives;
        o.ticksPerClick = ticksPerClick;
        o.walkBlocksPerTick = walkBlocksPerTick;
        return o;
    }

    /** Round-trip walking time for a distance in blocks. */
    double travelTicks(double distance) {
        return 2 * distance / Math.max(0.01, walkBlocksPerTick);
    }
}
