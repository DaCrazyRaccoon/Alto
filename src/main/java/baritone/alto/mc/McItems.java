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

import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Conversions between Minecraft item stacks and Alto's Minecraft-free {@link ItemKey}.
 * <p>
 * Variant rule: a stack whose data components differ from the item's defaults gets a short, stable hash of the
 * difference as its variant, except when only "wear" components differ (damage, repair cost, custom name), so a
 * used pickaxe is still just {@code minecraft:iron_pickaxe} for planning and counting.
 */
public final class McItems {

    private McItems() {
    }

    public static ItemKey key(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemKey.EMPTY;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return ItemKey.of(id, variant(stack));
    }

    public static ItemKey key(Item item) {
        return ItemKey.of(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    public static Item item(ItemKey key) {
        ResourceLocation rl = ResourceLocation.tryParse(key.id());
        if (rl == null) {
            return Items.AIR;
        }
        return BuiltInRegistries.ITEM.getOptional(rl).orElse(Items.AIR);
    }

    public static Optional<Item> parseItem(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id.indexOf(':') < 0 ? "minecraft:" + id : id);
        if (rl == null) {
            return Optional.empty();
        }
        return BuiltInRegistries.ITEM.getOptional(rl).filter(i -> i != Items.AIR);
    }

    public static String displayName(ItemKey key) {
        Item item = item(key);
        return item == Items.AIR ? key.shortName() : new ItemStack(item).getHoverName().getString();
    }

    private static String variant(ItemStack stack) {
        DataComponentPatch patch = stack.getComponentsPatch();
        if (patch.isEmpty()) {
            return "";
        }
        DataComponentPatch relevant = patch.forget(type -> type == DataComponents.DAMAGE || type == DataComponents.REPAIR_COST
                || type == DataComponents.CUSTOM_NAME);
        if (relevant.isEmpty()) {
            return "";
        }
        try {
            Optional<Tag> tag = DataComponentPatch.CODEC.encodeStart(NbtOps.INSTANCE, relevant).result();
            String text = tag.map(Tag::toString).orElse(relevant.toString());
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 10);
        } catch (NoSuchAlgorithmException | RuntimeException e) {
            return Integer.toHexString(relevant.hashCode());
        }
    }

    /** Main inventory + hotbar + offhand (no armor), plain counts. */
    public static ItemCounts inventoryCounts(Inventory inv) {
        ItemCounts c = new ItemCounts();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty()) {
                c.add(key(s), s.getCount());
            }
        }
        for (ItemStack s : inv.offhand) {
            if (!s.isEmpty()) {
                c.add(key(s), s.getCount());
            }
        }
        return c;
    }

    /** Count of a plain item (any variant) in the main inventory, hotbar and offhand. */
    public static int count(Inventory inv, Item item) {
        int n = 0;
        for (ItemStack s : inv.items) {
            if (s.is(item)) {
                n += s.getCount();
            }
        }
        for (ItemStack s : inv.offhand) {
            if (s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** Items stored inside shulker boxes carried in the inventory (from their synced container component). */
    public static ItemCounts shulkerContents(Inventory inv) {
        ItemCounts c = new ItemCounts();
        for (ItemStack s : inv.items) {
            addShulkerContents(s, c);
        }
        return c;
    }

    public static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    public static void addShulkerContents(ItemStack stack, ItemCounts into) {
        if (!isShulkerBox(stack)) {
            return;
        }
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return;
        }
        contents.nonEmptyItems().forEach(inner -> into.add(key(inner), inner.getCount()));
    }

    /** Number of completely empty main+hotbar slots. */
    /**
     * A hotbar slot that is safe to hold while right-clicking a container: modded blocks react to some held items
     * (a Sophisticated Storage tier upgrade or dye changes the chest, a Create wrench rotates blocks, a bucket fills).
     * Prefers the current slot if it is empty, a block or a tool; then an empty slot; then any block or tool.
     */
    public static int safeHotbarSlot(Inventory inv) {
        if (isSafeToHold(inv.getItem(inv.selected))) {
            return inv.selected;
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (isSafeToHold(inv.getItem(i))) {
                return i;
            }
        }
        return inv.selected;
    }

    static boolean isSafeToHold(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        net.minecraft.world.item.Item item = stack.getItem();
        String ns = BuiltInRegistries.ITEM.getKey(item).getNamespace();
        if (ns.startsWith("sophisticated") || isShulkerBox(stack)) {
            return false;
        }
        return item instanceof net.minecraft.world.item.BlockItem || item instanceof net.minecraft.world.item.TieredItem;
    }

    public static int freeSlots(Inventory inv) {
        int n = 0;
        for (ItemStack s : inv.items) {
            if (s.isEmpty()) {
                n++;
            }
        }
        return n;
    }
}
