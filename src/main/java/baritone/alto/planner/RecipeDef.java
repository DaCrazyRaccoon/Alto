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

import java.util.ArrayList;
import java.util.List;

/**
 * A recipe in the planner's own, Minecraft-independent form. Built from the client recipe manager
 * ({@code VanillaRecipeSource}), from EMI (optional), or from test fixtures.
 *
 * @param id          recipe id ({@code minecraft:oak_planks})
 * @param kind        what kind of station runs it
 * @param source      where it came from ({@code vanilla}, {@code emi:create:mixing}, {@code fixture})
 * @param inputs      crafting: one ingredient per used grid slot (count 1); cooking: the single input
 * @param gridSlots   shaped crafting only: for each input, its slot in a 3x3 grid (row * 3 + column); empty otherwise
 * @param width       shaped crafting pattern width (shapeless: 0)
 * @param height      shaped crafting pattern height (shapeless: 0)
 * @param output      the result item
 * @param outputCount results per craft / per smelted item
 * @param remainders  items left behind per craft (empty buckets, bottles)
 * @param cookTicks   cooking time per item in ticks (0 for crafting)
 * @param experienceHint unused by the planner; kept for display
 */
public record RecipeDef(String id, RecipeKind kind, String source, List<Ingredient> inputs, List<Integer> gridSlots,
                        int width, int height, ItemKey output, int outputCount, ItemCounts remainders, int cookTicks,
                        float experienceHint) {

    public RecipeDef {
        inputs = List.copyOf(inputs);
        gridSlots = List.copyOf(gridSlots);
        if (outputCount <= 0) {
            throw new IllegalArgumentException("recipe " + id + " has no output");
        }
        if (!gridSlots.isEmpty() && gridSlots.size() != inputs.size()) {
            throw new IllegalArgumentException("recipe " + id + ": gridSlots/inputs size mismatch");
        }
    }

    public static RecipeDef shaped(String id, String output, int outputCount, String[] pattern, Object... keyThenIngredient) {
        java.util.Map<Character, Ingredient> keys = new java.util.HashMap<>();
        for (int i = 0; i + 1 < keyThenIngredient.length; i += 2) {
            keys.put((Character) keyThenIngredient[i], (Ingredient) keyThenIngredient[i + 1]);
        }
        List<Ingredient> inputs = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        int width = 0;
        for (int row = 0; row < pattern.length; row++) {
            width = Math.max(width, pattern[row].length());
            for (int col = 0; col < pattern[row].length(); col++) {
                char c = pattern[row].charAt(col);
                if (c == ' ') {
                    continue;
                }
                Ingredient ing = keys.get(c);
                if (ing == null) {
                    throw new IllegalArgumentException("pattern key " + c + " undefined in " + id);
                }
                inputs.add(ing);
                slots.add(row * 3 + col);
            }
        }
        return new RecipeDef(id, RecipeKind.CRAFTING, "fixture", inputs, slots, width, pattern.length,
                ItemKey.of(output), outputCount, new ItemCounts(), 0, 0);
    }

    public static RecipeDef shapeless(String id, String output, int outputCount, Ingredient... inputs) {
        return new RecipeDef(id, RecipeKind.CRAFTING, "fixture", List.of(inputs), List.of(), 0, 0,
                ItemKey.of(output), outputCount, new ItemCounts(), 0, 0);
    }

    public static RecipeDef cooking(String id, RecipeKind kind, String output, Ingredient input, int cookTicks) {
        return new RecipeDef(id, kind, "fixture", List.of(input), List.of(), 0, 0,
                ItemKey.of(output), 1, new ItemCounts(), cookTicks, 0);
    }

    public RecipeDef withRemainders(ItemCounts perCraft) {
        return new RecipeDef(id, kind, source, inputs, gridSlots, width, height, output, outputCount, perCraft.copy(), cookTicks, experienceHint);
    }

    public boolean isShaped() {
        return !gridSlots.isEmpty();
    }

    /**
     * Whether this recipe can be made in the player's 2x2 inventory grid.
     */
    public boolean fitsInventoryGrid() {
        if (kind != RecipeKind.CRAFTING) {
            return false;
        }
        if (isShaped()) {
            return width <= 2 && height <= 2;
        }
        return inputs.size() <= 4;
    }

    /**
     * Inputs with identical option lists merged, e.g. a chest becomes "8x any planks".
     */
    public List<Ingredient> aggregatedInputs() {
        List<Ingredient> out = new ArrayList<>();
        outer:
        for (Ingredient in : inputs) {
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).sameOptions(in)) {
                    out.set(i, out.get(i).withCount(out.get(i).count() + in.count()));
                    continue outer;
                }
            }
            out.add(in);
        }
        return out;
    }

    /** True if any input accepts the output item (repair/upgrade loops, dye-the-same-item recipes). */
    public boolean consumesOwnOutput() {
        for (Ingredient in : inputs) {
            if (in.accepts(output)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return id + " (" + kind + ") -> " + outputCount + "x " + output.shortName();
    }
}
