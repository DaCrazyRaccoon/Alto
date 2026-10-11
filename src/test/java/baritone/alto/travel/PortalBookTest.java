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

package baritone.alto.travel;

import baritone.alto.travel.PortalBook.Pos;
import baritone.alto.travel.PortalBook.Route;
import org.junit.jupiter.api.Test;

import static baritone.alto.travel.PortalBook.NETHER;
import static baritone.alto.travel.PortalBook.OVERWORLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalBookTest {

    @Test
    void portalBlocksOfOnePortalAreOnePortal() {
        PortalBook b = new PortalBook();
        assertTrue(b.addPortal(new Pos(OVERWORLD, 10, 64, 10)));
        assertFalse(b.addPortal(new Pos(OVERWORLD, 11, 66, 10)));
        assertTrue(b.addPortal(new Pos(NETHER, 11, 66, 10)));
        assertEquals(2, b.portals().size());
    }

    @Test
    void unknownArrivalUsesTheEightToOneScale() {
        PortalBook b = new PortalBook();
        b.addPortal(new Pos(OVERWORLD, 800, 64, -160));
        Route r = b.best(OVERWORLD, 790, 64, -160, NETHER, 100, -20).orElseThrow();
        assertEquals(new Pos(NETHER, 100, 64, -20), r.arrival());
        assertFalse(r.arrivalKnown());
    }

    @Test
    void knownLinkIsUsedAndPreferred() {
        PortalBook b = new PortalBook();
        Pos near = new Pos(OVERWORLD, 0, 64, 0);
        Pos far = new Pos(OVERWORLD, 400, 64, 0);
        b.addPortal(near);
        b.addPortal(far);
        // walking through the near portal once arrived at nether 50,70,0 (not 0,64,0)
        b.addLink(near, new Pos(NETHER, 50, 70, 0));
        Route r = b.best(OVERWORLD, 0, 64, 0, NETHER, 50, 0).orElseThrow();
        assertEquals(near, r.portal());
        assertTrue(r.arrivalKnown());
        assertEquals(new Pos(NETHER, 50, 70, 0), r.arrival());
    }

    @Test
    void farTargetPicksThePortalThatSavesWalking() {
        PortalBook b = new PortalBook();
        b.addPortal(new Pos(NETHER, 0, 64, 0));      // arrives near overworld 0,0
        b.addPortal(new Pos(NETHER, 125, 64, 0));    // arrives near overworld 1000,0
        Route r = b.best(NETHER, 0, 64, 0, OVERWORLD, 1000, 0).orElseThrow();
        assertEquals(125, r.portal().x());
    }

    @Test
    void noRouteToTheEndOrWithoutPortals() {
        PortalBook b = new PortalBook();
        assertTrue(b.best(OVERWORLD, 0, 64, 0, NETHER, 0, 0).isEmpty());
        b.addPortal(new Pos(OVERWORLD, 0, 64, 0));
        assertTrue(b.best(OVERWORLD, 0, 64, 0, "minecraft:the_end", 0, 0).isEmpty());
    }

    @Test
    void jsonRoundTripAndForget() {
        PortalBook b = new PortalBook();
        Pos a = new Pos(OVERWORLD, 1, 2, 3);
        b.addLink(a, new Pos(NETHER, 4, 5, 6));
        PortalBook c = PortalBook.fromJson(b.toJson());
        assertEquals(b.portals(), c.portals());
        assertEquals(b.links(), c.links());
        c.forget(a);
        assertEquals(1, c.portals().size());
        assertTrue(c.links().isEmpty());
    }

    @Test
    void dimensionNames() {
        assertEquals(NETHER, PortalBook.parseDimension("nether"));
        assertEquals(OVERWORLD, PortalBook.parseDimension("Overworld"));
        assertEquals(null, PortalBook.parseDimension("moon"));
    }
}
