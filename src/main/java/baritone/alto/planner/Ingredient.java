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

import baritone.alto.core.ItemKey;

import java.util.List;
import java.util.stream.Collectors;

/**
 * One input of a recipe: any of {@code options} (a tag such as "any planks" becomes several options), {@code count}
 * of them. For crafting recipes every grid slot is its own ingredient with count 1; {@link RecipeDef#aggregatedInputs()}
 * merges identical ones.
 *
 * @param options  accepted items, in the recipe's order (never empty)
 * @param count    how many are consumed per craft
 * @param label    human label, e.g. the tag name {@code #minecraft:planks}, or the single item id
 */
public record Ingredient(List<ItemKey> options, int count, String label) {

    public Ingredient {
        if (options.isEmpty()) {
            throw new IllegalArgumentException("ingredient without options");
        }
        options = List.copyOf(options);
    }

    public static Ingredient of(String... ids) {
        List<ItemKey> keys = java.util.Arrays.stream(ids).map(ItemKey::of).toList();
        return new Ingredient(keys, 1, ids.length == 1 ? keys.get(0).id() : keys.stream().map(ItemKey::shortName).collect(Collectors.joining("|")));
    }

    /** Label without the {@code minecraft:} namespace ({@code raw_iron}, {@code #planks}). */
    public String displayLabel() {
        return label.replace("minecraft:", "");
    }

    public boolean accepts(ItemKey item) {
        return options.contains(item);
    }

    public boolean sameOptions(Ingredient other) {
        return options.equals(other.options);
    }

    public Ingredient withCount(int newCount) {
        return new Ingredient(options, newCount, label);
    }

    @Override
    public String toString() {
        return count + "x " + displayLabel();
    }
}
