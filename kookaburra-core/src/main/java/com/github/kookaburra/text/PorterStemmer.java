package com.github.kookaburra.text;

/**
 * The Porter stemming algorithm (M. F. Porter, 1980), as used by the {@code english} analyzers of Lucene and
 * Elasticsearch: reduces inflected English words to a common stem ({@code connected, connecting, connection} →
 * {@code connect}; {@code movie, movies} → {@code movi}). Only words of the letters a to z are stemmed.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
final class PorterStemmer {

    private char[] b;
    private int k;
    private int j;

    private PorterStemmer() {
    }

    /** The stem of a lower-case word; words with characters outside a to z, or of 2 letters or fewer, are returned. */
    static String stem(String word) {
        if (word.length() <= 2 || !word.chars().allMatch(c -> c >= 'a' && c <= 'z')) {
            return word;
        }
        PorterStemmer s = new PorterStemmer();
        s.b = word.toCharArray();
        s.k = s.b.length - 1;
        s.step1ab();
        if (s.k > 0) {
            s.step1c();
            s.step2();
            s.step3();
            s.step4();
            s.step5();
        }
        return new String(s.b, 0, s.k + 1);
    }

    /** Whether b[i] is a consonant. */
    private boolean cons(int i) {
        switch (b[i]) {
            case 'a', 'e', 'i', 'o', 'u':
                return false;
            case 'y':
                return i == 0 || !cons(i - 1);
            default:
                return true;
        }
    }

    /** The number of consonant sequences between 0 and j: [C](VC)^m[V]. */
    private int m() {
        int n = 0;
        int i = 0;
        while (true) {
            if (i > j) {
                return n;
            }
            if (!cons(i)) {
                break;
            }
            i++;
        }
        i++;
        while (true) {
            while (true) {
                if (i > j) {
                    return n;
                }
                if (cons(i)) {
                    break;
                }
                i++;
            }
            i++;
            n++;
            while (true) {
                if (i > j) {
                    return n;
                }
                if (!cons(i)) {
                    break;
                }
                i++;
            }
            i++;
        }
    }

    /** Whether 0..j contains a vowel. */
    private boolean vowelInStem() {
        for (int i = 0; i <= j; i++) {
            if (!cons(i)) {
                return true;
            }
        }
        return false;
    }

    /** Whether j-1, j is a double consonant. */
    private boolean doublec(int x) {
        return x >= 1 && b[x] == b[x - 1] && cons(x);
    }

    /** Whether i-2, i-1, i is consonant-vowel-consonant and the last is not w, x or y (e.g. hop, not snow). */
    private boolean cvc(int i) {
        if (i < 2 || !cons(i) || cons(i - 1) || !cons(i - 2)) {
            return false;
        }
        char ch = b[i];
        return ch != 'w' && ch != 'x' && ch != 'y';
    }

    private boolean ends(String s) {
        int l = s.length();
        int o = k - l + 1;
        if (o < 0) {
            return false;
        }
        for (int i = 0; i < l; i++) {
            if (b[o + i] != s.charAt(i)) {
                return false;
            }
        }
        j = k - l;
        return true;
    }

    /** Sets j+1..k to {@code s}. */
    private void setto(String s) {
        int l = s.length();
        int o = j + 1;
        if (o + l > b.length) {
            char[] n = new char[o + l];
            System.arraycopy(b, 0, n, 0, b.length);
            b = n;
        }
        for (int i = 0; i < l; i++) {
            b[o + i] = s.charAt(i);
        }
        k = j + l;
    }

    private void r(String s) {
        if (m() > 0) {
            setto(s);
        }
    }

    /** Plurals and -ed / -ing: caresses → caress, ponies → poni, meetings → meet, agreed → agree, hopping → hop. */
    private void step1ab() {
        if (b[k] == 's') {
            if (ends("sses")) {
                k -= 2;
            } else if (ends("ies")) {
                setto("i");
            } else if (b[k - 1] != 's') {
                k--;
            }
        }
        if (ends("eed")) {
            if (m() > 0) {
                k--;
            }
        } else if ((ends("ed") || ends("ing")) && vowelInStem()) {
            k = j;
            if (ends("at")) {
                setto("ate");
            } else if (ends("bl")) {
                setto("ble");
            } else if (ends("iz")) {
                setto("ize");
            } else if (doublec(k)) {
                k--;
                char ch = b[k];
                if (ch == 'l' || ch == 's' || ch == 'z') {
                    k++;
                }
            } else if (m() == 1 && cvc(k)) {
                setto("e");
            }
        }
    }

    /** Terminal y → i when there is another vowel in the stem. */
    private void step1c() {
        if (ends("y") && vowelInStem()) {
            b[k] = 'i';
        }
    }

    /** Double suffixes to single ones: -ization → -ize, -ational → -ate, … */
    private void step2() {
        if (k == 0) {
            return;
        }
        switch (b[k - 1]) {
            case 'a' -> {
                if (ends("ational")) {
                    r("ate");
                } else if (ends("tional")) {
                    r("tion");
                }
            }
            case 'c' -> {
                if (ends("enci")) {
                    r("ence");
                } else if (ends("anci")) {
                    r("ance");
                }
            }
            case 'e' -> {
                if (ends("izer")) {
                    r("ize");
                }
            }
            case 'l' -> {
                if (ends("bli")) {
                    r("ble");
                } else if (ends("alli")) {
                    r("al");
                } else if (ends("entli")) {
                    r("ent");
                } else if (ends("eli")) {
                    r("e");
                } else if (ends("ousli")) {
                    r("ous");
                }
            }
            case 'o' -> {
                if (ends("ization")) {
                    r("ize");
                } else if (ends("ation")) {
                    r("ate");
                } else if (ends("ator")) {
                    r("ate");
                }
            }
            case 's' -> {
                if (ends("alism")) {
                    r("al");
                } else if (ends("iveness")) {
                    r("ive");
                } else if (ends("fulness")) {
                    r("ful");
                } else if (ends("ousness")) {
                    r("ous");
                }
            }
            case 't' -> {
                if (ends("aliti")) {
                    r("al");
                } else if (ends("iviti")) {
                    r("ive");
                } else if (ends("biliti")) {
                    r("ble");
                }
            }
            case 'g' -> {
                if (ends("logi")) {
                    r("log");
                }
            }
            default -> {
            }
        }
    }

    /** -ic-, -full, -ness etc. */
    private void step3() {
        switch (b[k]) {
            case 'e' -> {
                if (ends("icate")) {
                    r("ic");
                } else if (ends("ative")) {
                    r("");
                } else if (ends("alize")) {
                    r("al");
                }
            }
            case 'i' -> {
                if (ends("iciti")) {
                    r("ic");
                }
            }
            case 'l' -> {
                if (ends("ical")) {
                    r("ic");
                } else if (ends("ful")) {
                    r("");
                }
            }
            case 's' -> {
                if (ends("ness")) {
                    r("");
                }
            }
            default -> {
            }
        }
    }

    /** -ant, -ence etc. in context <c>vcvc<v>. */
    private void step4() {
        if (k == 0) {
            return;
        }
        boolean found = switch (b[k - 1]) {
            case 'a' -> ends("al");
            case 'c' -> ends("ance") || ends("ence");
            case 'e' -> ends("er");
            case 'i' -> ends("ic");
            case 'l' -> ends("able") || ends("ible");
            case 'n' -> ends("ant") || ends("ement") || ends("ment") || ends("ent");
            case 'o' -> (ends("ion") && j >= 0 && (b[j] == 's' || b[j] == 't')) || ends("ou");
            case 's' -> ends("ism");
            case 't' -> ends("ate") || ends("iti");
            case 'u' -> ends("ous");
            case 'v' -> ends("ive");
            case 'z' -> ends("ize");
            default -> false;
        };
        if (found && m() > 1) {
            k = j;
        }
    }

    /** Removes a final -e if m() > 1, and changes -ll to -l if m() > 1. */
    private void step5() {
        j = k;
        if (b[k] == 'e') {
            int a = m();
            if (a > 1 || (a == 1 && !cvc(k - 1))) {
                k--;
            }
        }
        if (b[k] == 'l' && doublec(k) && m() > 1) {
            k--;
        }
    }
}
