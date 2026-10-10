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

/**
 * Fixed limits of the language. The same numbers are in docs/alto/SCRIPT_LANGUAGE.md and the JSON schema.
 */
public final class Limits {

    /** Item counts of mine/craft/smelt/fetch and {@code on_item_below}: 1 to this. */
    public static final int MAX_COUNT = 100_000;
    /** {@code repeat} times: 0 to this. */
    public static final int MAX_REPEAT = 100_000;
    /** Literal counts at or above this get a warning. */
    public static final int LARGE_COUNT = 10_000;
    /** Literal {@code fill} volumes above this get a warning. */
    public static final long LARGE_FILL_VOLUME = 100_000;
    /** {@code wait} and {@code wait_until} timeouts: 0 to this many seconds (one day). */
    public static final double MAX_WAIT_SECONDS = 86_400;
    /** {@code every}: at least this many seconds. */
    public static final double MIN_EVERY_SECONDS = 1;
    /** Block coordinates: -this to +this (the vanilla world border). */
    public static final int MAX_COORDINATE = 30_000_000;
    /** {@code random} bounds: -this to +this. */
    public static final int MAX_RANDOM = 1_000_000_000;
    /** {@code farm} range in blocks. */
    public static final int MAX_FARM_RANGE = 256;
    /** {@code on_health_below} threshold: 1 to this. */
    public static final double MAX_HEALTH = 1024;
    /** Length of chat text, Baritone commands and trigger-like strings (the vanilla chat limit). */
    public static final int MAX_CHAT_LENGTH = 256;
    /** Length of chat triggers. */
    public static final int MAX_TRIGGER_LENGTH = 64;
    /** Length of names of variables, procedures, parameters, homes, zones and train stations. */
    public static final int MAX_NAME_LENGTH = 64;
    /** Length of message texts and other literal strings. */
    public static final int MAX_TEXT_LENGTH = 1000;
    /** Length of the script name. */
    public static final int MAX_SCRIPT_NAME_LENGTH = 100;
    /** Length of the script description. */
    public static final int MAX_DESCRIPTION_LENGTH = 4000;
    /** Length of any string value produced while running ({@code join}). */
    public static final int MAX_STRING_VALUE = 10_000;
    /** Size of a script file in characters. */
    public static final int MAX_SCRIPT_CHARS = 1_000_000;
    /** JSON nesting depth. */
    public static final int MAX_JSON_DEPTH = 100;
    /** Number of statements plus expression objects in one script. */
    public static final int MAX_NODES = 20_000;

    private Limits() {
    }
}
