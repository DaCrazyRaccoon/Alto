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

import java.util.EnumMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Rate limiter for every packet-producing action Alto takes (good-citizen rule: conservative, configurable,
 * deterministic; no random jitter). One token bucket per {@link Kind}: a bucket holds at most {@code burst} tokens
 * and refills at {@code perSecond}. An action may run only when {@link #tryAcquire} returns true.
 * <p>
 * On top of the per-kind limits, {@link #slowDown(double)} scales every rate down while the server lags
 * (see {@code LagMonitor}); {@code 1.0} = normal, {@code 0.25} = a quarter of the normal rate.
 * Pure Java with an injectable clock so it can be unit tested.
 */
public final class Pacer {

    public enum Kind {
        /** A click in a container menu (pick up, quick move, swap, throw, drag step). */
        CONTAINER_CLICK,
        /** A recipe-book placement packet. */
        RECIPE_PLACE,
        /** Using an item on a block (opening a container, placing, setting a filter). */
        BLOCK_USE,
        /** Opening a container (counted in addition to BLOCK_USE so reopen loops stay slow). */
        CONTAINER_OPEN,
        /** Any other packet Alto sends deliberately (e.g. a train-map request). */
        OTHER
    }

    private static final class Bucket {
        double perSecond;
        double burst;
        double tokens;
        long lastNanos;
    }

    private final LongSupplier nanoClock;
    private final Map<Kind, Bucket> buckets = new EnumMap<>(Kind.class);
    private double slowDown = 1.0;
    private long acquired;
    private long denied;

    public Pacer(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        for (Kind k : Kind.values()) {
            Bucket b = new Bucket();
            b.perSecond = 4;
            b.burst = 1;
            b.tokens = 1;
            b.lastNanos = nanoClock.getAsLong();
            buckets.put(k, b);
        }
    }

    public static Pacer systemClock() {
        return new Pacer(System::nanoTime);
    }

    /**
     * @param perSecond sustained rate (values below 0.05 are raised to 0.05)
     * @param burst     how many actions may happen back to back after a pause (at least 1)
     */
    public void configure(Kind kind, double perSecond, int burst) {
        Bucket b = buckets.get(kind);
        refill(b);
        b.perSecond = Math.max(0.05, perSecond);
        b.burst = Math.max(1, burst);
        b.tokens = Math.min(b.tokens, b.burst);
    }

    /** 1.0 = normal speed; lower values slow every kind down (lag backoff). Clamped to [0.05, 1]. */
    public void slowDown(double factor) {
        Bucket[] all = buckets.values().toArray(new Bucket[0]);
        for (Bucket b : all) {
            refill(b);
        }
        this.slowDown = Math.max(0.05, Math.min(1.0, factor));
    }

    public double slowDownFactor() {
        return slowDown;
    }

    /** Takes a token if one is available. */
    public boolean tryAcquire(Kind kind) {
        Bucket b = buckets.get(kind);
        refill(b);
        if (b.tokens >= 1) {
            b.tokens -= 1;
            acquired++;
            return true;
        }
        denied++;
        return false;
    }

    /** Whether {@link #tryAcquire} would succeed now (does not consume). */
    public boolean ready(Kind kind) {
        Bucket b = buckets.get(kind);
        refill(b);
        return b.tokens >= 1;
    }

    /** Nanoseconds until the next token of this kind is available (0 if available now). */
    public long nanosUntilReady(Kind kind) {
        Bucket b = buckets.get(kind);
        refill(b);
        if (b.tokens >= 1) {
            return 0;
        }
        double rate = b.perSecond * slowDown;
        return (long) Math.ceil((1 - b.tokens) / rate * 1e9);
    }

    public double rate(Kind kind) {
        return buckets.get(kind).perSecond * slowDown;
    }

    public long acquiredCount() {
        return acquired;
    }

    public long deniedCount() {
        return denied;
    }

    private void refill(Bucket b) {
        long now = nanoClock.getAsLong();
        long elapsed = now - b.lastNanos;
        if (elapsed > 0) {
            b.tokens = Math.min(b.burst, b.tokens + elapsed / 1e9 * b.perSecond * slowDown);
            b.lastNanos = now;
        }
    }
}
