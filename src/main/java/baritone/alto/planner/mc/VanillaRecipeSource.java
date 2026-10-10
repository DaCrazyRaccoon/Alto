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

package baritone.alto.planner.mc;

import baritone.alto.AltoLog;
import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import baritone.alto.planner.Ingredient;
import baritone.alto.planner.RecipeBook;
import baritone.alto.planner.RecipeDef;
import baritone.alto.planner.RecipeKind;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the client's recipe manager (synced by the server on join and /reload, so datapack and KubeJS changes are
 * included) into a planner {@link RecipeBook}. Only recipe types the bot can execute are converted: crafting
 * (shaped/shapeless, not "special" ones like map cloning) and furnace/blast furnace/smoker cooking.
 * Cached per recipe-manager instance. Client thread only.
 */
public final class VanillaRecipeSource {

    private RecipeManager cachedFor;
    private int cachedSize = -1;
    private RecipeBook cached = RecipeBook.empty();

    public RecipeBook book(Level level) {
        if (level == null) {
            return RecipeBook.empty();
        }
        RecipeManager rm = level.getRecipeManager();
        int size = rm.getRecipes().size();
        if (rm != cachedFor || size != cachedSize) {
            long start = System.nanoTime();
            cached = convert(rm, level.registryAccess());
            cachedFor = rm;
            cachedSize = size;
            AltoLog.info("recipe book: " + cached.size() + " executable recipes from " + size + " in "
                    + (System.nanoTime() - start) / 1_000_000 + " ms");
        }
        return cached;
    }

    static RecipeBook convert(RecipeManager rm, HolderLookup.Provider registries) {
        List<RecipeDef> out = new ArrayList<>();
        for (RecipeHolder<?> holder : rm.getRecipes()) {
            try {
                RecipeDef def = convert(holder, registries);
                if (def != null) {
                    out.add(def);
                }
            } catch (Throwable t) {
                // A broken modded recipe must not break planning for everything else.
                AltoLog.debug("skipping recipe " + holder.id() + ": " + t);
            }
        }
        return RecipeBook.of(out);
    }

    static RecipeDef convert(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        Recipe<?> recipe = holder.value();
        String id = holder.id().toString();
        if (recipe.isSpecial() || recipe.isIncomplete()) {
            return null;
        }
        ItemStack result = recipe.getResultItem(registries);
        if (result.isEmpty()) {
            return null;
        }
        ItemKey output = McItems.key(result);
        if (recipe instanceof CraftingRecipe) {
            List<Ingredient> inputs = new ArrayList<>();
            List<Integer> slots = new ArrayList<>();
            ItemCounts remainders = new ItemCounts();
            int width = 0;
            int height = 0;
            List<net.minecraft.world.item.crafting.Ingredient> ings = recipe.getIngredients();
            if (recipe instanceof ShapedRecipe shaped) {
                width = shaped.getWidth();
                height = shaped.getHeight();
                for (int i = 0; i < ings.size(); i++) {
                    Ingredient ing = convert(ings.get(i));
                    if (ing == null) {
                        continue;
                    }
                    inputs.add(ing);
                    slots.add((i / width) * 3 + (i % width));
                    addRemainder(ings.get(i), remainders);
                }
            } else {
                for (net.minecraft.world.item.crafting.Ingredient raw : ings) {
                    Ingredient ing = convert(raw);
                    if (ing != null) {
                        inputs.add(ing);
                        addRemainder(raw, remainders);
                    }
                }
                if (inputs.size() > 9) {
                    return null;
                }
            }
            if (inputs.isEmpty() || width > 3 || height > 3) {
                return null;
            }
            return new RecipeDef(id, RecipeKind.CRAFTING, "vanilla", inputs, slots, width, height, output, result.getCount(),
                    remainders, 0, 0);
        }
        if (recipe instanceof AbstractCookingRecipe cooking) {
            RecipeType<?> type = recipe.getType();
            RecipeKind kind = type == RecipeType.SMELTING ? RecipeKind.SMELTING
                    : type == RecipeType.BLASTING ? RecipeKind.BLASTING
                    : type == RecipeType.SMOKING ? RecipeKind.SMOKING : RecipeKind.OTHER;
            if (kind == RecipeKind.OTHER || recipe.getIngredients().isEmpty()) {
                return null;
            }
            Ingredient in = convert(recipe.getIngredients().get(0));
            if (in == null) {
                return null;
            }
            return new RecipeDef(id, kind, "vanilla", List.of(in), List.of(), 0, 0, output, result.getCount(),
                    new ItemCounts(), cooking.getCookingTime(), cooking.getExperience());
        }
        return null;
    }

    private static Ingredient convert(net.minecraft.world.item.crafting.Ingredient raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Set<ItemKey> keys = new LinkedHashSet<>();
        for (ItemStack s : raw.getItems()) {
            if (!s.isEmpty()) {
                keys.add(McItems.key(s.getItem()));
            }
        }
        if (keys.isEmpty()) {
            return null;
        }
        List<ItemKey> list = new ArrayList<>(keys);
        String label = list.size() == 1 ? list.get(0).id()
                : list.get(0).shortName() + " or " + (list.size() - 1) + " other" + (list.size() > 2 ? "s" : "");
        return new Ingredient(list, 1, label);
    }

    private static void addRemainder(net.minecraft.world.item.crafting.Ingredient raw, ItemCounts into) {
        ItemStack[] items = raw.getItems();
        if (items.length == 0) {
            return;
        }
        Item first = items[0].getItem();
        if (first.hasCraftingRemainingItem()) {
            into.add(McItems.key(first.getCraftingRemainingItem()), 1);
        }
    }
}
