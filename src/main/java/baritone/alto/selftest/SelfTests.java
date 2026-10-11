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

    /**
     * Pictures for the documentation (dev harness only, -Palto_devtest_photos=true): the self-tests ask for one at
     * the moments that show a feature best; while one is being taken, the scenario waits.
     */
    public interface PhotoHook {
        void request(String name, boolean thirdPerson);

        boolean busy();
    }

    /** Null in a normal game: no pictures are ever taken. */
    public static volatile PhotoHook photos;

    private final java.util.Set<String> photosTaken = new java.util.HashSet<>();
    private Scenario.Verdict heldVerdict;
    private int photoScreenTicks;
    private int photoRideTicks;
    private int photoMoveTicks;

    private final Alto alto;
    private final Map<String, Supplier<Scenario>> scenarios = new LinkedHashMap<>();
    private final Deque<String> queue = new ArrayDeque<>();
    private final List<Result> results = new ArrayList<>();
    private Scenario current;
    private BlockPos origin;
    private int ticks;
    private int phase;
    private long startNanos;
    private BlockPos baseOrigin;

    public SelfTests(Alto alto) {
        this.alto = alto;
        M1Scenarios.register(this);
        M2Scenarios.register(this);
        M3Scenarios.register(this);
        M4Scenarios.register(this);
        M5Scenarios.register(this);
        M6Scenarios.register(this);
        M7Scenarios.register(this);
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
        } else {
            for (String name : what.split(",")) { // several: "smelt,shulker"
                if (!scenarios.containsKey(name.trim())) {
                    return "unknown scenario '" + name.trim() + "'; known: " + String.join(", ", scenarios.keySet());
                }
                todo.add(name.trim());
            }
        }
        results.clear();
        baseOrigin = mc.player.blockPosition();
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
        PhotoHook ph = photos;
        if (ph != null && ph.busy()) {
            return; // a documentation picture is being taken
        }
        if (heldVerdict != null) {
            Scenario.Verdict v = heldVerdict;
            heldVerdict = null;
            finish(v);
            return;
        }
        try {
            if (phase == 0) {
                origin = baseOrigin != null ? baseOrigin : mc.player.blockPosition();
                MinecraftServer server = mc.getSingleplayerServer();
                server.submit(() -> {
                    ServerPlayer sp = server.getPlayerList().getPlayer(mc.player.getUUID());
                    ServerLevel level = server.overworld(); // test areas are always in the overworld (the travel test visits the nether)
                    current.setup(level, sp, origin);
                }).get(30, TimeUnit.SECONDS);
                phase = 1;
                ticks = 0;
                return;
            }
            if (phase == 1) {
                if (++ticks < 20 || mc.level.dimension() != net.minecraft.world.level.Level.OVERWORLD && ticks < 600) {
                    return; // let the world changes (and a dimension change back) reach the client
                }
                current.start(alto, origin);
                phase = 2;
                ticks = 0;
                return;
            }
            ticks++;
            Scenario.Verdict v = current.check(alto, origin, ticks);
            if (ph != null) {
                photoMoments(ph, mc);
            }
            if (v.outcome() == Scenario.Outcome.PASS && ph != null && photosTaken.add(current.name() + "-done")) {
                // the result, seen from behind the player (or through their eyes when it is the block they look at)
                ph.request(current.name() + "-done", !java.util.Set.of("create-filter", "useon").contains(current.name()));
                heldVerdict = v;
                return;
            }
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

    /** The moments that show a feature best: a container in use, a ride, a moving platform, a safety stop. */
    private void photoMoments(PhotoHook ph, Minecraft mc) {
        String n = current.name();
        boolean gui = mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                || mc.screen != null && mc.screen.getClass().getName().startsWith("baritone.");
        photoScreenTicks = gui ? photoScreenTicks + 1 : 0;
        if (gui && photoScreenTicks >= 2) {
            // A container once Alto has put things in it (or after 2 s); Alto's own screens right away.
            int filled = 0;
            for (net.minecraft.world.inventory.Slot s : mc.player.containerMenu.slots) {
                if (!(s.container instanceof net.minecraft.world.entity.player.Inventory) && s.hasItem()) {
                    filled++;
                }
            }
            String cls = mc.screen.getClass().getName();
            String screen = mc.screen.getClass().getSimpleName().replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
            boolean crafting = screen.contains("crafting") || screen.equals("inventoryscreen");
            boolean ready;
            if (cls.startsWith("baritone.")) {
                ready = photoScreenTicks >= 6; // Alto's own screens
            } else if (cls.contains("StockKeeper")) {
                ready = photoScreenTicks == 20; // its stock list arrives a little later
            } else if (n.equals("emi-pick")) {
                ready = photoScreenTicks == 18; // EMI's list next to the inventory
            } else if (crafting) {
                ready = filled >= 3 || photoScreenTicks == 40; // a grid Alto is filling
            } else {
                ready = filled >= 1 || photoScreenTicks == 3; // a container right after it opened, contents still in
            }
            if (ready && photosTaken.add(n + "-" + screen)) {
                ph.request(n + "-" + screen, false);
            }
        }
        photoRideTicks = mc.player.isPassenger() ? photoRideTicks + 1 : 0;
        if (photoRideTicks == 30 && photosTaken.add(n + "-riding")) {
            ph.request(n + "-riding", true);
        }
        photoMoveTicks = alto.platformPause().onMovingPlatform() ? photoMoveTicks + 1 : 0;
        if (photoMoveTicks == 15 && photosTaken.add(n + "-moving")) {
            ph.request(n + "-moving", true);
        }
        if (alto.safety().tripped() && photosTaken.add(n + "-safety")) {
            ph.request(n + "-safety", false);
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
        AltoLog.chat("SELFTEST " + s.name() + ": " + v.outcome() + " - " + v.message(),
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
