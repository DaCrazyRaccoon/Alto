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
import baritone.alto.script.ScriptReport;
import baritone.alto.script.runtime.RunStatus;
import baritone.alto.scriptmc.ScriptManager;
import baritone.api.BaritoneAPI;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M5 script runner: the scripts in baritone/alto/scripts (re-read every second, so new or edited files show up),
 * their state, and the running script with pause/resume/stop. A new or changed script asks for confirmation and
 * shows what it may do before it runs.
 */
public final class ScriptRunnerScreen extends Screen {

    private final Screen parent;
    private final Map<String, String> states = new LinkedHashMap<>();
    private List<String> lastNames = List.of();
    private int ticks;

    public ScriptRunnerScreen(Screen parent) {
        super(Component.literal("Alto scripts"));
        this.parent = parent;
    }

    private static Alto alto() {
        return ((Baritone) BaritoneAPI.getProvider().getPrimaryBaritone()).getAlto();
    }

    private void refresh() {
        ScriptManager m = alto().scripts();
        states.clear();
        for (String name : m.names()) {
            try {
                ScriptReport r = m.load(name);
                states.put(name, !r.runnable() ? "§c" + r.errors().size() + " error(s)"
                        : m.isConfirmed(r.hash()) ? "§aready" : "§enew or changed: asks first");
            } catch (IOException | RuntimeException e) {
                states.put(name, "§cunreadable");
            }
        }
    }

    @Override
    protected void init() {
        clearWidgets();
        refresh();
        lastNames = alto().scripts().names();
        int y = 64;
        for (String name : states.keySet()) {
            addRenderableWidget(Button.builder(Component.literal("Check"), b -> check(name)).bounds(width / 2 + 60, y, 50, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Run"), b -> run(name)).bounds(width / 2 + 114, y, 50, 18).build());
            y += 22;
            if (y > height - 60) {
                break;
            }
        }
        addRenderableWidget(Button.builder(Component.literal("Pause / resume"), b -> {
            var run = alto().scripts().current();
            if (run != null && run.status() == RunStatus.PAUSED) {
                alto().scripts().resume();
            } else {
                alto().scripts().pause();
            }
        }).bounds(width / 2 - 154, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Stop"), b -> alto().scripts().stop("stopped in the script screen"))
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(width / 2 + 54, height - 28, 100, 20).build());
    }

    private void check(String name) {
        try {
            ScriptReport r = alto().scripts().load(name);
            List<String> lines = new ArrayList<>();
            if (!r.runnable()) {
                r.errors().forEach(d -> lines.add(d.toString()));
            } else {
                lines.addAll(alto().scripts().describe(r));
            }
            minecraft.setScreen(new ConfirmScreen(ok -> minecraft.setScreen(this), Component.literal(name),
                    Component.literal(String.join("\n", lines)), Component.literal("OK"), Component.literal("Back")));
        } catch (IOException e) {
            baritone.alto.AltoLog.chat("Cannot read " + name + ": " + e.getMessage());
        }
    }

    private void run(String name) {
        try {
            ScriptReport r = alto().scripts().load(name);
            if (r.runnable() && !alto().scripts().isConfirmed(r.hash())) {
                List<String> lines = alto().scripts().describe(r);
                minecraft.setScreen(new ConfirmScreen(yes -> {
                    if (yes) {
                        report(alto().scripts().start(name, r, true));
                        minecraft.setScreen(null);
                    } else {
                        minecraft.setScreen(this);
                    }
                }, Component.literal("Run '" + name + "' for the first time?"), Component.literal(String.join("\n", lines)),
                        Component.literal("Run it"), Component.literal("Cancel")));
                return;
            }
            report(alto().scripts().start(name, r, false));
            if (alto().scripts().current() != null && !alto().scripts().current().status().isEnded()) {
                minecraft.setScreen(null);
            }
        } catch (IOException e) {
            baritone.alto.AltoLog.chat("Cannot read " + name + ": " + e.getMessage());
        }
    }

    private static void report(List<String> problems) {
        if (problems != null) {
            problems.forEach(baritone.alto.AltoLog::chat);
        }
    }

    @Override
    public void tick() {
        if (++ticks % 20 == 0 && !alto().scripts().names().equals(lastNames)) {
            init(); // the folder changed
        } else if (ticks % 20 == 0) {
            refresh(); // a file was edited
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        ScriptManager m = alto().scripts();
        List<String> status = m.status();
        int y = 22;
        for (String s : status.subList(0, Math.min(3, status.size()))) {
            g.drawCenteredString(font, font.plainSubstrByWidth(s, width - 40), width / 2, y, 0xCCCCCC);
            y += 11;
        }
        if (states.isEmpty()) {
            g.drawCenteredString(font, "No scripts yet. Save scripts from alto-script-editor.html into:", width / 2, 70, 0xAAAAAA);
            g.drawCenteredString(font, m.folder().toString(), width / 2, 82, 0xAAAAAA);
            return;
        }
        y = 64;
        for (Map.Entry<String, String> e : states.entrySet()) {
            g.drawString(font, font.plainSubstrByWidth(e.getKey(), 150), width / 2 - 170, y + 5, 0xFFFFFF);
            g.drawString(font, e.getValue(), width / 2 - 10 - font.width(e.getValue()) + 60 - 60, y + 5, 0xFFFFFF);
            y += 22;
            if (y > height - 60) {
                break;
            }
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
