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
import baritone.api.Settings;
import baritone.api.utils.SettingsUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extra 11: Alto's settings in groups, with a search box and a reset button per setting, plus homes and storage
 * zones. Opened from the Mods list (Config) or with {@code #alto settings}. Changes apply at once and are saved to
 * Baritone's settings file when the screen closes.
 */
public final class AltoSettingsScreen extends Screen {

    /** Group title -> name fragments (first match wins; the rest goes to "Other"). */
    private static final Map<String, String[]> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("General", new String[]{"altoenabled", "altodebug", "altohud", "altoshowscreens", "altoselftest"});
        GROUPS.put("Scripts and safety", new String[]{"script", "safety"});
        GROUPS.put("Crafting and stations", new String[]{"alwaysusecraftingstation", "crafting", "station", "furnace", "fuel", "planner", "nevercons", "smelt"});
        GROUPS.put("Storage and upkeep", new String[]{"storage", "deposit", "restock", "keep", "zone", "eat", "food", "tool", "compress", "shulker", "supply"});
        GROUPS.put("Travel and farming", new String[]{"portal", "vehicle", "train", "platform", "farm", "crop", "climb"});
        GROUPS.put("Mods", new String[]{"create", "emi", "sophisticated", "claims", "corpse", "filter", "pick", "stockkeeper", "hazard", "compat"});
        GROUPS.put("Pacing (be kind to servers)", new String[]{"persecond", "click", "timeout", "retries", "lag", "pace", "open"});
    }

    private final Screen parent;
    private EditBox search;
    private SettingList list;

    public AltoSettingsScreen(Screen parent) {
        super(Component.literal("Baritone (Alto) settings"));
        this.parent = parent;
    }

    static List<Settings.Setting<?>> altoSettings() {
        List<Settings.Setting<?>> out = new ArrayList<>();
        for (Settings.Setting<?> s : Baritone.settings().allSettings) {
            String n = s.getName();
            if ((n.startsWith("alto") || n.equals("alwaysUseCraftingStation")) && !SettingsUtil.javaOnlySetting(s)) {
                out.add(s);
            }
        }
        return out;
    }

    static String groupOf(Settings.Setting<?> s) {
        String n = s.getName().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String[]> g : GROUPS.entrySet()) {
            for (String frag : g.getValue()) {
                if (n.contains(frag)) {
                    return g.getKey();
                }
            }
        }
        return "Other";
    }

    @Override
    protected void init() {
        search = new EditBox(font, width / 2 - 100, 22, 200, 18, Component.literal("Search"));
        search.setHint(Component.literal("Search settings..."));
        search.setResponder(q -> list.rebuild(q));
        addRenderableWidget(search);
        list = new SettingList(minecraft, width, height - 46 - 34, 46, 24);
        addRenderableWidget(list);
        list.rebuild("");
        addRenderableWidget(Button.builder(Component.literal("Homes and storage zones"), b -> minecraft.setScreen(new AltoZonesScreen(this)))
                .bounds(width / 2 - 154, height - 28, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 + 4, height - 28, 150, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        SettingsUtil.save(Baritone.settings());
        minecraft.setScreen(parent);
    }

    // ------------------------------------------------------------------------------------------ list

    final class SettingList extends ContainerObjectSelectionList<Row> {
        SettingList(Minecraft mc, int w, int h, int top, int itemHeight) {
            super(mc, w, h, top, itemHeight);
        }

        void rebuild(String query) {
            clearEntries();
            String q = query.trim().toLowerCase(Locale.ROOT);
            Map<String, List<Settings.Setting<?>>> byGroup = new LinkedHashMap<>();
            GROUPS.keySet().forEach(k -> byGroup.put(k, new ArrayList<>()));
            byGroup.put("Other", new ArrayList<>());
            for (Settings.Setting<?> s : altoSettings()) {
                if (q.isEmpty() || s.getName().toLowerCase(Locale.ROOT).contains(q)) {
                    byGroup.get(groupOf(s)).add(s);
                }
            }
            byGroup.forEach((group, settings) -> {
                if (!settings.isEmpty()) {
                    addEntry(new Header(group));
                    settings.forEach(s -> addEntry(new SettingRow(s)));
                }
            });
            setScrollAmount(0);
        }

        @Override
        public int getRowWidth() {
            return Math.min(420, width - 20);
        }
    }

    abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
    }

    final class Header extends Row {
        private final String text;

        Header(String text) {
            this.text = text;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int w, int h, int mx, int my, boolean hover, float pt) {
            g.drawString(font, "§e§l" + text, left, top + 8, 0xFFFFFF);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    final class SettingRow extends Row {
        private final Settings.Setting<?> setting;
        private final AbstractWidget value;
        private final Button reset;

        @SuppressWarnings("unchecked")
        SettingRow(Settings.Setting<?> setting) {
            this.setting = setting;
            if (setting.value instanceof Boolean) {
                Settings.Setting<Boolean> b = (Settings.Setting<Boolean>) setting;
                value = CycleButton.onOffBuilder(b.value).displayOnlyValue().create(0, 0, 120, 20, Component.literal(setting.getName()),
                        (btn, v) -> b.value = v);
            } else {
                EditBox box = new EditBox(font, 0, 0, 118, 18, Component.literal(setting.getName()));
                box.setMaxLength(2000);
                box.setValue(SettingsUtil.settingValueToString(setting));
                box.setResponder(text -> {
                    try {
                        SettingsUtil.parseAndApply(Baritone.settings(), setting.getName(), text);
                        box.setTextColor(0xE0E0E0);
                    } catch (RuntimeException e) {
                        box.setTextColor(0xFF6060); // not valid yet; the setting keeps its old value
                    }
                });
                value = box;
            }
            value.setTooltip(Tooltip.create(Component.literal(setting.getName() + "\nType: " + SettingsUtil.settingTypeToString(setting)
                    + "\nDefault: " + SettingsUtil.settingDefaultToString(setting))));
            reset = Button.builder(Component.literal("↺"), b -> {
                setting.reset();
                if (value instanceof EditBox box) {
                    box.setValue(SettingsUtil.settingValueToString(setting));
                } else if (value instanceof CycleButton<?> c && setting.value instanceof Boolean v) {
                    @SuppressWarnings("unchecked") CycleButton<Boolean> cb = (CycleButton<Boolean>) c;
                    cb.setValue(v);
                }
            }).bounds(0, 0, 20, 20).tooltip(Tooltip.create(Component.literal("Reset to default"))).build();
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int w, int h, int mx, int my, boolean hover, float pt) {
            boolean changed = !SettingsUtil.settingValueToString(setting).equals(SettingsUtil.settingDefaultToString(setting));
            String label = (changed ? "§f" : "§7") + setting.getName();
            g.drawString(font, font.plainSubstrByWidth(label, w - 150), left, top + 6, 0xFFFFFF);
            value.setX(left + w - 146);
            value.setY(top + 1);
            value.render(g, mx, my, pt);
            reset.setX(left + w - 22);
            reset.setY(top);
            reset.active = changed;
            reset.render(g, mx, my, pt);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(value, reset);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(value, reset);
        }
    }
}
