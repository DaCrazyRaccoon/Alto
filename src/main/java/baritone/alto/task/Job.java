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

package baritone.alto.task;

import java.util.function.BiConsumer;

/**
 * A top-level unit of work for {@code AltoProcess}: a root task plus how it relates to other Baritone processes.
 *
 * @param root      the task tree to run
 * @param temporary true for supply runs on behalf of another process (the builder): Baritone then keeps the other
 *                  process's state and resumes it afterwards. False for jobs you start yourself (#craft): a newer
 *                  command replaces them, like any Baritone process.
 * @param source    who started it ("command", "builder", "script", "selftest")
 * @param onFinish  called once with (success, message) when the job ends, also when it is cancelled (success=false)
 */
public record Job(AltoTask root, boolean temporary, String source, BiConsumer<Boolean, String> onFinish) {

    public static Job of(AltoTask root, String source) {
        return new Job(root, false, source, (ok, msg) -> {
        });
    }
}
