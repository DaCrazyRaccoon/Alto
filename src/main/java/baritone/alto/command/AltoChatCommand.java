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

package baritone.alto.command;

import baritone.alto.AltoLog;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import net.minecraft.network.chat.Component;

/** Base for Alto's chat commands: their replies carry the [Alto] prefix instead of [Baritone]. */
public abstract class AltoChatCommand extends Command {

    protected AltoChatCommand(IBaritone baritone, String... names) {
        super(baritone, names);
    }

    @Override
    public void logDirect(boolean logAsToast, Component... components) {
        AltoLog.send(logAsToast, components);
    }
}
