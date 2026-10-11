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
import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.utils.BaritoneProcessHelper;

/**
 * The Baritone process that runs Alto {@link Job}s.
 * <p>
 * Priority rules (see research.md §1.3): Baritone resets every lower process when a normal process takes control,
 * but leaves them alone when a <i>temporary</i> one does. So:
 * <ul>
 *     <li>jobs you start yourself (#craft) are normal processes at the default priority: a newer command replaces
 *     them, #stop cancels them;</li>
 *     <li>supply runs for the builder are temporary and rank just above the builder (-0.5 vs -1) but below
 *     #pause (0), so the build keeps its state and continues afterwards, and #pause still pauses everything.</li>
 * </ul>
 */
public final class AltoProcess extends BaritoneProcessHelper {

    public static final double SUPPLY_PRIORITY = -0.5;
    /** While a job lets Baritone's miner work for it, it must rank above the miner or the miner would reset it. */
    public static final double DEFERRING_PRIORITY = -0.75;

    private final Alto alto;
    private Job job;
    private String lastDisplay = "";
    private boolean deferring;

    public AltoProcess(Baritone baritone, Alto alto) {
        super(baritone);
        this.alto = alto;
    }

    /** Starts a job, replacing (and cancelling) the current one. */
    public void start(Job newJob) {
        if (job != null) {
            finish(false, "replaced by " + newJob.root().name());
        }
        job = newJob;
        AltoLog.chat("Started: " + newJob.root().name());
    }

    public Job job() {
        return job;
    }

    public boolean busy() {
        return job != null;
    }

    /** Cancels the current job (e.g. a safety stop or #alto stop). */
    public void cancelJob(String reason) {
        if (job != null) {
            finish(false, reason);
        }
    }

    @Override
    public boolean isActive() {
        if (job != null && !Baritone.settings().altoEnabled.value) {
            finish(false, "Alto was switched off (altoEnabled false)");
        }
        return job != null && ctx.player() != null && ctx.world() != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        Job current = job;
        if (current == null) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        deferring = false;
        Step step;
        try {
            step = current.root().tick(calcFailed);
        } catch (Throwable t) {
            AltoLog.exception("Alto task crashed (" + current.root().name() + ")", t);
            step = Step.failure("internal error: " + t);
        }
        lastDisplay = current.root().name() + (current.root().detail().isEmpty() ? "" : " - " + current.root().detail());
        switch (step.kind()) {
            case WALK:
                return new PathingCommand(step.goal(), PathingCommandType.SET_GOAL_AND_PATH);
            case DEFER:
                deferring = true;
                return new PathingCommand(null, PathingCommandType.DEFER);
            case SUCCESS:
                finish(true, step.message());
                // DEFER, not a pause: the job is gone, so this process no longer counts as temporary; taking control
                // now would make Baritone reset the processes below (the builder we just supplied).
                return new PathingCommand(null, PathingCommandType.DEFER);
            case FAILURE:
                finish(false, step.message());
                return new PathingCommand(null, PathingCommandType.DEFER);
            case STAY:
            default:
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
    }

    private void finish(boolean success, String message) {
        Job ended = job;
        job = null;
        if (ended == null) {
            return;
        }
        try {
            ended.root().cancel();
        } catch (Throwable t) {
            AltoLog.exception("cleanup failed", t);
        }
        String text = ended.root().name() + (message == null || message.isEmpty() ? "" : ": " + message);
        if (success) {
            AltoLog.chat("Done: " + text);
        } else {
            AltoLog.error("Stopped: " + text);
        }
        try {
            ended.onFinish().accept(success, message == null ? "" : message);
        } catch (Throwable t) {
            AltoLog.exception("job callback failed", t);
        }
        alto.onJobFinished(ended, success, message);
    }

    @Override
    public boolean isTemporary() {
        return job != null && job.temporary();
    }

    @Override
    public double priority() {
        if (job == null) {
            return IBaritoneProcess.DEFAULT_PRIORITY;
        }
        if (job.temporary()) {
            return SUPPLY_PRIORITY;
        }
        return deferring ? DEFERRING_PRIORITY : IBaritoneProcess.DEFAULT_PRIORITY;
    }

    @Override
    public void onLostControl() {
        if (job != null) {
            finish(false, "cancelled");
        }
    }

    @Override
    public String displayName0() {
        return "Alto: " + (job == null ? "idle" : lastDisplay);
    }
}
