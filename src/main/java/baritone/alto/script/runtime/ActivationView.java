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

import java.util.List;

/**
 * Read-only view of one running event handler, for the in-game runner screen.
 *
 * @param id          activation number (increasing, unique within the run)
 * @param handler     handler label, e.g. {@code on_chat "!go"}
 * @param handlerPath location of the handler, e.g. {@code events[1]}
 * @param path        location of the current block
 * @param block       type of the current block ({@code craft}, {@code repeat}...), empty at the end of a list
 * @param callStack   active procedure calls, outermost first: {@code restock (called at events[0].body[1])}
 * @param waitingFor  what it waits for ({@code task craft}, {@code wait 2.5 s}...), empty when it is executing
 */
public record ActivationView(int id, String handler, String handlerPath, String path, String block,
                             List<String> callStack, String waitingFor) {

    public ActivationView {
        callStack = List.copyOf(callStack);
    }
}
