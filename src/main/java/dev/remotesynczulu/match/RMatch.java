package dev.remotesynczulu.match;

/**
 * rsync-style wildcard matcher, translated from openrsync's rmatch.c.
 *
 * <p>Supports {@code *} (matches within a path segment), {@code **}
 * (matches across segment boundaries), {@code ?}, bracket expressions
 * {@code [abc]} / {@code [a-z]} / {@code [!a-z]}, POSIX character classes
 * such as {@code [[:alpha:]]}, and backslash escapes.
 *
 * <p>The C original is built on {@code goto}; this translation uses an
 * explicit state machine that follows the original control flow step for
 * step. {@link #rmatch(String, String, boolean)} returns {@code true} when
 * the string matches (the C function returns 0 on match).
 */
public final class RMatch {

    private RMatch() {
    }

    /** Mutable string cursor emulating the C {@code const char **} idiom. */
    private static final class Cur {
        final String s;
        int p;

        Cur(String s) {
            this.s = s;
        }

        int ch() {
            return p < s.length() ? s.charAt(p) : '\0';
        }

        int chAt(int i) {
            int q = p + i;
            return q < s.length() ? s.charAt(q) : '\0';
        }

        int take() {
            return p < s.length() ? s.charAt(p++) : '\0';
        }

        boolean end() {
            return p >= s.length();
        }

        int indexOf(char c) {
            int i = s.indexOf(c, p);
            return i < 0 ? s.length() : i;
        }
    }

    // Matcher states (see rmatch.c for the equivalent goto labels).
    private enum St {
        OUTER_CHECK,    // top of the outer while loop
        LEAD_SLASH,     // consume a leading '/' pair, set segend
        I1_CHECK,       // top of the inner while (*pattern) loop
        STARS,          // consume '*'/'?' runs, handle '**'
        I2_CHECK,       // while (string < segend)
        MATCHSUB,       // attempt a submatch
        FAILED,         // failed_match: retry at next segment after '**'
        AFTER_I2,       // "at end of string segment, eat up any extra '*'"
        BOUNDARY,       // post-loop boundary checks
        FINAL,          // both cursors exhausted?
        MATCH,
        NOMATCH
    }

    /** Overall matcher context. */
    private static final class Ctx {
        final Cur pat;
        final Cur str;
        int segend;
        int segnext;
        int mismatch = -1;  // pattern index saved when '**' seen
        boolean wild;

        Ctx(String pat, String str) {
            this.pat = new Cur(pat);
            this.str = new Cur(str);
        }
    }

    /**
     * Match {@code string} against {@code pattern}.
     *
     * @param leadingDir when true, a pattern that is fully consumed while
     *                   the string still has a trailing "/" counts as a
     *                   match (parent-directory matching)
     */
    public static boolean rmatch(String pattern, String string, boolean leadingDir) {
        Ctx c = new Ctx(pattern, string);
        St st = St.LEAD_SLASH;

        while (st != St.MATCH && st != St.NOMATCH) {
            st = switch (st) {
                case OUTER_CHECK -> outerCheck(c);
                case LEAD_SLASH -> leadSlash(c);
                case I1_CHECK -> c.pat.end() ? St.BOUNDARY : St.STARS;
                case STARS -> stars(c);
                case I2_CHECK -> c.str.p < c.segend ? St.MATCHSUB : St.AFTER_I2;
                case MATCHSUB -> matchsub0(c) ? St.AFTER_I2 : St.FAILED;
                case FAILED -> failed(c);
                case AFTER_I2 -> afterI2(c);
                case BOUNDARY -> boundary(c);
                case FINAL -> finalState(c, leadingDir);
                default -> throw new IllegalStateException();
            };
        }
        return st == St.MATCH;
    }

    public static boolean rmatch(String pattern, String string) {
        return rmatch(pattern, string, false);
    }

    // ---- state handlers ------------------------------------------------

    private static St outerCheck(Ctx c) {
        if (!c.pat.end() && !c.str.end()) {
            return St.LEAD_SLASH;
        }
        return St.FINAL;
    }

    private static St leadSlash(Ctx c) {
        if (c.pat.ch() == '\\' && c.pat.chAt(1) == '/') {
            c.pat.p++;
        }
        if (c.str.ch() == '/' && c.pat.ch() == '/') {
            c.str.p++;
            c.pat.p++;
        }
        c.segend = c.str.indexOf('/');
        return St.I1_CHECK;
    }

    private static St stars(Ctx c) {
        c.wild = false;
        boolean starstar = false;
        for (;;) {
            int ch = c.pat.ch();
            if (ch == '*') {
                if (c.pat.chAt(1) == '*') {
                    starstar = true;
                    c.pat.p++;
                }
                c.wild = true;
                c.pat.p++;
            } else if (ch == '?') {
                if (!starstar) {
                    if (c.str.p < c.segend && c.str.ch() != '/') {
                        c.str.p++;
                        c.pat.p++;
                    } else {
                        return St.NOMATCH;
                    }
                } else {
                    break;
                }
            } else {
                break;
            }
        }
        if (starstar && c.pat.end()) {
            return St.MATCH;
        }
        if (starstar) {
            c.segnext = c.segend;
            c.mismatch = c.pat.p;
        }
        return St.I2_CHECK;
    }

    /** failed_match: retry with the next string segment after a '**'. */
    private static St failed(Ctx c) {
        if (c.mismatch < 0) {
            return St.NOMATCH;
        }
        c.pat.p = c.mismatch;
        c.wild = true;
        c.str.p = c.segnext;
        if (c.str.ch() == '/') {
            c.str.p++;
        }
        c.segend = c.str.indexOf('/');
        c.segnext = c.segend;
        if (c.str.p < c.segend) {
            return St.I2_CHECK;
        }
        return St.NOMATCH;
    }

    private static St afterI2(Ctx c) {
        if (c.str.p >= c.segend && c.pat.ch() != '*') {
            return St.BOUNDARY;
        }
        return St.I1_CHECK;
    }

    private static St boundary(Ctx c) {
        if (!c.str.end() && c.str.ch() != '/') {
            return St.FAILED;
        }
        if (!c.pat.end() && c.pat.ch() != '/') {
            return St.FAILED;
        }
        return St.OUTER_CHECK;
    }

    private static St finalState(Ctx c, boolean leadingDir) {
        if (c.pat.end() && c.str.end()) {
            return St.MATCH;
        }
        if (leadingDir && c.pat.end() && c.str.ch() == '/') {
            return St.MATCH;
        }
        return St.NOMATCH;
    }

    // ---- submatch machinery ---------------------------------------------

    /** I2 body: run matchsub; true = submatch succeeded. */
    private static boolean matchsub0(Ctx c) {
        Cur pat = c.pat;
        Cur str = c.str;
        int segEnd = c.segend;

        // How many characters will this submatch consume?
        int matchlen = 0;
        for (int q = pat.p; q < pat.s.length(); matchlen++) {
            char pc = pat.s.charAt(q);
            if (pc == '*') {
                break;
            }
            if (pc == '/' || (pc == '\\' && q + 1 < pat.s.length()
                    && pat.s.charAt(q + 1) == '/')) {
                if (c.wild) {
                    if (str.p > segEnd - matchlen) {
                        return false;
                    }
                    str.p = segEnd - matchlen;
                    c.wild = false;
                }
                break;
            }
            Cur dummy = new Cur(pat.s);
            dummy.p = q;
            matchchar(dummy, ' ');
            q = dummy.p;
        }

        // Saved entry position: wild restarts advance this by one each
        // time (C: string = ++*ss).
        int entryStr = str.p;

        if (str.p > segEnd - matchlen) {
            return false;
        }
        if (pat.end() && c.wild) {
            str.p = segEnd - matchlen;
            c.wild = false;
        }

        int patStart = pat.p;
        while (!pat.end() && pat.ch() != '*') {
            if (pat.ch() == '\\' && pat.chAt(1) == '/') {
                pat.p++;
            }
            if (pat.ch() == '/') {
                // hit the barrier but we still have characters left
                return str.p >= segEnd;
            }
            if (str.p >= segEnd) {
                return false;
            }
            if (matchchar(pat, (char) str.take()) == 0) {
                continue;
            }
            if (c.wild) {
                // skip forward one char and restart the whole submatch
                str.p = ++entryStr;
                pat.p = patStart;
                if (str.p > segEnd - matchlen) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * Single-character match; pattern cursor always advances past what it
     * consumed. Returns 0 on match (same convention as the C code).
     */
    private static int matchchar(Cur pat, char in) {
        char c = (char) pat.take();
        switch (c) {
            case '?':
                return (in == '\0' || in == '/') ? 1 : 0;
            case '[':
                if (in == '\0' || in == '/') {
                    return 1;
                }
                return switch (rangematch(pat, in)) {
                    case RANGE_ERROR -> (in == '[') ? 0 : 1;
                    case RANGE_MATCH -> 0;
                    default -> 1;
                };
            case '\\':
                c = (char) pat.take();
                if (c == '\0') {
                    c = '\\';
                    pat.p--;
                }
                return c == in ? 0 : 1;
            default:
                return c == in ? 0 : 1;
        }
    }

    private static final int RANGE_MATCH = 1;
    private static final int RANGE_NOMATCH = 0;
    private static final int RANGE_ERROR = -1;

    /** Bracket expression matcher; pattern cursor is just past '['. */
    private static int rangematch(Cur pat, char test) {
        boolean negate = pat.ch() == '!' || pat.ch() == '^';
        if (negate) {
            pat.p++;
        }

        boolean ok = false;
        char c = (char) pat.take();
        do {
            if (c == '[') {
                switch (classmatch(pat, test)) {
                    case RANGE_MATCH -> {
                        ok = true;
                        c = (char) pat.take();
                        continue;
                    }
                    case RANGE_NOMATCH -> {
                        c = (char) pat.take();
                        continue;
                    }
                    default -> {
                        // invalid class: treat '[' literally
                    }
                }
            }
            if (c == '\\') {
                c = (char) pat.take();
            }
            if (c == '\0') {
                return RANGE_ERROR;
            }
            if (c == '/') {
                return RANGE_NOMATCH;
            }
            if (pat.ch() == '-') {
                char c2 = (char) pat.chAt(1);
                if (c2 != '\0' && c2 != ']') {
                    pat.p += 2;
                    if (c2 == '\\') {
                        c2 = (char) pat.take();
                    }
                    if (c2 == '\0') {
                        return RANGE_ERROR;
                    }
                    if (c <= test && test <= c2) {
                        ok = true;
                    }
                    c = (char) pat.take();
                    continue;
                }
            }
            if (c == test) {
                ok = true;
            }
            c = (char) pat.take();
        } while (c != ']');

        return ok == negate ? RANGE_NOMATCH : RANGE_MATCH;
    }

    /**
     * POSIX character class inside a bracket expression: "[:name:]".
     * Cursor is just past the '['; on success it is moved past ":]".
     */
    private static int classmatch(Cur pat, char test) {
        int mismatch = pat.p;
        if (pat.ch() != ':') {
            return RANGE_ERROR;
        }
        int colon = pat.s.indexOf(':', pat.p + 1);
        if (colon < 0 || colon + 1 >= pat.s.length()
                || pat.s.charAt(colon + 1) != ']') {
            pat.p = mismatch;
            return RANGE_ERROR;
        }
        String name = pat.s.substring(pat.p + 1, colon);
        if (!isClass(name)) {
            pat.p = mismatch;
            return RANGE_ERROR;
        }
        pat.p = colon + 2;
        return classHolds(name, test) ? RANGE_MATCH : RANGE_NOMATCH;
    }

    private static boolean isClass(String name) {
        return switch (name) {
            case "alnum", "alpha", "blank", "cntrl", "digit", "graph",
                 "lower", "print", "punct", "space", "upper", "xdigit" -> true;
            default -> false;
        };
    }

    private static boolean classHolds(String name, char test) {
        return switch (name) {
            case "alnum" -> Character.isLetterOrDigit(test);
            case "alpha" -> Character.isLetter(test);
            case "blank" -> test == ' ' || test == '\t';
            case "cntrl" -> Character.isISOControl(test);
            case "digit" -> Character.isDigit(test);
            case "graph" -> test > 0x20 && test < 0x7f;
            case "lower" -> Character.isLowerCase(test);
            case "print" -> test >= 0x20 && test < 0x7f;
            case "punct" -> isPunct(test);
            case "space" -> Character.isWhitespace(test);
            case "upper" -> Character.isUpperCase(test);
            case "xdigit" -> Character.digit(test, 16) >= 0;
            default -> false;
        };
    }

    private static boolean isPunct(char c) {
        return (c >= '!' && c <= '/') || (c >= ':' && c <= '@')
                || (c >= '[' && c <= '`') || (c >= '{' && c <= '~');
    }
}
