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

package baritone.alto.script.lang;

import java.util.List;

/**
 * The value of one action field, shaped by its {@link FieldKind}.
 */
public sealed interface ActionArg {

    /** Expression kinds (number, integer, string, any, id, file name). */
    record ExprArg(Expr expr) implements ActionArg {
    }

    /** {@link FieldKind#CHOICE}: one of the allowed names. */
    record ChoiceArg(String value) implements ActionArg {
    }

    /** {@link FieldKind#TEXT}: a literal string. */
    record TextArg(String text) implements ActionArg {
    }

    /** {@link FieldKind#ID_LIST}. */
    record ListArg(List<Expr> items) implements ActionArg {
        public ListArg {
            items = List.copyOf(items);
        }
    }

    /** {@link FieldKind#TARGET}. */
    record TargetArg(Target target) implements ActionArg {
    }
}
