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

package baritone.alto.selftest;

import baritone.alto.Alto;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * One named in-game self-test. {@link #setup} runs on the integrated server thread and builds the test situation;
 * {@link #start} kicks off the behaviour under test through normal Alto/Baritone entry points (client thread);
 * {@link #check} is called every client tick until it returns PASS or FAIL or the timeout passes.
 * <p>
 * Only the setup uses server access (it is a test fixture, like a creative player building a test rig). The
 * behaviour under test always goes through the normal client packets.
 */
public abstract class Scenario {

    public enum Outcome { RUNNING, PASS, FAIL }

    public record Verdict(Outcome outcome, String message) {
        public static final Verdict RUNNING = new Verdict(Outcome.RUNNING, "");

        public static Verdict pass(String message) {
            return new Verdict(Outcome.PASS, message);
        }

        public static Verdict fail(String message) {
            return new Verdict(Outcome.FAIL, message);
        }
    }

    public abstract String name();

    public abstract String description();

    /** Seconds before the scenario fails as timed out. */
    public int timeoutSeconds() {
        return 120;
    }

    /** Server thread: build the test situation around {@code origin} (the player's feet). */
    public abstract void setup(ServerLevel level, ServerPlayer player, BlockPos origin);

    /** Client thread, once after setup: start the behaviour under test. */
    public abstract void start(Alto alto, BlockPos origin);

    /** Client thread, every tick. */
    public abstract Verdict check(Alto alto, BlockPos origin, int ticks);

    /** Client thread, always called at the end (restore settings, stop jobs). */
    public void cleanup(Alto alto) {
    }
}
