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

package baritone.alto.script;

import baritone.alto.script.lang.ActionType;
import baritone.alto.script.lang.Value;
import baritone.alto.script.runtime.ActionRequest;
import baritone.alto.script.runtime.GotoTarget;
import baritone.alto.script.runtime.RunOptions;
import baritone.alto.script.runtime.RunStatus;
import baritone.alto.script.runtime.ScriptRun;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every file in docs/alto/examples must validate cleanly and run to completion against the mock world.
 */
class ExamplesTest {

    /** The project folder: the test working directory or one of its parents. */
    static Path projectFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(relative + " not found from " + Path.of("").toAbsolutePath());
    }

    static List<Path> examples() {
        try (Stream<Path> files = Files.list(projectFile("docs/alto/examples"))) {
            return files.filter(p -> p.getFileName().toString().endsWith(".alto.json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String name(Path file) {
        String n = file.getFileName().toString();
        return n.substring(0, n.length() - ".alto.json".length());
    }

    @Test
    void theRequiredExamplesExist() {
        List<String> names = new ArrayList<>();
        examples().forEach(p -> names.add(name(p)));
        assertTrue(names.containsAll(List.of("planks_from_logs", "smelt_iron_stack", "restock_and_build",
                "farm_sugar_cane_and_deposit", "set_deployer_filter")), names::toString);
    }

    @Test
    void everyExampleValidatesWithoutErrorsOrWarnings() {
        for (Path file : examples()) {
            ScriptReport report = ScriptLoader.load(read(file));
            assertEquals(List.of(), report.diagnostics(), file::toString);
        }
    }

    @Test
    void everyExampleRunsToCompletionInTheMockWorld() {
        for (Path file : examples()) {
            String name = name(file);
            MockHost host = new MockHost();
            prepare(name, host);
            ScriptRun run = new ScriptRun(ScriptLoader.load(read(file)).script(), host, new RunOptions());
            if (name.equals("farm_sugar_cane_and_deposit")) {
                // get hurt during the second pass to exercise on_health_below
                while (!run.status().isEnded() && ((Value.Num) run.variables().get("passes")).value() < 2) {
                    host.tick(run);
                }
                host.health = 5;
            }
            host.runToEnd(run, 200_000);
            assertEquals(RunStatus.FINISHED, run.status(), () -> name + ": " + run.endReason());
            verify(name, host, run);
        }
    }

    private static void prepare(String name, MockHost host) {
        switch (name) {
            case "planks_from_logs" -> host.give("oak_log", 16);
            case "smelt_iron_stack" -> host.give("raw_iron", 10).stock("raw_iron", 100).stock("coal", 16);
            case "restock_and_build" -> host.give("oak_planks", 28).stock("oak_planks", 200).stock("glass_pane", 64)
                    .stock("cobblestone", 100);
            case "farm_sugar_cane_and_deposit" -> host.give("bread", 5).give("torch", 10);
            case "set_deployer_filter" -> host.stock("iron_ingot", 10);
            default -> {
                // new examples must run in an empty world, or get a setup here
            }
        }
    }

    private static void verify(String name, MockHost host, ScriptRun run) {
        switch (name) {
            case "planks_from_logs" -> {
                assertEquals(64, host.count("oak_planks"));
                assertEquals(List.of("Done: 64 oak planks."), host.messages);
            }
            case "smelt_iron_stack" -> {
                assertEquals(List.of(new ActionRequest.Fetch("minecraft:raw_iron", 54), new ActionRequest.Fetch("minecraft:coal", 8),
                        new ActionRequest.Smelt("minecraft:iron_ingot", 64)), requests(host));
                assertEquals(64, host.count("iron_ingot"));
            }
            case "restock_and_build" -> {
                assertEquals(List.of(new ActionRequest.Fetch("minecraft:oak_planks", 100), new ActionRequest.Fetch("minecraft:glass_pane", 32),
                        new ActionRequest.Fetch("minecraft:cobblestone", 64),
                        new ActionRequest.Goto(new GotoTarget.Coords(100, 64, -203, null)),
                        new ActionRequest.Build("cabin.schem", 100, 64, -200)), requests(host));
                assertEquals(128, host.count("oak_planks"));
            }
            case "farm_sugar_cane_and_deposit" -> {
                assertEquals(Value.of(12), run.variables().get("passes"));
                assertEquals(12 * 48, host.stored("sugar_cane"));
                assertEquals(5, host.count("bread"), "food is kept");
                assertEquals(10, host.count("torch"));
                assertTrue(host.startedTypes().contains(ActionType.EAT), host.startedTypes()::toString);
                assertEquals("All passes done, the farm script ends.", host.messages.get(host.messages.size() - 1));
                assertTrue(run.endReason().contains("stop"), run.endReason());
            }
            case "set_deployer_filter" -> {
                assertEquals("minecraft:iron_ingot", host.filters.get("120,70,-45"));
                assertEquals(1, host.count("iron_ingot"));
            }
            default -> fail("add expectations for the new example " + name);
        }
    }

    private static List<ActionRequest> requests(MockHost host) {
        return host.started.stream().map(c -> c.request()).toList();
    }
}
