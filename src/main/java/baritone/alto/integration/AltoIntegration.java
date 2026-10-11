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

/**
 * One optional mod integration. Implementations need a public no-argument constructor and must only be loaded when
 * their mod is installed (see {@link Integrations}).
 */
public interface AltoIntegration {

    /** Human name, e.g. "Create". */
    String name();

    /** Its own setting switch (e.g. altoCreate). */
    boolean enabled();

    /** Register adapters, hazard conditions, filter access, storage blocks... Called once per Alto instance. */
    void init(Alto alto);
}
