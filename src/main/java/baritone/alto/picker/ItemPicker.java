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

package baritone.alto.picker;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import baritone.alto.task.AltoTask;
import baritone.alto.task.Job;
import baritone.alto.task.SequenceTask;
import baritone.alto.task.SetFilterTask;
import baritone.api.command.IBaritoneChatControl;
import baritone.api.utils.Helper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Feature H: "pick" an item (EMI's panel, a recipe, or any inventory slot, with the Alto pick key) and use it as the
 * target of an Alto action. The headline flow: look at a Create deployer and press the pick key (marks it), open
 * your inventory, hover the item in EMI and press the pick key again: Alto walks there and sets the filter.
 * Several blocks can be marked (or selected with #sel) and get the same filter.
 */
public final class ItemPicker {

    /** Where the hovered item comes from while a screen is open (EMI integration, or plain inventory slots). */
    public interface HoverProvider {
        /** The item under the mouse in the current screen, or EMPTY. */
        ItemStack hovered(double mouseX, double mouseY);

        /** EMI favorites (or empty if not available). */
        default List<ItemStack> favorites() {
            return List.of();
        }
    }

    private final Alto alto;
    private final List<BlockPos> marked = new ArrayList<>();
    private final List<HoverProvider> providers = new ArrayList<>();
    private ItemKey last = ItemKey.EMPTY;

    public ItemPicker(Alto alto) {
        this.alto = alto;
    }

    public void addProvider(HoverProvider p) {
        providers.add(0, p); // integrations first; the vanilla slot provider stays last
    }

    public List<HoverProvider> providers() {
        return providers;
    }

    public ItemKey last() {
        return last;
    }

    public List<BlockPos> marked() {
        return List.copyOf(marked);
    }

    public void clearMarks() {
        marked.clear();
    }

    /** Pick key pressed in the world: mark/unmark the block under the crosshair. */
    public void toggleMarkAtCrosshair() {
        HitResult hit = Minecraft.getInstance().hitResult;
        if (!(hit instanceof BlockHitResult b) || hit.getType() != HitResult.Type.BLOCK) {
            AltoLog.chat("Look at a block to mark it (marked: " + marked.size() + ").");
            return;
        }
        BlockPos pos = b.getBlockPos().immutable();
        if (marked.remove(pos)) {
            AltoLog.chat("Unmarked " + pos.toShortString() + " (" + marked.size() + " marked).");
            return;
        }
        marked.add(pos);
        boolean hasFilter = alto.filterAccess(Minecraft.getInstance().level, pos) != null;
        AltoLog.chat("Marked " + pos.toShortString() + (hasFilter ? " (has a filter slot)" : "") + " - now pick an item in EMI or "
                + "your inventory with the same key (" + marked.size() + " marked).");
    }

    /** Pick key pressed over an item in a screen. */
    public void pick(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        last = McItems.key(stack.getItem());
        if (!marked.isEmpty() && Baritone.settings().altoPickAutoFilter.value) {
            Minecraft.getInstance().setScreen(null);
            filterMarked(last);
            return;
        }
        offerActions(last);
    }

    /** Set the filter of every marked block to {@code item} (EMPTY clears). */
    public void filterMarked(ItemKey item) {
        if (marked.isEmpty()) {
            AltoLog.warn("No marked blocks. Look at a block and press the Alto pick key, or use #filter <item> <x y z>.");
            return;
        }
        List<AltoTask> steps = new ArrayList<>();
        for (BlockPos p : marked) {
            steps.add(new SetFilterTask(alto, p, item, null));
        }
        String what = item.isEmpty() ? "clear the filter" : "set the filter to " + item.shortName();
        alto.startJob(Job.of(new SequenceTask(alto, "Filters: " + what + " on " + marked.size() + " block(s)", steps), "picker"));
        marked.clear();
    }

    private void offerActions(ItemKey item) {
        String id = item.id();
        MutableComponent line = Component.literal("[Alto] Picked " + McItems.displayName(item) + ": ");
        line.append(action("[filter marked]", "filter " + id, "Set this as the filter of the marked blocks"));
        line.append(Component.literal(" "));
        line.append(action("[fetch 64]", "fetch " + id + " 64", "Fetch 64 from your storage zones"));
        line.append(Component.literal(" "));
        line.append(action("[craft 1]", "craft " + id + " 1", "Craft one (plans the whole chain)"));
        line.append(Component.literal(" "));
        line.append(action("[plan]", "plan " + id + " 1", "Show how Alto would get one"));
        if (McItems.item(item) instanceof net.minecraft.world.item.BlockItem) {
            line.append(Component.literal(" "));
            line.append(action("[mine 16]", "mine 16 " + id, "Mine 16 of this block"));
        }
        Helper.HELPER.logDirect(line);
    }

    private static MutableComponent action(String label, String command, String hover) {
        return Component.literal(label).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, IBaritoneChatControl.FORCE_COMMAND_PREFIX + command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover + "\n#" + command))));
    }
}
