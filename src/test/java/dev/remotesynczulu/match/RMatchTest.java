package dev.remotesynczulu.match;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wildcard matcher tests, mirroring openrsync's regress/rmatch cases. */
class RMatchTest {

    @Test
    void literals() {
        assertTrue(RMatch.rmatch("foo", "foo"));
        assertFalse(RMatch.rmatch("foo", "bar"));
        assertFalse(RMatch.rmatch("foo", "foobar"));
        assertFalse(RMatch.rmatch("foobar", "foo"));
    }

    @Test
    void singleStar() {
        assertTrue(RMatch.rmatch("*", "foo"));
        assertTrue(RMatch.rmatch("f*", "foo"));
        assertTrue(RMatch.rmatch("*.c", "main.c"));
        assertFalse(RMatch.rmatch("*.c", "main.h"));
        // Single star does not cross a segment boundary.
        assertFalse(RMatch.rmatch("*.c", "src/main.c"));
        assertTrue(RMatch.rmatch("f*/b?r", "foo/bar"));
    }

    @Test
    void doubleStar() {
        assertTrue(RMatch.rmatch("**", "anything/at/all"));
        assertTrue(RMatch.rmatch("**.c", "src/main.c"));
        assertTrue(RMatch.rmatch("src/**", "src/a/b/c"));
        assertTrue(RMatch.rmatch("**/main.c", "a/b/main.c"));
        assertTrue(RMatch.rmatch("a/**/z", "a/x/y/z"));
        assertFalse(RMatch.rmatch("a/**/z", "b/x/y/z"));
    }

    @Test
    void questionMark() {
        assertTrue(RMatch.rmatch("f?o", "foo"));
        assertFalse(RMatch.rmatch("f?o", "fo"));
        assertFalse(RMatch.rmatch("f?o", "f/o"));
    }

    @Test
    void brackets() {
        assertTrue(RMatch.rmatch("[a-z]", "m"));
        assertFalse(RMatch.rmatch("[a-z]", "M"));
        assertTrue(RMatch.rmatch("[!a-z]", "M"));
        assertTrue(RMatch.rmatch("[abc]", "b"));
        assertFalse(RMatch.rmatch("[abc]", "d"));
        assertTrue(RMatch.rmatch("file[0-9].txt", "file7.txt"));
        assertFalse(RMatch.rmatch("file[0-9].txt", "filex.txt"));
    }

    @Test
    void posixClasses() {
        assertTrue(RMatch.rmatch("[[:alpha:]]", "q"));
        assertFalse(RMatch.rmatch("[[:alpha:]]", "4"));
        assertTrue(RMatch.rmatch("[[:digit:]]", "4"));
        assertTrue(RMatch.rmatch("[[:upper:]]", "Q"));
        assertFalse(RMatch.rmatch("[[:lower:]]", "Q"));
    }

    @Test
    void escapes() {
        assertTrue(RMatch.rmatch("\\*", "*"));
        assertFalse(RMatch.rmatch("\\*", "star"));
        assertTrue(RMatch.rmatch("a\\?b", "a?b"));
        // An escaped slash participates like a plain slash.
        assertTrue(RMatch.rmatch("a\\/b", "a/b"));
    }

    @Test
    void leadingDir() {
        // Pattern fully consumed while a trailing '/' segment remains.
        assertTrue(RMatch.rmatch("foo", "foo/bar", true));
        assertFalse(RMatch.rmatch("foo", "foo/bar", false));
    }
}
