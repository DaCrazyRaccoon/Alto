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

package baritone.alto.net;

import baritone.Baritone;
import baritone.alto.AltoLog;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shows, once per server address, a reminder to check that server's rules about automation before using Alto's
 * automatic features. The list of addresses already shown is kept in {@code baritone/alto/server-notices.json}.
 * Singleplayer and LAN worlds hosted by this client never show it.
 */
public final class ServerNotice {

    private final Path file;
    private Set<String> shown;

    public ServerNotice(Path altoDir) {
        this.file = altoDir.resolve("server-notices.json");
    }

    /** Call after joining a world. */
    public void onJoin() {
        Minecraft mc = Minecraft.getInstance();
        if (!Baritone.settings().altoServerNotice.value || mc.hasSingleplayerServer()) {
            return;
        }
        ServerData server = mc.getCurrentServer();
        if (server == null) {
            return;
        }
        String address = server.ip.trim().toLowerCase(Locale.ROOT);
        load();
        if (!shown.add(address)) {
            return;
        }
        save();
        baritone.api.utils.Helper.HELPER.logDirect("[Alto] First time on " + address
                + ": Baritone's Alto features click containers, craft and move items automatically. "
                + "Please check this server's rules about automation/bots before using them. "
                + "(Shown once per server; turn off with #set altoServerNotice false.)", ChatFormatting.YELLOW);
    }

    private void load() {
        if (shown != null) {
            return;
        }
        shown = new TreeSet<>();
        try {
            if (Files.exists(file)) {
                Set<String> s = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<Set<String>>() {}.getType());
                if (s != null) {
                    shown.addAll(s);
                }
            }
        } catch (IOException | RuntimeException e) {
            AltoLog.info("could not read " + file + ": " + e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new Gson().toJson(shown), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AltoLog.info("could not write " + file + ": " + e);
        }
    }
}
