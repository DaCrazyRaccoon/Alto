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

import baritone.alto.Alto;
import baritone.api.utils.IPlayerContext;

/**
 * A unit of work Alto performs over several ticks ("craft 8 planks", "place a crafting table").
 * <p>
 * Tasks are small state machines driven by {@link #tick}. They must re-check the live world every tick rather than
 * trusting what they saw earlier, and must leave no screen open or item on the cursor when cancelled.
 */
public abstract class AltoTask {

    protected final Alto alto;
    protected final IPlayerContext ctx;
    private String detail = "";
    private int done;
    private int total;

    protected AltoTask(Alto alto) {
        this.alto = alto;
        this.ctx = alto.ctx();
    }

    /** Short name for chat and the HUD: "Craft 8x oak_planks". */
    public abstract String name();

    /**
     * Advance the task.
     *
     * @param calcFailed true if Baritone failed to compute a path for this task's goal last tick
     */
    public abstract Step tick(boolean calcFailed);

    /** Stop now; close menus, release keys. Called at most once, also after success or failure. */
    public void cancel() {
    }

    /** Current sub-step for the HUD ("waiting for the furnace"), may be empty. */
    public String detail() {
        return detail;
    }

    protected void detail(String text) {
        this.detail = text == null ? "" : text;
    }

    /** Progress counters for the HUD; total 0 = unknown. */
    public int progressDone() {
        return done;
    }

    public int progressTotal() {
        return total;
    }

    protected void progress(int done, int total) {
        this.done = done;
        this.total = total;
    }
}
