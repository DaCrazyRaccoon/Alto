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

package baritone.alto.scriptmc;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.alto.script.ScriptLoader;
import baritone.alto.script.ScriptReport;
import baritone.alto.script.lang.Diagnostic;
import baritone.alto.script.lang.EventType;
import baritone.alto.script.runtime.RunOptions;
import baritone.alto.script.runtime.RunSnapshot;
import baritone.alto.script.runtime.RunStatus;
import baritone.alto.script.runtime.ScriptRun;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Runs Alto scripts (M5). Scripts live in {@code baritone/alto/scripts/*.alto.json}; they never start by themselves.
 * A new or changed script (different hash) shows what it may do and must be confirmed once. Only one script runs at
 * a time. When you leave the world while a script runs, a snapshot is saved and Alto asks on the next join whether
 * to continue ({@code #script resume}); it never continues on its own.
 */
public final class ScriptManager {

    private final Alto alto;
    private final AltoScriptHost host;
    private ScriptRun run;
    private String runName = "";
    private ScriptReport runReport;
    private String lastEnd = "";
    private boolean promptedResume;
    private int ticks;

    public ScriptManager(Alto alto) {
        this.alto = alto;
        this.host = new AltoScriptHost(alto);
        alto.addTickListener(a -> tick());
    }

    public Path folder() {
        return alto.directory().resolve("scripts");
    }

    private Path confirmedFile() {
        return folder().resolve(".confirmed-hashes.txt");
    }

    private Path snapshotFile() {
        return folder().resolve(".saved-run.json");
    }

    public List<String> names() {
        try {
            Files.createDirectories(folder());
            try (Stream<Path> s = Files.list(folder())) {
                return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".alto.json"))
                        .map(n -> n.substring(0, n.length() - ".alto.json".length())).sorted().toList();
            }
        } catch (IOException e) {
            return List.of();
        }
    }

    public ScriptRun current() {
        return run;
    }

    public String currentName() {
        return runName;
    }

    public String lastEnd() {
        return lastEnd;
    }

    public ScriptReport load(String name) throws IOException {
        Path file = folder().resolve(name.endsWith(".alto.json") ? name : name + ".alto.json");
        if (!file.normalize().startsWith(folder().normalize())) {
            throw new IOException("script names cannot leave the scripts folder");
        }
        return ScriptLoader.load(Files.readString(file, StandardCharsets.UTF_8));
    }

    private Set<String> confirmed() {
        try {
            return Files.exists(confirmedFile()) ? new HashSet<>(Files.readAllLines(confirmedFile(), StandardCharsets.UTF_8)) : new HashSet<>();
        } catch (IOException e) {
            return new HashSet<>();
        }
    }

    public boolean isConfirmed(String hash) {
        return confirmed().contains(hash);
    }

    private void markConfirmed(String hash) {
        Set<String> c = confirmed();
        if (c.add(hash)) {
            try {
                Files.createDirectories(folder());
                Files.write(confirmedFile(), c, StandardCharsets.UTF_8);
            } catch (IOException e) {
                AltoLog.info("could not remember the confirmation: " + e);
            }
        }
    }

    /** The lines shown before a first run: what the script may do, and its warnings. */
    public List<String> describe(ScriptReport r) {
        List<String> out = new ArrayList<>();
        out.add("Script '" + r.script().name() + "' (hash " + r.hash().substring(0, 12) + "...) may:");
        r.capabilities().sentences().forEach(s -> out.add("  - " + s));
        for (Diagnostic d : r.warnings()) {
            out.add("  warning: " + d);
        }
        return out;
    }

    /**
     * Starts a script by name. Returns null when it started; otherwise the lines to show (errors, or the
     * confirmation request).
     */
    public List<String> start(String name, boolean confirm) {
        if (!Baritone.settings().altoScripts.value) {
            return List.of("Scripts are switched off (altoScripts).");
        }
        ScriptReport r;
        try {
            r = load(name);
        } catch (IOException e) {
            return List.of("Cannot read script '" + name + "': " + e.getMessage() + ". Scripts: " + names());
        }
        return start(name, r, confirm);
    }

    /** Starts a loaded script (also used by the self-test). */
    public List<String> start(String name, ScriptReport r, boolean confirm) {
        if (!r.runnable()) {
            List<String> out = new ArrayList<>();
            out.add("Script '" + name + "' has errors:");
            r.errors().forEach(d -> out.add("  " + d));
            return out;
        }
        if (run != null && !run.status().isEnded()) {
            return List.of("Script '" + runName + "' is still running (#script stop first).");
        }
        if (!confirm && !confirmed().contains(r.hash())) {
            List<String> out = describe(r);
            out.add("This script is new or changed. Run it with: #script run " + name + " confirm");
            return out;
        }
        markConfirmed(r.hash());
        RunOptions options = new RunOptions();
        options.randomSeed = System.nanoTime();
        run = new ScriptRun(r.script(), host, options);
        runName = name;
        runReport = r;
        lastEnd = "";
        deleteSnapshot();
        AltoLog.chat("Script '" + name + "' started.");
        return null;
    }

    public boolean stop(String why) {
        if (run == null || run.status().isEnded()) {
            return false;
        }
        run.stop();
        finish(why);
        return true;
    }

    public boolean pause() {
        if (run == null || run.status() != RunStatus.RUNNING) {
            return false;
        }
        run.pause();
        return true;
    }

    /** Resumes a paused run, or continues a run saved before the last restart (the user typed it). */
    public List<String> resume() {
        if (run != null && run.status() == RunStatus.PAUSED) {
            run.resume();
            return List.of("Script '" + runName + "' resumed.");
        }
        if (run != null && !run.status().isEnded()) {
            return List.of("Script '" + runName + "' is running.");
        }
        try {
            if (!Files.exists(snapshotFile())) {
                return List.of("Nothing to resume.");
            }
            List<String> lines = Files.readAllLines(snapshotFile(), StandardCharsets.UTF_8);
            String name = lines.get(0);
            RunSnapshot snap = RunSnapshot.fromJson(String.join("\n", lines.subList(1, lines.size())));
            ScriptReport r = load(name);
            if (!r.runnable() || !r.hash().equals(snap.scriptHash())) {
                deleteSnapshot();
                return List.of("The saved run of '" + name + "' does not fit the script any more (it changed); not resumed.");
            }
            RunOptions options = new RunOptions();
            options.randomSeed = System.nanoTime();
            run = ScriptRun.restore(r.script(), host, options, snap);
            runName = name;
            runReport = r;
            deleteSnapshot();
            return List.of("Script '" + name + "' continues (variables restored, handlers start again from their beginning).");
        } catch (IOException | RuntimeException e) {
            return List.of("Could not resume: " + e.getMessage());
        }
    }

    /** Chat you typed: a trigger of the running script starts its handler and is not sent to the server. */
    public boolean onTypedChat(String message) {
        if (run == null || run.status().isEnded() || !run.isChatTrigger(message)) {
            return false;
        }
        run.fire(EventType.ON_CHAT, message);
        return true;
    }

    private void tick() {
        if (alto.ctx().player() == null) {
            if (run != null && !run.status().isEnded()) {
                saveSnapshot(); // left the world while the script ran
                run.stop();
                finish("left the world (saved; #script resume continues after you confirm)");
            }
            promptedResume = false;
            return;
        }
        if (!promptedResume) {
            promptedResume = true;
            if (run == null && Files.exists(snapshotFile())) {
                AltoLog.chat("A script was running when you left. Type #script resume to continue it, or #script forget.");
            }
        }
        if (run == null || run.status().isEnded()) {
            return;
        }
        if (++ticks % 100 == 0) {
            saveSnapshot(); // kept while running, so a crash or a closed game can be continued later (after asking)
        }
        try {
            run.tick();
        } catch (RuntimeException e) {
            AltoLog.exception("script crashed", e);
            run.stop();
        }
        if (run.status().isEnded()) {
            finish(null);
        }
    }

    private void finish(String why) {
        RunStatus status = run.status();
        if (why == null || !why.startsWith("left the world")) {
            deleteSnapshot();
        }
        String text = "Script '" + runName + "' " + status.name().toLowerCase(Locale.ROOT)
                + (why != null ? ": " + why : run.lastError() != null ? ": " + run.lastError() : run.endReason() != null ? ": " + run.endReason() : "");
        lastEnd = text;
        if (status == RunStatus.FINISHED) {
            AltoLog.chat(text);
        } else {
            AltoLog.warn(text);
        }
    }

    public List<String> status() {
        List<String> out = new ArrayList<>();
        if (run == null) {
            out.add(lastEnd.isEmpty() ? "No script has run yet." : lastEnd);
            return out;
        }
        out.add("Script '" + runName + "': " + run.status().name().toLowerCase(Locale.ROOT) + ", " + run.actionsStarted()
                + " action(s), " + run.ticks() + " ticks" + (run.currentBlock() == null ? "" : ", now: " + run.currentBlock() + " at " + run.currentPath()));
        if (!run.variables().isEmpty()) {
            StringBuilder vars = new StringBuilder("  variables:");
            run.variables().forEach((k, v) -> vars.append(' ').append(k).append('=').append(v.display()));
            out.add(vars.toString());
        }
        if (run.status().isEnded()) {
            out.add("  " + lastEnd);
        }
        return out;
    }

    private void saveSnapshot() {
        try {
            Files.createDirectories(folder());
            Files.writeString(snapshotFile(), runName + "\n" + run.snapshot().toJson(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            AltoLog.info("could not save the script run: " + e);
        }
    }

    public void deleteSnapshot() {
        try {
            Files.deleteIfExists(snapshotFile());
        } catch (IOException ignored) {
        }
    }
}
