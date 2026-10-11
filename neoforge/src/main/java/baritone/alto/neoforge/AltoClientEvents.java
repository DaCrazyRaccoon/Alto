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

package baritone.alto.neoforge;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.picker.ItemPicker;
import baritone.api.BaritoneAPI;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side key handling for Alto. Only local input is read; nothing here sends packets by itself.
 * <ul>
 * <li>Pick key (default Y): in the world it marks/unmarks the block you look at; in any inventory or EMI screen it
 * picks the item under the mouse (feature H).</li>
 * </ul>
 */
public final class AltoClientEvents {

    public static final String CATEGORY = "key.categories.alto";
    public static final KeyMapping PICK = new KeyMapping("key.alto.pick", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, CATEGORY);
    /** Global stop: the running script, Alto's job and Baritone's pathing. */
    public static final KeyMapping STOP = new KeyMapping("key.alto.stop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_END, CATEGORY);
    /** Progress HUD on/off (extra 14). */
    public static final KeyMapping HUD = new KeyMapping("key.alto.hud", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY);
    /** Script runner screen (M5). */
    public static final KeyMapping SCRIPTS = new KeyMapping("key.alto.scripts", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);

    private AltoClientEvents() {
    }

    static void init(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            e.register(PICK);
            e.register(STOP);
            e.register(HUD);
            e.register(SCRIPTS);
        });
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent.class, e -> e.registerAboveAll(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("baritoe", "alto_hud"), AltoClientEvents::renderHud));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> onTick());
        NeoForge.EVENT_BUS.addListener(ScreenEvent.KeyPressed.Pre.class, AltoClientEvents::onScreenKey);
    }

    private static Alto alto() {
        if (Minecraft.getInstance().player == null) {
            return null;
        }
        Alto alto = ((Baritone) BaritoneAPI.getProvider().getPrimaryBaritone()).getAlto();
        return alto != null && Baritone.settings().altoEnabled.value ? alto : null;
    }

    private static void stopAll() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        Alto alto = ((Baritone) BaritoneAPI.getProvider().getPrimaryBaritone()).getAlto();
        boolean script = alto.scripts().stop("stopped with the stop key");
        alto.process().cancelJob("stopped with the stop key");
        BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
        baritone.alto.AltoLog.chat("Stopped" + (script ? " the script," : "") + " Alto and Baritone (stop key).");
    }

    private static void renderHud(net.minecraft.client.gui.GuiGraphics g, net.minecraft.client.DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !Baritone.settings().altoHud.value || !Baritone.settings().altoEnabled.value) {
            return;
        }
        Alto alto = ((Baritone) BaritoneAPI.getProvider().getPrimaryBaritone()).getAlto();
        java.util.List<String> lines;
        try {
            lines = alto.hudLines();
        } catch (RuntimeException e) {
            return;
        }
        if (lines.isEmpty()) {
            return;
        }
        int y = g.guiHeight() / 3;
        int w = 0;
        for (String l : lines) {
            w = Math.max(w, mc.font.width(l));
        }
        g.fill(2, y - 2, 6 + w, y + lines.size() * 10, 0x80000000);
        for (String l : lines) {
            g.drawString(mc.font, l, 4, y, 0xFFFFFF);
            y += 10;
        }
    }

    private static void onTick() {
        while (STOP.consumeClick()) {
            stopAll();
        }
        while (HUD.consumeClick()) {
            if (Minecraft.getInstance().screen == null) {
                Baritone.settings().altoHud.value = !Baritone.settings().altoHud.value;
                baritone.api.utils.SettingsUtil.save(Baritone.settings());
            }
        }
        while (SCRIPTS.consumeClick()) {
            if (Minecraft.getInstance().screen == null && Minecraft.getInstance().player != null) {
                Minecraft.getInstance().setScreen(new ScriptRunnerScreen(null));
            }
        }
        while (PICK.consumeClick()) {
            Alto alto = alto();
            if (alto != null && Minecraft.getInstance().screen == null) {
                alto.picker().toggleMarkAtCrosshair();
            }
        }
    }

    private static void onScreenKey(ScreenEvent.KeyPressed.Pre e) {
        if (!(e.getScreen() instanceof ChatScreen) && STOP.isActiveAndMatches(InputConstants.getKey(e.getKeyCode(), e.getScanCode()))
                && !(e.getScreen().getFocused() instanceof net.minecraft.client.gui.components.EditBox)) {
            stopAll(); // works in container screens too (Alto may have one open)
            return;
        }
        if (e.getScreen() instanceof ChatScreen || !PICK.isActiveAndMatches(InputConstants.getKey(e.getKeyCode(), e.getScanCode()))) {
            return;
        }
        // Do not steal the key while a text field (EMI search, sign, anvil name...) has focus.
        if (e.getScreen().getFocused() instanceof net.minecraft.client.gui.components.EditBox box && box.isFocused()) {
            return;
        }
        Alto alto = alto();
        if (alto == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        double mx = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
        double my = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
        for (ItemPicker.HoverProvider p : alto.picker().providers()) {
            ItemStack stack;
            try {
                stack = p.hovered(mx, my);
            } catch (Throwable t) {
                continue; // a broken integration must not break the key
            }
            if (stack != null && !stack.isEmpty()) {
                alto.picker().pick(stack);
                e.setCanceled(true);
                return;
            }
        }
    }
}
