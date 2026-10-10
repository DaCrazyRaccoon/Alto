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

import java.util.ArrayList;
import java.util.List;

/**
 * A hand-copied subset of the vanilla 1.21.1 recipes (data/minecraft/recipe/*.json), enough to exercise the
 * planner: tags (planks, logs, coals, stone crafting materials), 2x2 vs 3x3 grids, smelting/blasting,
 * compression cycles (nugget/ingot/block), remainders (milk buckets in cake) and fuels.
 */
final class VanillaFixtures {

    static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry"};

    private VanillaFixtures() {
    }

    static Ingredient planks() {
        List<String> ids = new ArrayList<>();
        for (String w : WOODS) {
            ids.add("minecraft:" + w + "_planks");
        }
        ids.add("minecraft:bamboo_planks");
        ids.add("minecraft:crimson_planks");
        ids.add("minecraft:warped_planks");
        return new Ingredient(ids.stream().map(baritone.alto.core.ItemKey::of).toList(), 1, "#minecraft:planks");
    }

    static Ingredient logsOf(String wood) {
        return new Ingredient(List.of(
                baritone.alto.core.ItemKey.of(wood + "_log"), baritone.alto.core.ItemKey.of(wood + "_wood"),
                baritone.alto.core.ItemKey.of("stripped_" + wood + "_log"), baritone.alto.core.ItemKey.of("stripped_" + wood + "_wood")),
                1, "#minecraft:" + wood + "_logs");
    }

    static Ingredient logsThatBurn() {
        List<baritone.alto.core.ItemKey> ids = new ArrayList<>();
        for (String w : WOODS) {
            ids.add(baritone.alto.core.ItemKey.of(w + "_log"));
        }
        return new Ingredient(ids, 1, "#minecraft:logs_that_burn");
    }

    static Ingredient coals() {
        return Ingredient.of("minecraft:coal", "minecraft:charcoal");
    }

    static Ingredient stoneCrafting() {
        return Ingredient.of("minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:blackstone");
    }

    static RecipeBook book() {
        List<RecipeDef> r = new ArrayList<>();
        for (String w : WOODS) {
            r.add(RecipeDef.shapeless("minecraft:" + w + "_planks", w + "_planks", 4, logsOf(w)));
        }
        r.add(RecipeDef.shaped("minecraft:stick", "stick", 4, new String[]{"#", "#"}, '#', planks()));
        r.add(RecipeDef.shaped("minecraft:crafting_table", "crafting_table", 1, new String[]{"##", "##"}, '#', planks()));
        r.add(RecipeDef.shaped("minecraft:chest", "chest", 1, new String[]{"###", "# #", "###"}, '#', planks()));
        r.add(RecipeDef.shaped("minecraft:furnace", "furnace", 1, new String[]{"###", "# #", "###"}, '#', stoneCrafting()));
        r.add(RecipeDef.shaped("minecraft:torch", "torch", 4, new String[]{"X", "#"}, 'X', coals(), '#', Ingredient.of("stick")));
        r.add(RecipeDef.shaped("minecraft:bucket", "bucket", 1, new String[]{"# #", " # "}, '#', Ingredient.of("iron_ingot")));
        r.add(RecipeDef.shaped("minecraft:iron_block", "iron_block", 1, new String[]{"###", "###", "###"}, '#', Ingredient.of("iron_ingot")));
        r.add(RecipeDef.shaped("minecraft:iron_ingot_from_nuggets", "iron_ingot", 1, new String[]{"###", "###", "###"}, '#', Ingredient.of("iron_nugget")));
        r.add(RecipeDef.shapeless("minecraft:iron_ingot_from_iron_block", "iron_ingot", 9, Ingredient.of("iron_block")));
        r.add(RecipeDef.shapeless("minecraft:iron_nugget", "iron_nugget", 9, Ingredient.of("iron_ingot")));
        r.add(RecipeDef.shaped("minecraft:blast_furnace", "blast_furnace", 1, new String[]{"III", "IXI", "###"},
                'I', Ingredient.of("iron_ingot"), 'X', Ingredient.of("furnace"), '#', Ingredient.of("smooth_stone")));
        r.add(RecipeDef.shapeless("minecraft:sugar_from_sugar_cane", "sugar", 1, Ingredient.of("sugar_cane")));
        r.add(RecipeDef.shaped("minecraft:cake", "cake", 1, new String[]{"AAA", "BEB", "CCC"},
                'A', Ingredient.of("milk_bucket"), 'B', Ingredient.of("sugar"), 'E', Ingredient.of("egg"), 'C', Ingredient.of("wheat"))
                .withRemainders(ItemCounts.of("minecraft:bucket", 3)));

        r.add(RecipeDef.cooking("minecraft:iron_ingot_from_smelting_raw_iron", RecipeKind.SMELTING, "iron_ingot", Ingredient.of("raw_iron"), 200));
        r.add(RecipeDef.cooking("minecraft:iron_ingot_from_blasting_raw_iron", RecipeKind.BLASTING, "iron_ingot", Ingredient.of("raw_iron"), 100));
        r.add(RecipeDef.cooking("minecraft:stone", RecipeKind.SMELTING, "stone", Ingredient.of("cobblestone"), 200));
        r.add(RecipeDef.cooking("minecraft:smooth_stone", RecipeKind.SMELTING, "smooth_stone", Ingredient.of("stone"), 200));
        r.add(RecipeDef.cooking("minecraft:glass", RecipeKind.SMELTING, "glass", Ingredient.of("sand"), 200));
        r.add(RecipeDef.cooking("minecraft:charcoal", RecipeKind.SMELTING, "charcoal", logsThatBurn(), 200));
        r.add(RecipeDef.cooking("minecraft:cooked_beef", RecipeKind.SMELTING, "cooked_beef", Ingredient.of("beef"), 200));
        r.add(RecipeDef.cooking("minecraft:cooked_beef_from_smoking", RecipeKind.SMOKING, "cooked_beef", Ingredient.of("beef"), 100));
        return RecipeBook.of(r);
    }

    /** The default fuel allow-list order used by Alto (coal first, never valuable items). */
    static WorldModel.Builder world() {
        return WorldModel.builder()
                .fuel("coal", 1600)
                .fuel("charcoal", 1600)
                .fuel("oak_planks", 300)
                .fuel("spruce_planks", 300);
    }
}
