package io.freemytv;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Search matching, no Android in it (tools/TestData.java runs it on a plain JVM): case and accent insensitive,
 * word prefix, substring, and one typo for words of 4 letters or more. Same rules as the design mock (search.js).
 */
final class Fuzzy {
    private Fuzzy() {}

    /** "Amélie  Poulain!" → "amelie poulain". */
    static String norm(String s) {
        if (s == null) return "";
        String d = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return d.replaceAll("\\p{M}+", "").replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** a and b are equal, or differ by one letter added, removed or changed. */
    static boolean oneEdit(String a, String b) {
        if (a.equals(b)) return true;
        int la = a.length(), lb = b.length(), i = 0;
        if (Math.abs(la - lb) > 1) return false;
        while (i < la && i < lb && a.charAt(i) == b.charAt(i)) i++;
        if (la == lb) return a.substring(i + 1).equals(b.substring(i + 1));
        return la > lb ? a.substring(i + 1).equals(b.substring(i)) : a.substring(i).equals(b.substring(i + 1));
    }

    private static int word(String q, String[] words, int prefix, int inside, int typo) {
        int best = 0;
        for (String x : words) {
            int s;
            if (x.startsWith(q)) s = prefix;
            else if (q.length() >= 3 && x.indexOf(q) > 0) s = inside;
            else if (q.length() >= 4 && (oneEdit(q, x) || oneEdit(q, x.substring(0, Math.min(x.length(), q.length()))))) s = typo;
            else s = 0;
            if (s > best) best = s;
        }
        return best;
    }

    /**
     * How well `query` matches `primary` (a title or an app name) and, less, `secondary` (cast, genres): 0 = no match.
     * Every word of the query must match something.
     */
    static int score(String query, String primary, String secondary) {
        String q = norm(query), t = norm(primary);
        if (q.isEmpty()) return 0;
        String[] tw = t.split(" "), sw = norm(secondary).split(" ");
        int total = 0;
        if (t.startsWith(q)) total += 1000;
        else if (q.length() >= 3 && t.indexOf(q) > 0) total += 500;
        for (String w : q.split(" ")) {
            int m = Math.max(word(w, tw, 100, 60, 40), word(w, sw, 25, 15, 10));
            if (m == 0) return 0;
            total += m;
        }
        return total;
    }

    static int score(String query, String primary) {
        return score(query, primary, "");
    }
}
