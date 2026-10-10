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
import baritone.alto.container.MenuGoal;
import baritone.alto.container.mc.ContainerSession;

/**
 * Runs one {@link MenuGoal} in the player's own inventory (e.g. "put the planks on the hotbar"), then closes.
 */
public final class MenuGoalTask extends AltoTask {

    private final String name;
    private final MenuGoal goal;
    private final ContainerSession session;
    private boolean opened;
    private boolean goalDone;

    public MenuGoalTask(Alto alto, String name, MenuGoal goal) {
        super(alto);
        this.name = name;
        this.goal = goal;
        this.session = new ContainerSession(alto);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Step tick(boolean calcFailed) {
        if (!opened) {
            session.openInventory();
            opened = true;
            return Step.stay();
        }
        if (!goalDone) {
            ContainerSession.Status st = session.run(goal);
            if (st == ContainerSession.Status.FAILED) {
                return Step.failure(session.failure());
            }
            if (st == ContainerSession.Status.DONE) {
                goalDone = true;
            }
            return Step.stay();
        }
        return session.close() == ContainerSession.Status.DONE ? Step.success(goal.describe()) : Step.stay();
    }

    @Override
    public void cancel() {
        session.closeNow();
    }
}
