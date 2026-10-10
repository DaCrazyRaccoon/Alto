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

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacingTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void defaultIsFourClicksPerSecond() {
        AtomicLong now = new AtomicLong();
        Pacer p = new Pacer(now::get);
        p.configure(Pacer.Kind.CONTAINER_CLICK, 4, 1);
        int clicks = 0;
        for (int tick = 0; tick < 20 * 10; tick++) { // 10 seconds of client ticks
            now.addAndGet(SECOND / 20);
            if (p.tryAcquire(Pacer.Kind.CONTAINER_CLICK)) {
                clicks++;
            }
        }
        assertTrue(clicks >= 39 && clicks <= 41, "clicks in 10 s: " + clicks);
    }

    @Test
    void kindsAreIndependent() {
        AtomicLong now = new AtomicLong();
        Pacer p = new Pacer(now::get);
        assertTrue(p.tryAcquire(Pacer.Kind.CONTAINER_CLICK));
        assertFalse(p.tryAcquire(Pacer.Kind.CONTAINER_CLICK));
        assertTrue(p.tryAcquire(Pacer.Kind.RECIPE_PLACE));
    }

    @Test
    void burstAllowsShortRunsButNotMore() {
        AtomicLong now = new AtomicLong();
        Pacer p = new Pacer(now::get);
        p.configure(Pacer.Kind.CONTAINER_CLICK, 2, 3);
        now.addAndGet(10 * SECOND);
        assertTrue(p.tryAcquire(Pacer.Kind.CONTAINER_CLICK));
        assertTrue(p.tryAcquire(Pacer.Kind.CONTAINER_CLICK));
        assertTrue(p.tryAcquire(Pacer.Kind.CONTAINER_CLICK));
        assertFalse(p.tryAcquire(Pacer.Kind.CONTAINER_CLICK));
    }

    @Test
    void slowDownReducesTheRate() {
        AtomicLong now = new AtomicLong();
        Pacer p = new Pacer(now::get);
        p.configure(Pacer.Kind.CONTAINER_CLICK, 4, 1);
        p.slowDown(0.25);
        int clicks = 0;
        for (int tick = 0; tick < 200; tick++) {
            now.addAndGet(SECOND / 20);
            if (p.tryAcquire(Pacer.Kind.CONTAINER_CLICK)) {
                clicks++;
            }
        }
        assertTrue(clicks >= 9 && clicks <= 11, "clicks in 10 s at quarter speed: " + clicks);
        assertTrue(p.nanosUntilReady(Pacer.Kind.CONTAINER_CLICK) > 0);
    }

    @Test
    void slowDownIsClamped() {
        Pacer p = new Pacer(() -> 0);
        p.slowDown(0);
        assertEquals(0.05, p.slowDownFactor(), 1e-9);
        p.slowDown(7);
        assertEquals(1.0, p.slowDownFactor(), 1e-9);
    }

    @Test
    void backoffDoublesUpToTheCapThenGivesUp() {
        Backoff b = new Backoff(5, 30, 4);
        b.fail();
        assertEquals(5, b.currentDelay());
        b.fail();
        assertEquals(10, b.currentDelay());
        b.fail();
        assertEquals(20, b.currentDelay());
        assertFalse(b.exhausted());
        b.fail();
        assertEquals(30, b.currentDelay());
        assertTrue(b.exhausted());
        int waited = 0;
        while (b.waiting()) {
            waited++;
        }
        assertEquals(30, waited);
        b.reset();
        assertFalse(b.exhausted());
        assertFalse(b.waiting());
    }
}
