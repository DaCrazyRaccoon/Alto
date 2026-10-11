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

package baritone.alto;

import baritone.Baritone;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Alto's logging: chat messages carry an {@code [Alto]} prefix in Baritone's style (instead of Baritone's own
 * prefix) and go through Baritone's chat settings ({@code logger}, {@code logAsToast}, {@code useMessageTag});
 * debug lines only go to the game log, and only with {@code altoDebug}.
 * All chat output is local to the player; Alto never sends chat to the server.
 */
public final class AltoLog {

    private static final Logger LOG = LoggerFactory.getLogger("Alto");

    private AltoLog() {
    }

    /** "[Alto]", coloured like Baritone's "[Baritone]". */
    public static Component prefix() {
        MutableComponent name = Component.literal("Alto");
        name.setStyle(name.getStyle().withColor(ChatFormatting.LIGHT_PURPLE));
        MutableComponent prefix = Component.literal("");
        prefix.setStyle(name.getStyle().withColor(ChatFormatting.DARK_PURPLE));
        prefix.append("[");
        prefix.append(name);
        prefix.append("]");
        return prefix;
    }

    /** Components to chat with the [Alto] prefix (or as a toast when Baritone's logAsToast is on). */
    public static void chat(Component... components) {
        send(Baritone.settings().logAsToast.value, components);
    }

    /** Components to chat with the [Alto] prefix, or as a toast. */
    public static void send(boolean asToast, Component... components) {
        MutableComponent body = Component.literal("");
        for (Component c : components) {
            body.append(c);
        }
        if (asToast) {
            Minecraft.getInstance().execute(() -> Baritone.settings().toaster.value.accept(prefix(), body));
            return;
        }
        MutableComponent line = Component.literal("");
        if (!Baritone.settings().useMessageTag.value) {
            line.append(prefix());
            line.append(Component.literal(" "));
        }
        line.append(body);
        Minecraft.getInstance().execute(() -> Baritone.settings().logger.value.accept(line));
    }

    /** A message to chat with the [Alto] prefix, one chat line per text line. */
    public static void chat(String message, ChatFormatting color) {
        for (String text : message.split("\n")) {
            MutableComponent c = Component.literal(text.replace("\t", "    "));
            c.setStyle(c.getStyle().withColor(color));
            chat(c);
        }
    }

    public static void chat(String message) {
        chat(message, ChatFormatting.GRAY);
        LOG.info(message);
    }

    public static void warn(String message) {
        chat(message, ChatFormatting.GOLD);
        LOG.warn(message);
    }

    public static void error(String message) {
        chat(message, ChatFormatting.RED);
        LOG.warn(message);
    }

    public static void debug(String message) {
        if (Baritone.settings().altoDebug.value) {
            LOG.info("[debug] {}", message);
        }
    }

    public static void info(String message) {
        LOG.info(message);
    }

    public static void exception(String what, Throwable t) {
        LOG.error(what, t);
        chat(what + ": " + t, ChatFormatting.RED);
    }
}
