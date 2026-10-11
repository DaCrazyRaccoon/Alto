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

package baritone.alto.integration.corpse;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.container.SlotRole;
import baritone.alto.container.mc.ContainerAdapter;
import baritone.alto.container.mc.ContainerAdapters;
import baritone.alto.integration.AltoIntegration;
import de.maxhenkel.corpse.entities.CorpseEntity;
import de.maxhenkel.corpse.gui.CorpseContainerBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import java.util.Optional;
import java.util.UUID;

/**
 * Corpse 1.1.13: {@code #corpse} walks to your own corpse and takes your items back (normal right-click and
 * shift-clicks). Corpse menus are never indexed as storage and Alto never puts items into them. The death history
 * view (read-only) is never clicked. Only loaded when Corpse is installed.
 */
public final class CorpseIntegration implements AltoIntegration {

    @Override
    public String name() {
        return "Corpse";
    }

    @Override
    public boolean enabled() {
        return Baritone.settings().altoCorpse.value;
    }

    @Override
    public void init(Alto alto) {
        ContainerAdapters.register(new Adapter());
        alto.setCorpseLocator(CorpseIntegration::nearestOwnCorpse);
    }

    static Optional<Entity> nearestOwnCorpse() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            return Optional.empty();
        }
        UUID me = p.getUUID();
        Optional<Entity> best = Optional.empty();
        double bestDist = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof CorpseEntity c && !c.isRemoved() && c.getCorpseUUID().filter(me::equals).isPresent() && !c.isEmpty()) {
                double d = e.distanceToSqr(p);
                if (d < bestDist) {
                    bestDist = d;
                    best = Optional.of(e);
                }
            }
        }
        return best;
    }

    static final class Adapter implements ContainerAdapter {

        @Override
        public String id() {
            return "corpse:corpse";
        }

        @Override
        public int priority() {
            return 20;
        }

        @Override
        public boolean matches(AbstractContainerMenu menu) {
            return menu instanceof CorpseContainerBase;
        }

        @Override
        public SlotRole roleOf(AbstractContainerMenu menu, Slot slot, int index, Inventory playerInventory) {
            if (slot.container == playerInventory) {
                return null;
            }
            return ((CorpseContainerBase) menu).isEditable() && !((CorpseContainerBase) menu).isHistory() ? SlotRole.STORAGE : SlotRole.OTHER;
        }

        @Override
        public boolean indexable(AbstractContainerMenu menu) {
            return false;
        }

        @Override
        public boolean depositAllowed(AbstractContainerMenu menu) {
            return false;
        }
    }
}
