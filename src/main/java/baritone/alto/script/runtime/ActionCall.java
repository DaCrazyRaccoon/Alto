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

package baritone.alto.script.runtime;

import baritone.alto.script.lang.ActionType;

/**
 * One action for the host to perform, with every argument already evaluated, range-checked and normalized.
 *
 * @param path    location of the block in the script (for logs and the runner UI)
 * @param request the typed arguments; switch over it to perform the action
 */
public record ActionCall(String path, ActionRequest request) {

    public ActionType type() {
        return request.type();
    }
}
