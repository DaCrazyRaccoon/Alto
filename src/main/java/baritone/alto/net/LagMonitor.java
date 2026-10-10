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

package baritone.alto.net;

import baritone.api.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * Estimates how laggy the server and the connection are, from information every client receives anyway:
 * the server's time packets (sent once per second) and the player's own ping from the tab list.
 * <p>
 * Packet timing is recorded from the network thread (volatile fields only); everything else is read on the client
 * thread. The result drives {@code Pacer.slowDown} and longer confirmation waits, so Alto backs off when the
 * server struggles.
 */
public final class LagMonitor {

    private volatile long lastTimePacketNanos;
    private volatile long lastServerGameTime = -1;
    private volatile double tps = 20.0;
    private volatile long lastAnyPacketNanos = System.nanoTime();

    /** Called for every received packet (network thread). */
    public void onPacket() {
        lastAnyPacketNanos = System.nanoTime();
    }

    /** Called when a server time packet arrives (network thread). */
    public void onServerTime(long gameTime) {
        long now = System.nanoTime();
        long prevNanos = lastTimePacketNanos;
        long prevGame = lastServerGameTime;
        lastTimePacketNanos = now;
        lastServerGameTime = gameTime;
        if (prevNanos == 0 || prevGame < 0) {
            return;
        }
        double seconds = (now - prevNanos) / 1e9;
        long ticks = gameTime - prevGame;
        if (seconds < 0.5 || ticks <= 0 || ticks > 2000) {
            return; // too short a window, a time jump (/time set), or a world change
        }
        double instant = Math.min(20.0, ticks / seconds);
        tps = tps * 0.6 + instant * 0.4;
    }

    public void reset() {
        lastTimePacketNanos = 0;
        lastServerGameTime = -1;
        tps = 20.0;
        lastAnyPacketNanos = System.nanoTime();
    }

    public double tps() {
        return tps;
    }

    /** Ping in milliseconds from the tab list (0 in singleplayer or when unknown). */
    public int pingMs() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            return 0;
        }
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : Math.max(0, info.getLatency());
    }

    /** Seconds since any packet arrived (a stalled connection). */
    public double secondsSinceLastPacket() {
        return (System.nanoTime() - lastAnyPacketNanos) / 1e9;
    }

    public boolean lagging(Settings s) {
        return tps < s.altoLagTpsThreshold.value || pingMs() > s.altoLagPingThreshold.value || secondsSinceLastPacket() > 2.0;
    }

    /** Factor for {@code Pacer.slowDown}: 1 when healthy, down to 0.25 under heavy lag. */
    public double slowDownFactor(Settings s) {
        if (!s.altoLagBackoff.value) {
            return 1.0;
        }
        double f = 1.0;
        if (tps < s.altoLagTpsThreshold.value) {
            f = Math.min(f, Math.max(0.25, tps / 20.0));
        }
        int ping = pingMs();
        if (ping > s.altoLagPingThreshold.value) {
            f = Math.min(f, Math.max(0.25, s.altoLagPingThreshold.value / (double) ping));
        }
        if (secondsSinceLastPacket() > 2.0) {
            f = 0.25;
        }
        return f;
    }

    /** Ticks to wait after a click before trusting the menu: one round trip plus margin, more under lag. */
    public int confirmTicks(Settings s) {
        int byPing = (int) Math.ceil(pingMs() * 1.5 / 50.0) + 1;
        int base = Math.max(s.altoClickConfirmTicks.value, byPing);
        if (s.altoLagBackoff.value && tps < s.altoLagTpsThreshold.value) {
            base = (int) Math.ceil(base * 20.0 / Math.max(5.0, tps));
        }
        return Math.min(60, Math.max(1, base));
    }
}
