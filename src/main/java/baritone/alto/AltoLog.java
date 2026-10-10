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
import baritone.api.utils.Helper;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Alto's logging: chat messages go through Baritone's own chat helper (so they get the Baritone prefix and
 * respect its toast settings); debug lines only go to the game log, and only with {@code altoDebug}.
 * All chat output is local to the player; Alto never sends chat to the server.
 */
public final class AltoLog {

    private static final Logger LOG = LoggerFactory.getLogger("Alto");

    private AltoLog() {
    }

    public static void chat(String message) {
        Helper.HELPER.logDirect("[Alto] " + message);
        LOG.info(message);
    }

    public static void warn(String message) {
        Helper.HELPER.logDirect("[Alto] " + message, ChatFormatting.GOLD);
        LOG.warn(message);
    }

    public static void error(String message) {
        Helper.HELPER.logDirect("[Alto] " + message, ChatFormatting.RED);
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
        Helper.HELPER.logDirect("[Alto] " + what + ": " + t, ChatFormatting.RED);
    }
}
