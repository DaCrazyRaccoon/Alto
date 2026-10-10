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

package baritone.alto.core;

/**
 * Retry policy with exponential backoff measured in client ticks: attempt 1 waits {@code baseTicks}, attempt 2
 * twice that, and so on up to {@code maxTicks}; after {@code maxAttempts} failures {@link #exhausted()} is true.
 * Deterministic on purpose (no jitter): the brief asks for plain, predictable pacing.
 */
public final class Backoff {

    private final int baseTicks;
    private final int maxTicks;
    private final int maxAttempts;
    private int failures;
    private int waitLeft;

    public Backoff(int baseTicks, int maxTicks, int maxAttempts) {
        this.baseTicks = Math.max(1, baseTicks);
        this.maxTicks = Math.max(this.baseTicks, maxTicks);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /** Records a failure and starts the next wait. */
    public void fail() {
        failures++;
        waitLeft = currentDelay();
    }

    /** Forget earlier failures (after a success). */
    public void reset() {
        failures = 0;
        waitLeft = 0;
    }

    /** Call once per tick; returns true while still waiting. */
    public boolean waiting() {
        if (waitLeft > 0) {
            waitLeft--;
            return true;
        }
        return false;
    }

    public boolean exhausted() {
        return failures >= maxAttempts;
    }

    public int failures() {
        return failures;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** Delay that the most recent failure imposed. */
    public int currentDelay() {
        if (failures <= 0) {
            return 0;
        }
        long d = (long) baseTicks << Math.min(20, failures - 1);
        return (int) Math.min(maxTicks, d);
    }
}
