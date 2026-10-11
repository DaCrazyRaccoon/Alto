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

package baritone.alto.neoforge;

import baritone.alto.integration.Integrations;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

import java.util.Map;

/**
 * NeoForge side of the Alto fork: registers the optional integrations (by class name, only for installed mods),
 * key mappings, the HUD layer and the settings screen. Client-only like the rest of Baritone; it registers no
 * network channels, so servers never need this mod.
 */
public final class AltoNeoForge {

    /** mod id -> integration class (in baritone.alto.integration.*). */
    private static final Map<String, String> INTEGRATIONS = Map.of(
            "create", "baritone.alto.integration.create.CreateIntegration",
            "emi", "baritone.alto.integration.emi.EmiIntegration",
            "sophisticatedcore", "baritone.alto.integration.sophisticated.SophisticatedIntegration",
            "openpartiesandclaims", "baritone.alto.integration.claims.ClaimsIntegration",
            "corpse", "baritone.alto.integration.corpse.CorpseIntegration"
    );

    private AltoNeoForge() {
    }

    public static void init(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        // Extra 11: the settings screen behind the Mods list "Config" button and #alto settings.
        container.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                (mc, parent) -> new AltoSettingsScreen(parent));
        baritone.alto.Alto.setScreenOpeners(
                () -> net.minecraft.client.Minecraft.getInstance().setScreen(new AltoSettingsScreen(null)),
                () -> net.minecraft.client.Minecraft.getInstance().setScreen(new ScriptRunnerScreen(null)));
        init(modBus);
    }

    public static void init(IEventBus modBus) {
        ModList mods = ModList.get();
        INTEGRATIONS.forEach((modId, cls) -> {
            if (mods.isLoaded(modId)) {
                Integrations.register(modId, cls);
            }
        });
        AltoClientEvents.init(modBus);
    }
}
