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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable index of recipes by output item. Several sources (vanilla recipe manager, EMI) can be merged;
 * the first recipe with a given id wins.
 */
public final class RecipeBook {

    private final Map<String, RecipeDef> byId;
    private final Map<ItemKey, List<RecipeDef>> byOutput;

    private RecipeBook(Map<String, RecipeDef> byId) {
        this.byId = Collections.unmodifiableMap(byId);
        Map<ItemKey, List<RecipeDef>> out = new HashMap<>();
        for (RecipeDef r : byId.values()) {
            out.computeIfAbsent(r.output(), k -> new ArrayList<>()).add(r);
        }
        out.replaceAll((k, v) -> List.copyOf(v));
        this.byOutput = Collections.unmodifiableMap(out);
    }

    public static RecipeBook of(Collection<RecipeDef> recipes) {
        Map<String, RecipeDef> map = new LinkedHashMap<>();
        for (RecipeDef r : recipes) {
            map.putIfAbsent(r.id(), r);
        }
        return new RecipeBook(map);
    }

    public static RecipeBook merge(RecipeBook first, RecipeBook second) {
        Map<String, RecipeDef> map = new LinkedHashMap<>(first.byId);
        second.byId.forEach(map::putIfAbsent);
        return new RecipeBook(map);
    }

    public static RecipeBook empty() {
        return new RecipeBook(new LinkedHashMap<>());
    }

    /** All recipes producing {@code item}, in source order. */
    public List<RecipeDef> producing(ItemKey item) {
        List<RecipeDef> list = byOutput.get(item);
        if (list == null && !item.variant().isEmpty()) {
            list = byOutput.get(item.plain());
        }
        return list == null ? List.of() : list;
    }

    public RecipeDef byId(String id) {
        return byId.get(id);
    }

    public Collection<RecipeDef> all() {
        return byId.values();
    }

    public int size() {
        return byId.size();
    }
}
