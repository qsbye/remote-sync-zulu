package dev.remotesynczulu.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Include/exclude semantics from rules.c: first match wins. */
class FilterRulesTest {

    @Test
    void basenamePattern() {
        FilterRules rs = new FilterRules();
        rs.addParsed("- *.o");
        assertEquals(FilterRules.EXCLUDE, rs.match("a/b/c.o", false));
        assertEquals(0, rs.match("a/b/c.c", false));
    }

    @Test
    void firstMatchWins() {
        FilterRules rs = new FilterRules();
        rs.addParsed("+ important.o");
        rs.addParsed("- *.o");
        assertEquals(FilterRules.INCLUDE, rs.match("important.o", false));
        assertEquals(FilterRules.EXCLUDE, rs.match("other.o", false));
    }

    @Test
    void anchoredPattern() {
        FilterRules rs = new FilterRules();
        rs.addParsed("- /tmp");
        assertEquals(FilterRules.EXCLUDE, rs.match("tmp", true));
        assertEquals(0, rs.match("sub/tmp", true));
    }

    @Test
    void dirOnlyPattern() {
        FilterRules rs = new FilterRules();
        rs.addParsed("- build/");
        assertEquals(FilterRules.EXCLUDE, rs.match("build", true));
        assertEquals(0, rs.match("build", false));
        assertEquals(0, rs.match("src/build", false));
        assertEquals(FilterRules.EXCLUDE, rs.match("src/build", true));
    }

    @Test
    void leadingDirPattern() {
        FilterRules rs = new FilterRules();
        rs.addParsed("- cache/***");
        assertEquals(FilterRules.EXCLUDE, rs.match("cache", true));
        assertEquals(FilterRules.EXCLUDE, rs.match("cache/a/b", false));
        assertEquals(0, rs.match("other/b", false));
    }

    @Test
    void multiSegmentPattern() {
        FilterRules rs = new FilterRules();
        rs.addParsed("- foo/bar");
        // Non-anchored multi-segment matches the tail of the path.
        assertEquals(FilterRules.EXCLUDE, rs.match("x/foo/bar", false));
        assertEquals(0, rs.match("x/foo/barbaz", false));
    }

    @Test
    void rootNeverFiltered() {
        FilterRules rs = new FilterRules();
        rs.addParsed("- *");
        assertEquals(0, rs.match(".", true));
    }

    @Test
    void keepHelper() {
        FilterRules rs = new FilterRules();
        rs.addParsed("- *.tmp");
        assertFalse(rs.keep("x.tmp", false));
        assertTrue(rs.keep("x.c", false));
    }
}
