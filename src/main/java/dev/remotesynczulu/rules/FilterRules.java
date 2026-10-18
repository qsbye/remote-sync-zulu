package dev.remotesynczulu.rules;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered rule set with first-match-wins semantics, translated from
 * openrsync's rules.c (rules_match() and rule_match_evaluate()).
 *
 * <p>{@link #match(String, boolean)} returns {@code INCLUDE} when a rule
 * keeps the path, {@code EXCLUDE} when a rule rejects it, and {@code 0}
 * when no rule fired (path is kept by default). Directory pruning uses the
 * same entry point, exactly as the C flist generator does.
 */
public final class FilterRules {

    public static final int EXCLUDE = FilterRule.EXCLUDE;
    public static final int INCLUDE = FilterRule.INCLUDE;

    private final List<FilterRule> rules = new ArrayList<>();

    public void add(FilterRule rule) {
        rules.add(rule);
    }

    public void addParsed(String line) {
        add(FilterRule.parse(line));
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public int size() {
        return rules.size();
    }

    /** The ordered rule chain (read-only). */
    public java.util.List<FilterRule> all() {
        return java.util.Collections.unmodifiableList(rules);
    }

    /**
     * Evaluate the chain against a root-relative path. First rule that
     * fires decides; no match means "keep" (0).
     */
    public int match(String path, boolean isdir) {
        if (isdir && (path.equals(".") || path.isEmpty())) {
            return 0; // rules_match(): never filter the transfer root
        }
        for (FilterRule r : rules) {
            if (r.matches(path, isdir)) {
                return r.result();
            }
        }
        return 0;
    }

    /** Convenience: should this path be kept (not excluded)? */
    public boolean keep(String path, boolean isdir) {
        return match(path, isdir) != EXCLUDE;
    }

    @Override
    public String toString() {
        return rules.toString();
    }
}
