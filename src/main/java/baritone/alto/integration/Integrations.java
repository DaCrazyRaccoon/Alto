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

package baritone.alto.integration;

import baritone.alto.Alto;
import baritone.alto.AltoLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional (soft) integrations with other mods. The loader-specific entry point registers the class NAME of each
 * integration whose mod is installed; the class is only loaded then, by name, so a missing mod can never cause a
 * missing-class error. Each integration is wrapped: if it throws while starting, it is switched off with a warning
 * and Baritone keeps working.
 * <p>
 * To add one: implement {@link AltoIntegration} under baritone.alto.integration.&lt;mod&gt;, compile against the mod
 * as compileOnly, and register its class name in the NeoForge entry class (docs/alto/DEVELOPER.md).
 */
public final class Integrations {

    private static final Map<String, String> REGISTERED = new LinkedHashMap<>();
    private static final List<String> ACTIVE = new ArrayList<>();
    private static final List<String> FAILED = new ArrayList<>();

    private Integrations() {
    }

    /** Called by the loader entry point for each installed mod Alto knows. */
    public static synchronized void register(String modId, String className) {
        REGISTERED.put(modId, className);
    }

    /** Called once per Alto instance. */
    public static synchronized void initAll(Alto alto) {
        for (Map.Entry<String, String> e : REGISTERED.entrySet()) {
            try {
                Class<?> cls = Class.forName(e.getValue(), true, Integrations.class.getClassLoader());
                AltoIntegration integration = (AltoIntegration) cls.getDeclaredConstructor().newInstance();
                if (!integration.enabled()) {
                    AltoLog.info("integration " + integration.name() + " is switched off in the settings");
                    continue;
                }
                integration.init(alto);
                ACTIVE.add(integration.name());
                AltoLog.info("integration " + integration.name() + " active");
            } catch (Throwable t) {
                FAILED.add(e.getKey() + " (" + t + ")");
                AltoLog.info("integration for " + e.getKey() + " disabled: " + t);
            }
        }
    }

    public static synchronized List<String> active() {
        return List.copyOf(ACTIVE);
    }

    public static synchronized List<String> failed() {
        return List.copyOf(FAILED);
    }

    public static synchronized boolean isActive(String name) {
        return ACTIVE.contains(name);
    }
}
