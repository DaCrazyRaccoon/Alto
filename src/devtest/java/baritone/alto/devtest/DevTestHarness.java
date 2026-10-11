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

package baritone.alto.devtest;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * DEV-ONLY automation harness (separate source set, never part of the release jar).
 * <p>
 * Controlled by system properties:
 * <ul>
 *     <li>{@code alto.devtest=boot}: wait for the title screen, write a report, quit.</li>
 *     <li>{@code alto.devtest=world}: create a fresh flat world, run {@code alto.devtest.commands}
 *     (Baritone commands separated by {@code ;}, without prefix), wait until
 *     {@code <gameDir>/baritone/alto/selftest/finished.flag} appears or the timeout passes, quit.</li>
 *     <li>{@code alto.devtest.timeout}: seconds before giving up (default 600).</li>
 *     <li>{@code alto.devtest.gamemode}: survival (default) or creative.</li>
 * </ul>
 * The report is written to {@code <gameDir>/alto-devtest-report.txt}; its last line is the verdict
 * ({@code BOOT OK}, {@code WORLD OK}, {@code LAUNCH FAILED: ...} or {@code TIMEOUT ...}). Error screens (NeoForge's
 * loading error screen, vanilla error/disconnect screens) end the run immediately with {@code LAUNCH FAILED}.
 * Failures that happen before mods are constructed are caught by {@code scripts/alto/test-client.ps1}, which also
 * reads the game log. The harness writes its PID to {@code alto-devtest.pid} so that script can stop it.
 */
@Mod(value = "alto_devtest", dist = Dist.CLIENT)
public final class DevTestHarness {
    private static final Logger LOG = LogUtils.getLogger();

    private final String mode = System.getProperty("alto.devtest", "");
    private final long startNanos = System.nanoTime();
    private final long timeoutNanos = Long.getLong("alto.devtest.timeout", 600L) * 1_000_000_000L;
    private final List<String> report = new ArrayList<>();
    private final Deque<String> commands = new ArrayDeque<>();
    private boolean worldRequested;
    private int ticksInWorld = -1;
    private boolean done;

    public DevTestHarness() {
        if (Boolean.getBoolean("alto.devtest.production")) {
            simulateProduction();
        }
        if (mode.isEmpty()) {
            return;
        }
        String cmds = System.getProperty("alto.devtest.commands", "");
        Arrays.stream(cmds.split(";")).map(String::trim).filter(s -> !s.isEmpty()).forEach(commands::add);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        LOG.info("[alto-devtest] mode={} commands={}", mode, commands);
        try {
            Files.writeString(Path.of("alto-devtest.pid"), Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("[alto-devtest] could not write pid file", e);
        }
    }

    // -Palto_devtest_photos=true: documentation pictures at the moments the self-tests ask for (screenshots/photo-*.png).
    private static final boolean PHOTOS = Boolean.getBoolean("alto.devtest.photos");
    private final java.util.ArrayDeque<Object[]> photoQueue = new java.util.ArrayDeque<>();
    private Object[] photoNow;
    private int photoTicks;
    private net.minecraft.client.CameraType photoCamera;

    private boolean photoHookInstalled;

    /** Sets SelfTests.photos (by reflection: this harness is compiled without Alto on its classpath). */
    private void installPhotoHook() {
        photoHookInstalled = true;
        try {
            ClassLoader cl = DevTestHarness.class.getClassLoader();
            Class<?> selfTests = Class.forName("baritone.alto.selftest.SelfTests", true, cl);
            Class<?> hookType = Class.forName("baritone.alto.selftest.SelfTests$PhotoHook", true, cl);
            Object hook = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{hookType}, (proxy, m, args) -> switch (m.getName()) {
                case "request" -> {
                    photoQueue.add(new Object[]{args[0], args[1]});
                    yield null;
                }
                case "busy" -> photoNow != null || !photoQueue.isEmpty();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> "photo hook";
            });
            selfTests.getField("photos").set(null, hook);
            LOG.info("[alto-devtest] documentation photos on");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("[alto-devtest] could not install the photo hook", e);
        }
    }

    private void tickPhotos(Minecraft mc) {
        if (photoNow == null) {
            photoNow = photoQueue.poll();
            if (photoNow == null) {
                return;
            }
            photoTicks = 0;
            photoCamera = mc.options.getCameraType();
            mc.options.setCameraType((Boolean) photoNow[1] ? net.minecraft.client.CameraType.THIRD_PERSON_BACK : net.minecraft.client.CameraType.FIRST_PERSON);
            return;
        }
        if (++photoTicks < 4) {
            return; // let a few frames render from the new camera
        }
        String file = "photo-" + photoNow[0] + ".png";
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), msg -> {
        });
        LOG.info("[alto-devtest] photo {}", file);
        mc.options.setCameraType(photoCamera);
        photoNow = null;
    }

    private void onClientTick(ClientTickEvent.Post event) {
        if (done) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (PHOTOS) {
            if (!photoHookInstalled) {
                installPhotoHook();
            }
            if (mc.player != null) {
                tickPhotos(mc);
            }
        }
        if (System.nanoTime() - startNanos > timeoutNanos) {
            finish(mc, "TIMEOUT after " + (timeoutNanos / 1_000_000_000L) + " s");
            return;
        }
        String error = errorScreen(mc);
        if (error != null) {
            finish(mc, "LAUNCH FAILED: " + error);
            return;
        }
        logScreenChanges(mc);
        if (mc.screen != null && isModNoticeScreen(mc)) {
            // First-launch welcome screens of pack mods (e.g. Create Deep Seas) reopen until a choice is made, so press
            // their "later / skip / close" button like a player would (test copy only).
            dismissWelcome(mc);
            return;
        }
        periodicScreenshot(mc);
        if (mc.screen instanceof AccessibilityOnboardingScreen) {
            mc.options.onboardingAccessibilityFinished();
            mc.setScreen(new TitleScreen(true));
            return;
        }
        if (mode.equals("boot")) {
            if (mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                finish(mc, "BOOT OK: title screen reached, mods loaded=" + ModList.get().size());
            }
            return;
        }
        if (!mode.equals("world") && !mode.equals("join")) {
            return;
        }
        if (!worldRequested && mode.equals("join")) {
            // alto.devtest=join: connect to a dedicated server that does NOT have Alto (client-only proof).
            if (mc.screen instanceof TitleScreen title && mc.getOverlay() == null) {
                worldRequested = true;
                mc.options.pauseOnLostFocus = false;
                String addr = System.getProperty("alto.devtest.server", "127.0.0.1:25599");
                report.add("title screen reached, mods loaded=" + ModList.get().size() + "; joining " + addr);
                net.minecraft.client.gui.screens.ConnectScreen.startConnecting(title, mc,
                        net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(addr),
                        new net.minecraft.client.multiplayer.ServerData("Alto join test", addr, net.minecraft.client.multiplayer.ServerData.Type.OTHER),
                        false, null);
            }
            return;
        }
        if (!worldRequested) {
            if (mc.screen instanceof TitleScreen title && mc.getOverlay() == null) {
                worldRequested = true;
                mc.options.pauseOnLostFocus = false;
                report.add("title screen reached, mods loaded=" + ModList.get().size()
                        + " after " + (System.nanoTime() - startNanos) / 1_000_000_000L + " s");
                String name = "alto_test_" + System.currentTimeMillis();
                GameType type = "creative".equals(System.getProperty("alto.devtest.gamemode")) ? GameType.CREATIVE : GameType.SURVIVAL;
                GameRules rules = new GameRules();
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
                LevelSettings settings = new LevelSettings(name, type, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
                // alto.devtest.worldtype=normal: real terrain (default world generation, structures on), random seed
                // unless alto.devtest.seed is given. Default: a superflat world.
                boolean normal = "normal".equals(System.getProperty("alto.devtest.worldtype"));
                String seedProp = System.getProperty("alto.devtest.seed", "");
                long seed = !seedProp.isEmpty() ? Long.parseLong(seedProp) : normal ? new java.util.Random().nextLong() : 1L;
                var preset = normal ? WorldPresets.NORMAL : WorldPresets.FLAT;
                report.add("world type " + (normal ? "normal" : "flat") + ", seed " + seed);
                LOG.info("[alto-devtest] world type {} seed {}", normal ? "normal" : "flat", seed);
                mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(seed, normal, false),
                        ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(preset).value().createWorldDimensions(),
                        title);
            }
            return;
        }
        if (mc.player == null || mc.level == null || mode.equals("world") && mc.getSingleplayerServer() == null
                || mode.equals("join") && mc.getConnection() == null) {
            return;
        }
        ticksInWorld++;
        if (ticksInWorld == 0) {
            report.add("world joined after " + (System.nanoTime() - startNanos) / 1_000_000_000L + " s");
        }
        if (ticksInWorld < 100 || mc.screen != null && ticksInWorld < 200) {
            return; // let chunks load and screens settle
        }
        if (!commands.isEmpty()) {
            if (ticksInWorld % 20 == 0) {
                String cmd = commands.poll();
                report.add("command: " + cmd);
                try {
                    // Reflection keeps the harness compilable on its own; Baritone is loaded in every dev run.
                    Object provider = Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
                    Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
                    Object manager = baritone.getClass().getMethod("getCommandManager").invoke(baritone);
                    Object ok = manager.getClass().getMethod("execute", String.class).invoke(manager, cmd);
                    report.add("  -> executed=" + ok);
                } catch (Throwable t) {
                    report.add("  -> FAILED: " + t);
                    LOG.error("[alto-devtest] command failed", t);
                }
            }
            return;
        }
        if (mode.equals("join")) {
            if (ticksInWorld > 400) {
                boolean remote = !mc.isLocalServer() && mc.getConnection() != null && mc.getConnection().getConnection().isConnected();
                finish(mc, remote ? "JOIN OK after " + (System.nanoTime() - startNanos) / 1_000_000_000L
                        + " s: still connected to the server without Alto after 20 s in game" : "JOIN FAILED: not connected");
            }
            return;
        }
        Path flag = mc.gameDirectory.toPath().resolve("baritone/alto/selftest/finished.flag");
        boolean selftestRequested = System.getProperty("alto.devtest.commands", "").contains("selftest");
        if (!selftestRequested ? ticksInWorld > 300 : Files.exists(flag)) {
            long secs = (System.nanoTime() - startNanos) / 1_000_000_000L;
            if (!selftestRequested) {
                finish(mc, "WORLD OK after " + secs + " s");
                return;
            }
            // Everything must pass: one failed (or missing) scenario fails the whole run.
            int total = 0;
            java.util.List<String> failed = new java.util.ArrayList<>();
            try {
                var json = com.google.gson.JsonParser.parseString(Files.readString(flag.resolveSibling("last-run.json"))).getAsJsonObject();
                for (var e : json.getAsJsonArray("results")) {
                    var r = e.getAsJsonObject();
                    total++;
                    String line = "  " + r.get("name").getAsString() + ": " + r.get("outcome").getAsString() + " - " + r.get("message").getAsString();
                    report.add(line);
                    if (!"PASS".equals(r.get("outcome").getAsString())) {
                        failed.add(r.get("name").getAsString());
                    }
                }
            } catch (Exception ex) {
                finish(mc, "SELFTEST FAILED: could not read the results (" + ex + ")");
                return;
            }
            if (total == 0 || !failed.isEmpty()) {
                finish(mc, "SELFTEST FAILED after " + secs + " s: " + (total - failed.size()) + "/" + total + " passed, failed " + failed);
            } else {
                finish(mc, "WORLD OK after " + secs + " s: self-tests " + total + "/" + total + " passed");
            }
        }
    }

    /**
     * Pack runs only: several pack libraries run extra self-checks when FML is not in production mode (e.g. Registrate
     * throws "Found unused register callbacks" for an addon's harmless leftover callback, which only logs in the real
     * game). Mod constructors run before registration, so switching the flag here makes pack mods behave as they do
     * in Prism. DEV-ONLY: this harness is never shipped.
     */
    private static void simulateProduction() {
        try {
            java.lang.reflect.Field f = net.neoforged.fml.loading.FMLLoader.class.getDeclaredField("production");
            f.setAccessible(true);
            f.setBoolean(null, true);
            LOG.info("[alto-devtest] FMLLoader.production set to true (simulating the real game for pack mods)");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("[alto-devtest] could not simulate production mode", e);
        }
    }

    private int welcomeTries;
    private long lastShotNanos = System.nanoTime();

    /**
     * Pack mods show their own notice screens (welcome, update, changelog) at startup. A non-vanilla screen that is
     * not a container or chat screen, seen before the self-tests start, is treated as such a notice.
     */
    private boolean isModNoticeScreen(Minecraft mc) {
        String cls = mc.screen.getClass().getName();
        if (cls.startsWith("net.minecraft.") || cls.startsWith("net.neoforged.") || cls.startsWith("baritone.")) {
            return false;
        }
        if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
            return false;
        }
        String simple = mc.screen.getClass().getSimpleName();
        return simple.contains("Welcome") || simple.contains("Update") || simple.contains("Changelog")
                || simple.contains("Notice") || simple.contains("News") || commands.size() > 0 && ticksInWorld < 200;
    }

    private final java.util.Set<String> noticeShots = new java.util.HashSet<>();

    private void dismissWelcome(Minecraft mc) {
        String name = mc.screen.getClass().getName();
        if (noticeShots.add(name)) {
            shot(mc, "notice-" + mc.screen.getClass().getSimpleName());
        }
        for (net.minecraft.client.gui.components.events.GuiEventListener child : mc.screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.Button b) {
                String text = b.getMessage().getString().toLowerCase(java.util.Locale.ROOT);
                if (text.contains("later") || text.contains("skip") || text.contains("no thanks") || text.contains("close")
                        || text.equals("done") || text.equals("ok") || text.contains("continue")) {
                    LOG.info("[alto-devtest] pressing '{}' on {}", b.getMessage().getString(), name);
                    b.onPress();
                    return;
                }
            }
        }
        if (welcomeTries++ % 100 == 0) {
            LOG.info("[alto-devtest] closing welcome screen {} (no dismiss button found)", name);
        }
        mc.setScreen(null);
    }

    /** Game-window screenshots (only the game, saved under the run folder's screenshots/) for diagnosing runs. */
    private void shot(Minecraft mc, String why) {
        String file = "alto-devtest-" + System.currentTimeMillis() + "-" + why.replaceAll("[^a-zA-Z0-9_-]", "_") + ".png";
        try {
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), msg -> {
            });
            LOG.info("[alto-devtest] screenshot {} ({})", file, why);
        } catch (Throwable t) {
            LOG.warn("[alto-devtest] screenshot failed", t);
        }
    }

    private void periodicScreenshot(Minecraft mc) {
        if (System.nanoTime() - lastShotNanos > 30_000_000_000L && mc.level != null) {
            lastShotNanos = System.nanoTime();
            shot(mc, "periodic");
        }
    }

    private String lastScreen = "";
    private long lastScreenNanos = System.nanoTime();
    private boolean stuckReported;
    private int screenTicks;
    private static final boolean CONTAINER_SHOTS = Boolean.getBoolean("alto.devtest.containershots");

    /** Logs every screen change and reports a screen that blocks the run for more than two minutes. */
    private void logScreenChanges(Minecraft mc) {
        String now = mc.screen == null ? "none" : mc.screen.getClass().getName();
        if (!now.equals(lastScreen)) {
            LOG.info("[alto-devtest] screen: {}", now);
            lastScreen = now;
            lastScreenNanos = System.nanoTime();
            stuckReported = false;
            screenTicks = 0;
        } else if (CONTAINER_SHOTS && (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                || mc.screen != null && mc.screen.getClass().getName().startsWith("baritone.alto"))
                && ++screenTicks % 8 == 3 && screenTicks < 60) {
            // -Palto_devtest_containershots=true: what the player would see while Alto clicks in a container.
            shot(mc, "container-" + mc.screen.getClass().getSimpleName() + "-t" + screenTicks);
        } else if (!stuckReported && mc.screen != null && mc.getOverlay() == null
                && System.nanoTime() - lastScreenNanos > 120_000_000_000L && !(mc.screen instanceof TitleScreen)) {
            stuckReported = true;
            shot(mc, "stuck");
            LOG.warn("[alto-devtest] STUCK on screen {} for 2 minutes", now);
        }
    }

    /** Screens that mean the launch (or world join) failed. Matched by class name: some are NeoForge-internal. */
    private static final java.util.Set<String> ERROR_SCREENS = java.util.Set.of(
            "net.neoforged.neoforge.client.gui.LoadingErrorScreen",
            "net.minecraft.client.gui.screens.ErrorScreen",
            "net.minecraft.client.gui.screens.DisconnectedScreen",
            "net.minecraft.client.gui.screens.DatapackLoadFailureScreen",
            "net.minecraft.client.gui.screens.RecoverWorldDataScreen");

    private static String errorScreen(Minecraft mc) {
        if (mc.screen == null) {
            return null;
        }
        String cls = mc.screen.getClass().getName();
        if (!ERROR_SCREENS.contains(cls)) {
            return null;
        }
        return cls.substring(cls.lastIndexOf('.') + 1) + " \"" + mc.screen.getTitle().getString() + "\"";
    }

    private void finish(Minecraft mc, String verdict) {
        done = true;
        shot(mc, "final");
        report.add(verdict);
        Path out = mc.gameDirectory.toPath().resolve("alto-devtest-report.txt");
        try {
            Files.write(out, report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("[alto-devtest] could not write report", e);
        }
        LOG.info("[alto-devtest] {}", verdict);
        mc.stop();
    }
}
