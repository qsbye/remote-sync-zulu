package dev.remotesynczulu.rules;

import dev.remotesynczulu.match.RMatch;

/**
 * A single include/exclude rule, translated from openrsync's rules.c
 * (struct rule, parse_pattern() and rule_match_action_xfer()).
 *
 * <p>Supported surface syntax (same as rsync/openrsync):
 * <ul>
 *   <li>{@code - pattern} / {@code --exclude=pattern}: exclude</li>
 *   <li>{@code + pattern} / {@code --include=pattern}: include</li>
 *   <li>leading {@code !} negates the rule</li>
 *   <li>leading {@code /} anchors the pattern to the transfer root</li>
 *   <li>trailing {@code /} matches directories only</li>
 *   <li>trailing {@code /***} matches the directory and everything
 *       beneath it ("leadingdir")</li>
 *   <li>patterns without a slash and not anchored match basenames</li>
 *   <li>wildcards per {@link RMatch}: {@code * ? [..] **} and escapes</li>
 * </ul>
 *
 * <p>Merge-file rules, perishable rules and send/recv modifiers from the C
 * implementation are intentionally not supported (documented difference).
 */
public final class FilterRule {

    /** Exclude-type rule: a match rejects the path. */
    public static final int EXCLUDE = -1;
    /** Include-type rule: a match keeps the path. */
    public static final int INCLUDE = 1;

    final int type;        // EXCLUDE or INCLUDE
    final boolean negate;
    final boolean anchored;
    final boolean onlydir;
    final boolean leadingdir;
    final boolean fileonly;
    final boolean nowild;
    final int numseg;      // -1 when the pattern contains "**"
    final String pattern;

    private FilterRule(int type, boolean negate, String pattern) {
        this.type = type;
        this.negate = negate;

        boolean anchored = false;
        boolean onlydir = false;
        boolean leadingdir = false;

        // parse_pattern(): strip and mark a leading '/'.
        if (pattern.startsWith("/")) {
            pattern = pattern.substring(1);
            anchored = true;
        }
        // Trailing '/' marks directory-only.
        if (pattern.length() > 1 && pattern.endsWith("/")) {
            onlydir = true;
            pattern = pattern.substring(0, pattern.length() - 1);
        }
        // Trailing "/***" marks "this dir and all below".
        if (!onlydir && pattern.length() > 4 && pattern.endsWith("/***")) {
            leadingdir = true;
            pattern = pattern.substring(0, pattern.length() - 4);
        }

        int nseg = 1;
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) == '/') {
                nseg++;
            }
        }

        boolean fileonly = nseg == 1 && !anchored && !leadingdir;
        boolean nowild = !hasWild(pattern);
        if (!nowild && pattern.contains("**")) {
            nseg = -1;
        }

        this.anchored = anchored;
        this.onlydir = onlydir;
        this.leadingdir = leadingdir;
        this.fileonly = fileonly;
        this.nowild = nowild;
        this.numseg = nseg;
        this.pattern = pattern;
    }

    private static boolean hasWild(String p) {
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '*' || c == '?' || c == '[') {
                return true;
            }
            if (c == '\\') {
                i++;
            }
        }
        return false;
    }

    /**
     * Parse one rule line: {@code "- pat"}, {@code "+ pat"},
     * {@code "exclude pat"}, {@code "include pat"}, or a bare pattern
     * (treated as an exclude, matching --exclude semantics).
     */
    public static FilterRule parse(String line) {
        int type = EXCLUDE;
        String s = line.trim();
        if (s.startsWith("- ") || s.startsWith("- ")) {
            s = s.substring(2);
        } else if (s.startsWith("+ ") || s.startsWith("+ ")) {
            type = INCLUDE;
            s = s.substring(2);
        } else if (s.startsWith("exclude ")) {
            s = s.substring(8);
        } else if (s.startsWith("include ")) {
            type = INCLUDE;
            s = s.substring(8);
        }
        s = s.trim();
        boolean negate = false;
        if (s.startsWith("!")) {
            negate = true;
            s = s.substring(1).trim();
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("empty filter pattern");
        }
        return new FilterRule(type, negate, s);
    }

    /**
     * rule_match_action_xfer(): does this rule match {@code path}?
     *
     * @param path  transfer-root-relative path, '/'-separated, no leading
     *              slash
     * @param isdir whether the path names a directory
     * @return true when the rule fires (before include/exclude polarity)
     */
    boolean matches(String path, boolean isdir) {
        if (onlydir && !isdir) {
            // C: negated dir-only rules "match" non-dirs by type mismatch.
            return negate;
        }

        String basename = path;
        int slash = path.lastIndexOf('/');
        if (slash >= 0) {
            basename = path.substring(slash + 1);
        }

        if (nowild) {
            if (fileonly) {
                return basename.equals(pattern) != negate;
            }
            if (leadingdir) {
                int idx = path.indexOf(pattern);
                if (anchored && idx != 0) {
                    return false;
                }
                while (idx >= 0) {
                    boolean startOk = idx == 0 || path.charAt(idx - 1) == '/';
                    int end = idx + pattern.length();
                    boolean endOk = end == path.length()
                            || path.charAt(end) == '/';
                    if (startOk && endOk) {
                        return true != negate;
                    }
                    idx = path.indexOf(pattern, idx + 1);
                }
                return false;
            }
            if (anchored) {
                return path.equals(pattern) != negate;
            }
            if (path.length() >= pattern.length()
                    && path.endsWith(pattern)) {
                int start = path.length() - pattern.length();
                if (start == 0 || path.charAt(start - 1) == '/') {
                    return true != negate;
                }
            }
            return false;
        }

        if (leadingdir && !anchored) {
            // Start numsegs-1 elements from the end and try every
            // remaining dir boundary (rules.c lines 1323-1356).
            int nseg = numseg - 1;
            int p = path.length();
            while (p > 0) {
                p--;
                if (path.charAt(p) == '/' || p == 0) {
                    int search = p;
                    if (nseg > 0) {
                        nseg--;
                        continue;
                    }
                    if (path.charAt(search) == '/') {
                        search++;
                    }
                    if (RMatch.rmatch(pattern, path.substring(search), true)) {
                        return true != negate;
                    }
                }
            }
            return false;
        }

        String target;
        if (fileonly) {
            target = basename;
        } else if (anchored || numseg == -1) {
            target = path;
        } else {
            // Match against the last numseg segments.
            int nseg = 1;
            for (int i = 0; i < path.length(); i++) {
                if (path.charAt(i) == '/') {
                    nseg++;
                }
            }
            if (nseg < numseg) {
                return false;
            }
            int skip = nseg - numseg;
            int start = 0;
            for (int i = 0; i < path.length() && skip > 0; i++) {
                if (path.charAt(i) == '/') {
                    skip--;
                    start = i + 1;
                }
            }
            target = path.substring(start);
        }
        return RMatch.rmatch(pattern, target, leadingdir) != negate;
    }

    /** Rule polarity when it matches: INCLUDE (+1) or EXCLUDE (-1). */
    int result() {
        return type;
    }

    @Override
    public String toString() {
        return (type == INCLUDE ? "+ " : "- ")
                + (negate ? "!" : "")
                + (anchored ? "/" : "")
                + pattern
                + (onlydir ? "/" : "")
                + (leadingdir ? "/***" : "");
    }
}
