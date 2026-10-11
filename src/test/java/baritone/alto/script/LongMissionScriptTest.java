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

import baritone.alto.script.lang.EventType;
import baritone.alto.script.lang.Value;
import baritone.alto.script.lang.ActionType;
import baritone.alto.script.runtime.RunOptions;
import baritone.alto.script.runtime.RunStatus;
import baritone.alto.script.runtime.ScriptRun;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The long self-test script used in the final in-game run (neoforge/src/main/resources/assets/baritoe/alto/selftest/
 * long_mission.alto.json) must validate without errors and finish with result "ok" in the mock world.
 */
class LongMissionScriptTest {

    static ScriptReport load() {
        return ScriptLoader.load(ExamplesTest.read(ExamplesTest.projectFile(
                "neoforge/src/main/resources/assets/baritoe/alto/selftest/long_mission.alto.json")));
    }

    @Test
    void validatesWithoutErrors() {
        ScriptReport r = load();
        assertEquals(List.of(), r.errors(), () -> r.diagnostics().toString());
        assertTrue(r.script().handlers().size() >= 5);
    }

    @Test
    void runsToTheEndWithAllChecksPassing() {
        ScriptReport r = load();
        MockHost host = new MockHost();
        host.x = 100;
        host.y = 70;
        host.z = -20;
        host.hunger = 14;
        host.give("stone_pickaxe", 1).give("stick", 1).give("coal", 1).give("bread", 3);
        host.stock("oak_log", 16).stock("raw_iron", 3).stock("coal", 4).stock("cobblestone", 16)
                .stock("stone_bricks", 20).stock("glass", 20);
        host.recipes.put("minecraft:oak_planks", new MockHost.Recipe("minecraft:oak_log", 1, 4));
        host.recipes.put("minecraft:iron_ingot", new MockHost.Recipe("minecraft:raw_iron", 1, 1));
        // What the builder and the note block look like in the real world after the actions.
        for (int row = 0; row < 3; row++) {
            for (int dx = -5; dx <= -3; dx++) {
                host.blocks.put((100 + dx) + "," + (70 + row) + "," + (-20 + 6), "minecraft:oak_planks");
            }
        }
        host.blocks.put("104,70,-19", "minecraft:note_block");
        host.blocks.put("103,71,-14", "minecraft:glass");

        ScriptRun run = new ScriptRun(r.script(), host, new RunOptions());
        int ticks = 0;
        boolean pinged = false;
        while (!run.status().isEnded() && ticks < 200_000) {
            host.tick(run);
            ticks++;
            if (!pinged && ticks == 400) {
                assertTrue(run.isChatTrigger("!PING "));
                run.fire(EventType.ON_CHAT, "!ping");
                pinged = true;
            }
        }
        assertEquals(RunStatus.FINISHED, run.status(), () -> run.endReason() + " / " + run.lastError() + " / " + host.messages);
        assertEquals(Value.of("ok"), run.variables().get("result"), host.messages::toString);
        assertEquals(Value.of(12), run.variables().get("checks_passed"));
        assertEquals(Value.of(3), run.variables().get("wall_rows"));
        assertTrue(host.startedTypes().containsAll(List.of(ActionType.FETCH, ActionType.CRAFT, ActionType.PLACE_STATION,
                ActionType.PICKUP_STATION, ActionType.SMELT, ActionType.MINE, ActionType.FILL, ActionType.USE_ITEM_ON_BLOCK,
                ActionType.BUILD, ActionType.EAT, ActionType.DEPOSIT, ActionType.GOTO)), host.startedTypes()::toString);
        assertTrue(host.messages.get(host.messages.size() - 1).startsWith("Long mission finished: ok"), host.messages::toString);
    }
}
