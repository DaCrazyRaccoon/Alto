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

/**
 * Why a run failed.
 *
 * @param path    location of the block that failed, e.g. {@code events[0].body[2]}
 * @param message human-readable reason
 */
public record ScriptError(String path, String message) {

    /** {@code events[0].body[2]: craft failed: no oak logs} */
    @Override
    public String toString() {
        return path.isEmpty() ? message : path + ": " + message;
    }
}
