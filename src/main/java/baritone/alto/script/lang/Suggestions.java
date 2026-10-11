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

import java.util.Collection;

/**
 * "Did you mean ...?" hints for misspelled names (edit distance, deterministic: the first best candidate wins).
 */
public final class Suggestions {

    private Suggestions() {
    }

    /** {@code " (did you mean 'craft'?)"} or an empty string when nothing is close. */
    public static String hint(String typed, Collection<String> candidates) {
        String best = closest(typed, candidates);
        return best == null ? "" : " (did you mean '" + best + "'?)";
    }

    /** The closest candidate within an edit distance of 2 (1 for short words), or {@code null}. */
    public static String closest(String typed, Collection<String> candidates) {
        if (typed == null || typed.isEmpty()) {
            return null;
        }
        int limit = typed.length() <= 4 ? 1 : 2;
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int d = distance(typed, candidate);
            if (d <= limit && d < bestDistance) {
                best = candidate;
                bestDistance = d;
            }
        }
        return best;
    }

    /** Edit distance where swapping two neighbouring letters counts as one edit (optimal string alignment). */
    static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
