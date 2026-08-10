package com.anvicorp.api.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnostic for BUG 1 — "IDMS field span loses its font on save."
 *
 * <p>The {@code IdmsSaveSchemaTrace} log emits INCOMING / NORMALIZED /
 * STORED at every save. To fix the bug without needing a live browser
 * trace we exercise the same sanitizer with the exact shape of HTML the
 * studio's {@code wrapSelection} + {@code applyInheritedTypography} pipe
 * produces: a {@code <span data-field-id="…" class="doc-field
 * doc-field--erm" data-df-inherit="1" style="font-family: Calibri; …">}
 * inside its docx-preview {@code <span class="docx_r_1">} parent, along
 * with the docx-preview {@code <style>} block that class rules resolve
 * against.</p>
 *
 * <p>These tests assert what MUST survive the sanitize round-trip:</p>
 * <ol>
 *   <li>The field span keeps its {@code style} attribute — {@code
 *       font-family: Calibri} in particular. Regression protection for
 *       the "font strip" pass 2 fix (240c0cb) which broadened style
 *       preservation to {@code :all} tags.</li>
 *   <li>The docx-preview {@code <style>} block keeps its font-family
 *       class rules — regression protection for the a1fa486 fix.</li>
 *   <li>The idempotency marker {@code data-df-inherit="1"} survives so
 *       {@code applyInheritedTypography} doesn't re-run at render time
 *       and possibly re-pick a different source (which is what causes
 *       the "font changes on save" residual bug — the marker is stripped
 *       today because the sanitizer safelist doesn't include it on span,
 *       so paint-time re-inheritance CAN overwrite the wrap-time value).</li>
 * </ol>
 */
class CanonicalHtmlSanitizerFieldSpanFontIT {

    /** The exact HTML shape produced by studio wrapSelection +
     *  applyInheritedTypography inside a docx-preview run. */
    private static final String INCOMING = ""
            + "<style>.docx_r_1 { font-family: Calibri; font-size: 11pt; }</style>"
            + "<article class=\"docx\">"
            + "<p><span class=\"docx_r_1\">Position: "
            + "<span data-field-id=\"f-1\" class=\"doc-field doc-field--erm\" "
            +   "data-df-inherit=\"1\" "
            +   "style=\"font-family: Calibri; font-size: 11pt; color: rgb(17, 17, 17);\">"
            +   "___"
            + "</span>"
            + "</span></p>"
            + "</article>";

    @Test
    void field_span_inline_style_font_family_survives_sanitize() {
        String stored = CanonicalHtmlSanitizer.sanitize(INCOMING);
        assertTrue(stored.contains("data-field-id=\"f-1\""),
                "field-id lost: " + stored);
        assertTrue(stored.contains("font-family: Calibri")
                        || stored.contains("font-family:Calibri"),
                "inline font-family stripped from field span (BUG 1 regression): " + stored);
    }

    @Test
    void class_based_style_block_font_rules_survive_sanitize() {
        String stored = CanonicalHtmlSanitizer.sanitize(INCOMING);
        assertTrue(stored.contains(".docx_r_1"),
                "docx-preview class rule stripped: " + stored);
        assertTrue(stored.contains("font-family: Calibri")
                        || stored.contains("font-family:Calibri"),
                "docx-preview <style> font declarations stripped: " + stored);
    }

    /** THE ACTIVE BUG — the studio marks the field span
     *  {@code data-df-inherit="1"} so the shared paint pass in
     *  InstanceRenderer skips re-computing at render time (which could
     *  pick a different typography source than the wrap did, especially
     *  after the intervening sanitizer round-trip). Today's Safelist
     *  doesn't whitelist {@code data-df-inherit} on {@code span}, so the
     *  marker is stripped; paint DOES re-run and re-stamps from whatever
     *  source it picks now — often the browser default (Times New Roman)
     *  when the field wraps text that has no explicit inline font on any
     *  ancestor. That is the residual "font changes on save" for fields. */
    @Test
    void df_inherit_marker_survives_sanitize() {
        String stored = CanonicalHtmlSanitizer.sanitize(INCOMING);
        assertTrue(stored.contains("data-df-inherit=\"1\"")
                        || stored.contains("data-df-inherit=\"1\""),
                "data-df-inherit marker stripped — paint pass will re-run "
                        + "at render time and can overwrite the wrap-time font "
                        + "with a different pick. Sanitizer safelist needs "
                        + "data-df-inherit on span (BUG 1 root cause): " + stored);
    }

    /** Baseline — non-field docx-preview runs' inline font styles have
     *  been preserved since 240c0cb. If this ever fails, the
     *  {@code :all} style-attribute broadening regressed. */
    @Test
    void non_field_run_span_inline_font_survives_sanitize() {
        String withStyledRun = "<p><span class=\"docx_r_1\" "
                + "style=\"font-family: Calibri; font-size: 11pt\">Body text</span></p>";
        String stored = CanonicalHtmlSanitizer.sanitize(withStyledRun);
        assertTrue(stored.contains("font-family"),
                "non-field span inline font-family stripped (pass-2 regression): "
                        + stored);
        assertFalse(stored.isBlank(),
                "sanitize returned empty on plain styled run: " + stored);
    }
}
