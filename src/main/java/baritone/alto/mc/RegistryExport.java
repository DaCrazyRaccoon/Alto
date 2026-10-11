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

package baritone.alto.mc;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Writes {@code alto-registry.json} for the offline script editor: every item and block id of the running game with
 * its display name. Format: {@code {"format":"alto-registry","version":1,"minecraft":"1.21.1","exported":"...",
 * "items":[{"id","name"}],"blocks":[{"id","name"}]}}.
 */
public final class RegistryExport {

    private RegistryExport() {
    }

    public static int write(Path file) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("format", "alto-registry");
        root.addProperty("version", 1);
        root.addProperty("minecraft", SharedConstants.getCurrentVersion().getName());
        root.addProperty("exported", Instant.now().toString());
        JsonArray items = new JsonArray();
        List<ResourceLocation> itemIds = new ArrayList<>(BuiltInRegistries.ITEM.keySet());
        itemIds.sort(null);
        for (ResourceLocation id : itemIds) {
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", id.toString());
            o.addProperty("name", name(() -> new ItemStack(item).getHoverName().getString(), id));
            items.add(o);
        }
        JsonArray blocks = new JsonArray();
        List<ResourceLocation> blockIds = new ArrayList<>(BuiltInRegistries.BLOCK.keySet());
        blockIds.sort(null);
        for (ResourceLocation id : blockIds) {
            Block block = BuiltInRegistries.BLOCK.get(id);
            JsonObject o = new JsonObject();
            o.addProperty("id", id.toString());
            o.addProperty("name", name(() -> block.getName().getString(), id));
            blocks.add(o);
        }
        root.add("items", items);
        root.add("blocks", blocks);
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().disableHtmlEscaping().create().toJson(root), StandardCharsets.UTF_8);
        return items.size() + blocks.size();
    }

    private static String name(Supplier<String> s, ResourceLocation id) {
        try {
            String n = s.get();
            return n == null || n.isBlank() ? id.getPath() : n;
        } catch (Throwable t) {
            return id.getPath();
        }
    }
}
