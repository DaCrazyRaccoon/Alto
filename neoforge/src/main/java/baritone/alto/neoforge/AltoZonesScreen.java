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
import baritone.alto.storage.Zones;
import baritone.api.BaritoneAPI;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Homes and storage zones of the current world: list, remove, set a home where you stand. Zones are drawn with
 * {@code #sel} and {@code #home zone} (see the user guide); this screen manages what exists.
 */
public final class AltoZonesScreen extends Screen {

    private final Screen parent;

    public AltoZonesScreen(Screen parent) {
        super(Component.literal("Homes and storage zones"));
        this.parent = parent;
    }

    private static Alto alto() {
        return ((Baritone) BaritoneAPI.getProvider().getPrimaryBaritone()).getAlto();
    }

    @Override
    protected void init() {
        clearWidgets();
        if (minecraft.player == null) {
            addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(width / 2 - 75, height - 28, 150, 20).build());
            return;
        }
        Zones zones = alto().storage().zones();
        int y = 40;
        for (Zones.Home h : List.copyOf(zones.homes())) {
            addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
                zones.clearHome(h.name());
                alto().storage().zonesChanged();
                init();
            }).bounds(width / 2 + 110, y, 60, 18).build());
            y += 20;
        }
        y += 16;
        for (Zones.Zone z : List.copyOf(zones.zones())) {
            addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
                zones.removeZone(z.name());
                alto().storage().zonesChanged();
                init();
            }).bounds(width / 2 + 110, y, 60, 18).build());
            y += 20;
        }
        addRenderableWidget(Button.builder(Component.literal("Set home here"), b -> {
            var p = minecraft.player;
            String name = zones.homes().isEmpty() ? "home" : "home" + (zones.homes().size() + 1);
            zones.setHome(new Zones.Home(name, alto().storage().dimension(), p.getBlockX(), p.getBlockY(), p.getBlockZ()));
            alto().storage().zonesChanged();
            init();
        }).bounds(width / 2 - 154, height - 28, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(width / 2 + 4, height - 28, 150, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        if (minecraft.player == null) {
            g.drawCenteredString(font, "Join a world to manage its homes and zones.", width / 2, 40, 0xAAAAAA);
            return;
        }
        Zones zones = alto().storage().zones();
        int y = 26;
        g.drawString(font, "§eHomes" + (zones.homes().isEmpty() ? " §7(none: #home set <name>)" : ""), width / 2 - 170, y + 2, 0xFFFFFF);
        y += 14;
        for (Zones.Home h : zones.homes()) {
            g.drawString(font, h.name() + "  §7" + h.x() + " " + h.y() + " " + h.z() + " (" + h.dimension() + ")", width / 2 - 170, y + 5, 0xFFFFFF);
            y += 20;
        }
        y += 2;
        g.drawString(font, "§eStorage zones" + (zones.zones().isEmpty() ? " §7(none: #sel, then #home zone add <name>)" : ""), width / 2 - 170, y + 2, 0xFFFFFF);
        y += 14;
        for (Zones.Zone z : zones.zones()) {
            g.drawString(font, font.plainSubstrByWidth(z.describe(), 270), width / 2 - 170, y + 5, 0xFFFFFF);
            y += 20;
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
