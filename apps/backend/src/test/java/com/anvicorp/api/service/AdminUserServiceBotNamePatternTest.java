package com.anvicorp.api.service;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression pin for the gibberish-name signature used by
 * {@code AdminUserService.purgeSuspectedBots}. The SQL prefilter is
 * DB-portable (works on Postgres + H2 in tests); the name-shape check
 * is a Java-side regex applied to every prefilter hit. If the regex
 * ever loosens, a real user could match; if it tightens beyond the
 * actual bot samples ({@code Vtpkdyff}, {@code Wntzjw}), the endpoint
 * silently under-purges. These tests pin BOTH edges.
 */
class AdminUserServiceBotNamePatternTest {

    private static final Pattern P = AdminUserService.BOT_NAME_SHAPE;

    @Test
    void real_bot_samples_match() {
        assertTrue(P.matcher("Vtpkdyff").matches(),
                "sample 1 from the bot-account audit should match");
        assertTrue(P.matcher("Wntzjw").matches(),
                "sample 2 from the bot-account audit should match");
    }

    @Test
    void mixed_case_bot_shape_matches() {
        // Bots often lowercase or mixedcase; the pattern is [A-Za-z].
        assertTrue(P.matcher("qwerty").matches());
        assertTrue(P.matcher("aBCdefGH").matches());
    }

    @Test
    void real_name_with_space_does_not_match() {
        // The signature specifically excludes anything with a space.
        assertFalse(P.matcher("Jane Doe").matches(),
                "real names contain a space and MUST NOT match");
        assertFalse(P.matcher("John Q Smith").matches());
    }

    @Test
    void real_short_or_long_names_do_not_match() {
        // Under 5 chars or over 10 chars — real users use their real
        // names which typically fall outside this narrow bot-length
        // band. A single-name intern with "Li" (2 chars) or
        // "Christopher" (11 chars) is safe.
        assertFalse(P.matcher("Li").matches(),
                "2-char name shorter than bot band should not match");
        assertFalse(P.matcher("Ann").matches(),
                "3-char name shorter than bot band should not match");
        assertFalse(P.matcher("Christopher").matches(),
                "11-char name longer than bot band should not match");
        assertFalse(P.matcher("Constantine").matches(),
                "11-char name longer than bot band should not match");
    }

    @Test
    void names_with_punctuation_do_not_match() {
        // Real names may include apostrophes, hyphens, periods —
        // signature must exclude all of these.
        assertFalse(P.matcher("O'Neil").matches());
        assertFalse(P.matcher("Smith-Jones").matches());
        assertFalse(P.matcher("A.J.").matches());
    }

    @Test
    void names_with_digits_do_not_match() {
        // The bot pool doesn't include digit-carrying names; the
        // signature explicitly excludes them so a real user like
        // "User123" is never at risk (unlikely name, but tight
        // enough to still exclude it).
        assertFalse(P.matcher("User123").matches());
        assertFalse(P.matcher("Bot1").matches());
    }

    @Test
    void empty_and_null_safe() {
        assertFalse(P.matcher("").matches());
        // caller-side null check happens BEFORE the regex; the
        // service's callsite skips nulls without invoking the pattern.
        // (regex on null throws, but the pattern object itself
        // handles empty string safely.)
    }
}
