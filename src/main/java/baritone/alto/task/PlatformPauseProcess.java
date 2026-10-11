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

package baritone.alto.task;

import baritone.Baritone;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.utils.BaritoneProcessHelper;

/**
 * Extra 8, moving platforms: while you stand on a moving contraption (Create elevator, piston or gantry platform,
 * a bearing...), Baritone stops giving movement input so the platform carries you; when it stops, the process that
 * was working plans again from the new position. Temporary, so that process keeps its state.
 */
public final class PlatformPauseProcess extends BaritoneProcessHelper {

    private volatile boolean onMovingPlatform;
    private int stillTicks;

    public PlatformPauseProcess(Baritone baritone) {
        super(baritone);
    }

    /** Set every tick by the integration that can see contraptions (Create). */
    public void setOnMovingPlatform(boolean moving) {
        if (moving) {
            onMovingPlatform = true;
            stillTicks = 0;
        } else if (onMovingPlatform && ++stillTicks > 10) {
            onMovingPlatform = false; // half a second of standing still before walking again
        }
    }

    public boolean onMovingPlatform() {
        return onMovingPlatform;
    }

    @Override
    public boolean isActive() {
        return onMovingPlatform && Baritone.settings().altoEnabled.value && Baritone.settings().altoMovingPlatforms.value
                && baritone.getPathingControlManager().mostRecentInControl().isPresent(); // only while some job runs
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        baritone.getInputOverrideHandler().clearAllKeys();
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public double priority() {
        return 5; // above everything, like #pause: nothing may walk while the floor moves
    }

    @Override
    public void onLostControl() {
    }

    @Override
    public String displayName0() {
        return "Waiting while the platform moves";
    }
}
