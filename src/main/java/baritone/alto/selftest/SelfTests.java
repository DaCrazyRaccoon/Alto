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

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoLog;
import com.google.gson.GsonBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs named in-game self-test scenarios ({@code #selftest}). Safety: only in singleplayer (the setup needs the
 * integrated server), only when {@code altoSelfTest} is on, and only in worlds named {@code alto_test*} unless the
 * user adds "confirm" (the scenarios rebuild the area around the player and replace the inventory).
 * Results go to chat and to {@code baritone/alto/selftest/last-run.json}; {@code finished.flag} marks the end for
 * the automated dev harness.
 */
public final class SelfTests {

    public record Result(String name, String outcome, String message, double seconds) {
    }

    private final Alto alto;
    private final Map<String, Supplier<Scenario>> scenarios = new LinkedHashMap<>();
    private final Deque<String> queue = new ArrayDeque<>();
    private final List<Result> results = new ArrayList<>();
    private Scenario current;
    private BlockPos origin;
    private int ticks;
    private int phase;
    private long startNanos;

    public SelfTests(Alto alto) {
        this.alto = alto;
        M1Scenarios.register(this);
    }

    public void register(String name, Supplier<Scenario> factory) {
        scenarios.put(name, factory);
    }

    public List<String> names() {
        return new ArrayList<>(scenarios.keySet());
    }

    private boolean allowUpkeep;

    /** Scenarios that test the automatic helpers switch them on; all others run with the helpers quiet. */
    public void allowUpkeep(boolean allow) {
        this.allowUpkeep = allow;
    }

    public boolean allowUpkeep() {
        return allowUpkeep;
    }

    public boolean running() {
        return current != null || !queue.isEmpty();
    }

    /** @return null when started, otherwise why not */
    public String start(String what, boolean confirm) {
        Minecraft mc = Minecraft.getInstance();
        if (!Baritone.settings().altoSelfTest.value) {
            return "self-tests are switched off (altoSelfTest)";
        }
        if (!mc.hasSingleplayerServer() || mc.getSingleplayerServer() == null) {
            return "self-tests only run in singleplayer (they build their own test setup)";
        }
        String world = mc.getSingleplayerServer().getWorldData().getLevelName();
        if (!world.startsWith("alto_test") && !confirm) {
            return "this world is not named alto_test...; the tests change the area around you and your inventory. "
                    + "Add 'confirm' to run anyway.";
        }
        if (running()) {
            return "self-tests are already running";
        }
        List<String> todo = new ArrayList<>();
        if (what.equalsIgnoreCase("all")) {
            todo.addAll(scenarios.keySet());
        } else if (scenarios.containsKey(what)) {
            todo.add(what);
        } else {
            return "unknown scenario '" + what + "'; known: " + String.join(", ", scenarios.keySet());
        }
        results.clear();
        queue.addAll(todo);
        deleteFlag();
        AltoLog.chat("Self-test: running " + todo.size() + " scenario(s): " + String.join(", ", todo));
        return null;
    }

    /** Client tick. */
    public void tick() {
        if (current == null) {
            if (queue.isEmpty()) {
                return;
            }
            String name = queue.poll();
            current = scenarios.get(name).get();
            ticks = 0;
            phase = 0;
            startNanos = System.nanoTime();
            AltoLog.chat("Self-test " + name + ": " + current.description());
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getSingleplayerServer() == null) {
            finish(Scenario.Verdict.fail("left the world"));
            return;
        }
        try {
            if (phase == 0) {
                origin = mc.player.blockPosition();
                MinecraftServer server = mc.getSingleplayerServer();
                server.submit(() -> {
                    ServerPlayer sp = server.getPlayerList().getPlayer(mc.player.getUUID());
                    ServerLevel level = sp.serverLevel();
                    current.setup(level, sp, origin);
                }).get(30, TimeUnit.SECONDS);
                phase = 1;
                ticks = 0;
                return;
            }
            if (phase == 1) {
                if (++ticks < 20) {
                    return; // let the world changes reach the client
                }
                current.start(alto, origin);
                phase = 2;
                ticks = 0;
                return;
            }
            ticks++;
            Scenario.Verdict v = current.check(alto, origin, ticks);
            if (v.outcome() != Scenario.Outcome.RUNNING) {
                finish(v);
            } else if (ticks > current.timeoutSeconds() * 20) {
                finish(Scenario.Verdict.fail("timed out after " + current.timeoutSeconds() + " s"));
            }
        } catch (Throwable t) {
            AltoLog.exception("self-test " + current.name() + " crashed", t);
            finish(Scenario.Verdict.fail("crashed: " + t));
        }
    }

    private void finish(Scenario.Verdict v) {
        Scenario s = current;
        current = null;
        allowUpkeep = false;
        try {
            s.cleanup(alto);
        } catch (Throwable t) {
            AltoLog.exception("self-test cleanup failed", t);
        }
        double seconds = (System.nanoTime() - startNanos) / 1e9;
        results.add(new Result(s.name(), v.outcome().name(), v.message(), Math.round(seconds * 10) / 10.0));
        baritone.api.utils.Helper.HELPER.logDirect("[Alto] SELFTEST " + s.name() + ": " + v.outcome() + " - " + v.message(),
                v.outcome() == Scenario.Outcome.PASS ? ChatFormatting.GREEN : ChatFormatting.RED);
        AltoLog.info("SELFTEST " + s.name() + ": " + v.outcome() + " - " + v.message());
        if (queue.isEmpty()) {
            long pass = results.stream().filter(r -> r.outcome().equals("PASS")).count();
            AltoLog.chat("Self-test finished: " + pass + "/" + results.size() + " passed.");
            write();
        }
    }

    private Path dir() {
        return alto.directory().resolve("selftest");
    }

    private void deleteFlag() {
        try {
            Files.deleteIfExists(dir().resolve("finished.flag"));
        } catch (IOException ignored) {
        }
    }

    private void write() {
        try {
            Files.createDirectories(dir());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("finished", Instant.now().toString());
            out.put("results", results);
            Files.writeString(dir().resolve("last-run.json"), new GsonBuilder().setPrettyPrinting().create().toJson(out), StandardCharsets.UTF_8);
            Files.writeString(dir().resolve("finished.flag"), Instant.now().toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AltoLog.info("could not write self-test results: " + e);
        }
    }

    public List<Result> results() {
        return List.copyOf(results);
    }
}
