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

package baritone.alto.integration.emi;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.alto.core.ItemKey;
import baritone.alto.integration.AltoIntegration;
import baritone.alto.mc.McItems;
import baritone.alto.picker.ItemPicker;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.screen.BoMScreen;
import dev.emi.emi.screen.RecipeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EMI 1.1.24 integration (feature H). Only loaded when EMI is installed. Read-only: it never registers an EMI plugin
 * and never changes EMI state.
 * <ul>
 * <li>Pick key: the stack under the mouse in EMI's panels, inventory slots, the recipe screen and the recipe tree
 * (public {@code EmiApi.getHoveredStack}, internal getters for EMI's own screens).</li>
 * <li>{@code #pick favorites}: EMI's favorites list (internal {@code EmiFavorites.favorites}).</li>
 * <li>Planner: the player's default recipes from EMI's recipe tree are preferred (internal {@code BoM}).</li>
 * <li>Missing items: "#plan" names the EMI categories that make them (e.g. Create pressing).</li>
 * </ul>
 * Internal members can change between EMI versions; each use is wrapped so a change only switches that part off.
 */
public final class EmiIntegration implements AltoIntegration {

    private static boolean internalsBroken;

    @Override
    public String name() {
        return "EMI";
    }

    @Override
    public boolean enabled() {
        return Baritone.settings().altoEmi.value;
    }

    @Override
    public void init(Alto alto) {
        alto.picker().addProvider(new ItemPicker.HoverProvider() {
            @Override
            public ItemStack hovered(double mouseX, double mouseY) {
                return EmiIntegration.hovered(mouseX, mouseY);
            }

            @Override
            public List<ItemStack> favorites() {
                return EmiIntegration.favorites();
            }
        });
        alto.addRecipePreferences(EmiIntegration::preferences);
        alto.addRecipeHint(EmiIntegration::hint);
        if (net.neoforged.fml.ModList.get().isLoaded("create")) {
            EmiScenarios.register(alto); // the pick test sets a Create deployer's filter
        }
    }

    static ItemStack hovered(double mouseX, double mouseY) {
        EmiStackInteraction in = EmiApi.getHoveredStack((int) mouseX, (int) mouseY, true);
        ItemStack s = in == null || in.isEmpty() ? ItemStack.EMPTY : first(in.getStack());
        if (!s.isEmpty() || internalsBroken) {
            return s;
        }
        try {
            var screen = Minecraft.getInstance().screen;
            if (screen instanceof RecipeScreen rs) {
                return first(rs.getHoveredStack());
            }
            if (screen instanceof BoMScreen bs) {
                Object hover = bs.getHoveredStack((int) mouseX, (int) mouseY);
                if (hover != null) {
                    Field f = hover.getClass().getField("stack");
                    f.setAccessible(true);
                    return first((EmiIngredient) f.get(hover));
                }
            }
        } catch (Throwable t) {
            broken(t);
        }
        return ItemStack.EMPTY;
    }

    static List<ItemStack> favorites() {
        List<ItemStack> out = new ArrayList<>();
        if (internalsBroken) {
            return out;
        }
        try {
            for (EmiFavorite f : List.copyOf(EmiFavorites.favorites)) {
                ItemStack s = first(f.getStack());
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        } catch (Throwable t) {
            broken(t);
        }
        return out;
    }

    /** Output item -> recipe id the player chose as default in EMI's recipe tree. */
    static Map<ItemKey, String> preferences() {
        Map<ItemKey, String> out = new HashMap<>();
        if (internalsBroken) {
            return out;
        }
        try {
            addAll(out, BoM.defaultRecipes);
            addAll(out, BoM.addedRecipes); // the player's own choices win over pack defaults
        } catch (Throwable t) {
            broken(t);
        }
        return out;
    }

    private static void addAll(Map<ItemKey, String> out, Map<EmiIngredient, EmiRecipe> recipes) {
        if (recipes == null) {
            return;
        }
        for (Map.Entry<EmiIngredient, EmiRecipe> e : Map.copyOf(recipes).entrySet()) {
            EmiRecipe r = e.getValue();
            ItemStack s = first(e.getKey());
            if (r != null && r.getId() != null && !s.isEmpty()) {
                out.put(McItems.key(s.getItem()), r.getId().toString());
            }
        }
    }

    /** "made by: Pressing, Mixing (EMI)" for an item Alto cannot get. */
    static String hint(ItemKey item) {
        Set<String> categories = new LinkedHashSet<>();
        for (EmiRecipe r : EmiApi.getRecipeManager().getRecipesByOutput(EmiStack.of(McItems.item(item)))) {
            if (r.getCategory() != null) {
                categories.add(r.getCategory().getName().getString());
            }
            if (categories.size() >= 5) {
                break;
            }
        }
        return categories.isEmpty() ? null : item.shortName() + " is made by: " + String.join(", ", categories) + " (EMI)";
    }

    private static ItemStack first(EmiIngredient ing) {
        if (ing == null || ing.isEmpty()) {
            return ItemStack.EMPTY;
        }
        for (EmiStack s : ing.getEmiStacks()) {
            ItemStack is = s.getItemStack();
            if (!is.isEmpty()) {
                return is.copyWithCount(1);
            }
        }
        return ItemStack.EMPTY;
    }

    private static void broken(Throwable t) {
        if (!internalsBroken) {
            internalsBroken = true;
            AltoLog.info("EMI internals changed, favorites/recipe tree support switched off: " + t);
        }
    }
}
