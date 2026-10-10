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

import baritone.alto.core.ItemKey;

import java.util.regex.Pattern;

/**
 * Item/block ids, names and file names as they may appear in scripts.
 */
public final class Ids {

    private static final Pattern ID = Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9 _.()+\\-]+");
    private static final int MAX_FILE_NAME = 128;

    private Ids() {
    }

    /** {@code oak_planks} or {@code minecraft:oak_planks}; lower case, no spaces. */
    public static boolean isValidId(String id) {
        return id != null && id.length() <= 200 && ID.matcher(id).matches();
    }

    /** Adds the {@code minecraft:} namespace when it is missing. The id must be valid. */
    public static String normalize(String id) {
        return ItemKey.of(id).id();
    }

    /** Names of variables, procedures and parameters: a letter or underscore, then letters, digits, underscores. */
    public static boolean isValidName(String name) {
        return name != null && name.length() <= Limits.MAX_NAME_LENGTH && NAME.matcher(name).matches();
    }

    /**
     * A plain file name (schematics): letters, digits, space and {@code _ . ( ) + -}; no folders, no {@code ..},
     * not starting with a dot. The host resolves it inside its own schematics folder.
     */
    public static boolean isValidFileName(String name) {
        return name != null && !name.isEmpty() && name.length() <= MAX_FILE_NAME && FILE_NAME.matcher(name).matches()
                && !name.startsWith(".") && !name.contains("..") && name.equals(name.trim());
    }
}
