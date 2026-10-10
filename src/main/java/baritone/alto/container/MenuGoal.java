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

package baritone.alto.container;

/**
 * Something to achieve inside an open menu ("take 32 oak logs", "put everything except the keep-list in").
 * <p>
 * A goal never remembers which clicks it already sent. Each call to {@link #decide} looks at the current
 * (server-confirmed) {@link MenuView} and returns the next click, so lag, desyncs and other mods moving items
 * (Inventory Essentials refill, backpack pickup upgrades) are handled by simply re-deciding. The only state a goal
 * keeps is its baseline (counts when it started), captured on the first call.
 */
public interface MenuGoal {

    Decision decide(MenuView view);

    String describe();

    sealed interface Decision permits ClickNext, Done, Fail {
    }

    record ClickNext(Click click) implements Decision {
    }

    record Done(String summary) implements Decision {
    }

    record Fail(String reason) implements Decision {
    }

    static Decision click(Click c) {
        return new ClickNext(c);
    }

    static Decision done(String summary) {
        return new Done(summary);
    }

    static Decision fail(String reason) {
        return new Fail(reason);
    }
}
