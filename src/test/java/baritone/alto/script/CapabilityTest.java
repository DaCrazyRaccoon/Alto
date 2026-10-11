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

import baritone.alto.script.check.Capability;
import baritone.alto.script.check.CapabilitySummary;
import baritone.alto.script.lang.ActionType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static baritone.alto.script.Scripts.onStart;
import static baritone.alto.script.Scripts.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityTest {

    @Test
    void everythingFixtureUsesEveryCapability() {
        CapabilitySummary s = CapabilitySummary.of(parse(Fixtures.EVERYTHING));
        assertEquals(EnumSet.allOf(Capability.class), s.capabilities());
        assertEquals(List.of("mine diamond_ore"), s.commands());
        assertEquals(List.of("hello"), s.chatTexts());
        assertEquals(List.of("!go"), s.chatTriggers());
        assertEquals(List.of("build", "farm", "fill", "mine", "pickup_station"), s.sources().get(Capability.BREAKS_BLOCKS));
        assertEquals(List.of("every", "forever", "on_chat", "on_health_below", "on_inventory_full", "on_item_below"),
                s.sources().get(Capability.RUNS_UNTIL_STOPPED));
        List<String> sentences = s.sentences();
        assertTrue(sentences.contains("Breaks blocks (build, farm, fill, mine, pickup_station)."), sentences::toString);
        assertTrue(sentences.contains("Runs Baritone commands: mine diamond_ore."), sentences::toString);
        assertTrue(sentences.contains("Sends chat messages or server commands as you: \"hello\"."), sentences::toString);
        assertTrue(sentences.contains("Walks through portals (take_portal)."), sentences::toString);
        assertEquals(Capability.values().length, sentences.size());
    }

    @Test
    void simpleScriptsListOnlyWhatTheyDo() {
        CapabilitySummary s = CapabilitySummary.of(parse(onStart("{'block':'craft','item':'oak_planks','count':4}",
                "{'block':'wait','seconds':1}", "{'block':'message','text':'done'}")));
        assertEquals(EnumSet.of(Capability.MOVES, Capability.CRAFTS), s.capabilities());
        assertEquals(List.of("Walks around (craft).", "Crafts items (craft)."), s.sentences());
        assertFalse(s.has(Capability.RUNS_UNTIL_STOPPED));
        assertTrue(CapabilitySummary.of(parse(onStart())).capabilities().isEmpty());
    }

    @Test
    void computedChatTextIsMarkedAndCommandsAreNormalized() {
        CapabilitySummary s = CapabilitySummary.of(parse(Scripts.withVars("{'name':'n','initial':1}",
                "{'block':'send_chat','text':{'expr':'join','parts':['n=',{'expr':'var','name':'n'}]}}",
                "{'block':'command','command':'#goto 0 64 0'}", "{'block':'command','command':'goto 0 64 0'}")));
        assertEquals(List.of(CapabilitySummary.COMPUTED_TEXT), s.chatTexts());
        assertEquals(List.of("goto 0 64 0"), s.commands());
    }

    @Test
    void everyActionHasACapabilityMapping() {
        for (ActionType type : ActionType.values()) {
            boolean worldAction = type.isHostTask();
            assertEquals(worldAction, !Capability.of(type).isEmpty(), type::jsonName);
        }
    }

    @Test
    void loaderReportCombinesEverything() {
        ScriptReport ok = ScriptLoader.load(Fixtures.EVERYTHING);
        assertTrue(ok.runnable());
        assertNotNull(ok.hash());
        assertNotNull(ok.capabilities());
        assertEquals(3, ok.warnings().size());
        ScriptReport broken = ScriptLoader.load("{");
        assertFalse(broken.runnable());
        assertNull(broken.script());
        assertNull(broken.capabilities());
        assertEquals(1, broken.errors().size());
        ScriptReport invalid = ScriptLoader.load(onStart("{'block':'break'}"));
        assertFalse(invalid.runnable());
        assertNotNull(invalid.script(), "parsed but not runnable: the editor can still show it");
    }
}
