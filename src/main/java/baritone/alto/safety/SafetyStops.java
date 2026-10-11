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

package baritone.alto.safety;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

import java.util.Optional;

/**
 * Extra 13, safety stops: while Baritone, an Alto job or a script is working, everything pauses when your health
 * drops to {@code altoSafetyHealth} or a hostile mob is within 6 blocks and can see you. Alto has no combat (by
 * design), so it stops and tells you instead; {@code #resume} continues, {@code #stop} ends everything.
 */
public final class SafetyStops {

    private static final double MOB_RANGE = 6;
    private static final int GRACE_TICKS = 20 * 30;

    private final Alto alto;
    private final PauseProcess process;
    private String reason;
    private boolean scriptPausedByUs;
    private int grace;

    public SafetyStops(Alto alto) {
        this.alto = alto;
        this.process = new PauseProcess(alto.baritone());
        alto.baritone().getPathingControlManager().registerProcess(process);
        alto.addTickListener(a -> tick());
    }

    public boolean tripped() {
        return reason != null;
    }

    public String reason() {
        return reason;
    }

    private boolean working() {
        var run = alto.scripts().current();
        boolean script = run != null && !run.status().isEnded();
        return script || alto.process().busy()
                || alto.baritone().getPathingControlManager().mostRecentInControl().filter(p -> p != process).isPresent();
    }

    private void tick() {
        if (grace > 0) {
            grace--;
        }
        LocalPlayer p = alto.ctx().player();
        if (p == null || reason != null || grace > 0 || !Baritone.settings().altoSafetyStops.value || !alto.enabled() || !working()) {
            return;
        }
        String why = check(p);
        if (why != null) {
            trip(why);
        }
    }

    private String check(LocalPlayer p) {
        if (p.isCreative() || p.isSpectator()) {
            return null;
        }
        if (p.getHealth() <= Baritone.settings().altoSafetyHealth.value && p.getHealth() < p.getMaxHealth()) {
            return "health is low (" + Math.round(p.getHealth()) + "/" + Math.round(p.getMaxHealth()) + ")";
        }
        Optional<Mob> mob = p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(MOB_RANGE),
                m -> m instanceof Enemy && m.isAlive() && m.distanceTo(p) <= MOB_RANGE && m.hasLineOfSight(p)).stream().findFirst();
        return mob.map(m -> "a hostile " + m.getType().getDescription().getString() + " is " + Math.round(m.distanceTo(p)) + " blocks away").orElse(null);
    }

    /** Pauses everything (also used by the self-test). */
    public void trip(String why) {
        reason = why;
        var run = alto.scripts().current();
        scriptPausedByUs = run != null && alto.scripts().pause();
        AltoLog.warn("Safety stop: " + why + ". Everything is paused. Type #resume when it is safe, or #stop.");
    }

    /** #resume: continue after a safety stop. False if there was none. */
    public boolean resume() {
        if (reason == null) {
            return false;
        }
        reason = null;
        grace = GRACE_TICKS; // do not stop again at once for the same reason
        if (scriptPausedByUs) {
            alto.scripts().resume();
        }
        scriptPausedByUs = false;
        return true;
    }

    /** #stop: forget a safety stop (everything was cancelled anyway). */
    public void clear() {
        reason = null;
        scriptPausedByUs = false;
    }

    /** Holds every other process while a safety stop is active (like #pause, but from Alto). */
    private final class PauseProcess extends BaritoneProcessHelper {
        PauseProcess(baritone.api.IBaritone baritone) {
            super((Baritone) baritone);
        }

        @Override
        public boolean isActive() {
            return reason != null;
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
            return 10;
        }

        @Override
        public void onLostControl() {
        }

        @Override
        public String displayName0() {
            return "Safety stop: " + reason;
        }
    }
}
