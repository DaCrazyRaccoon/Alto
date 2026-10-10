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

import baritone.alto.Alto;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs child tasks one after another; fails when a child fails.
 */
public class SequenceTask extends AltoTask {

    private final String name;
    private final List<AltoTask> children;
    private int index;

    public SequenceTask(Alto alto, String name, List<AltoTask> children) {
        super(alto);
        this.name = name;
        this.children = new ArrayList<>(children);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Step tick(boolean calcFailed) {
        while (index < children.size()) {
            AltoTask child = children.get(index);
            Step s = child.tick(calcFailed);
            detail(child.name() + (child.detail().isEmpty() ? "" : ": " + child.detail()));
            progress(index, children.size());
            if (s.kind() == Step.Kind.SUCCESS) {
                child.cancel();
                index++;
                calcFailed = false;
                continue;
            }
            if (s.kind() == Step.Kind.FAILURE) {
                child.cancel();
                return Step.failure(child.name() + ": " + s.message());
            }
            return s;
        }
        return Step.success("all " + children.size() + " steps done");
    }

    @Override
    public void cancel() {
        if (index < children.size()) {
            children.get(index).cancel();
        }
    }

    public AltoTask current() {
        return index < children.size() ? children.get(index) : null;
    }
}
