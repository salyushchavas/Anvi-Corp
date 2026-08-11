package com.anvicorp.api.erm.idms;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the semantics of the field-level correction lock so a future
 * refactor can't drift the two-layer model (whole-doc {@code
 * internLocked} + per-field {@code unlockedFieldIds}). Covers the five
 * cases the design spec enumerates + the signature-persistence rule
 * (the survey called that one out explicitly).
 *
 * <p>Pure unit test — no Spring context, no H2 — exercises the
 * package-private narrowing predicate directly. Every other behaviour
 * downstream (server 409, client disable, ERM checklist round-trip)
 * derives from this predicate, so pinning it here is enough for the
 * correctness guarantee even without a full integration harness.</p>
 */
class DocumentInstanceFieldLockTest {

    /** (a) 1-field unlock — only that field editable, others locked. */
    @Test
    void one_field_unlock_narrows_intern_edits_to_just_that_id() {
        List<String> unlocked = List.of("field-42");

        assertTrue(DocumentInstanceService.isFieldEditableUnderLock(
                        "field-42", "INTERN", unlocked),
                "the single unlocked field must be editable by the intern");
        assertFalse(DocumentInstanceService.isFieldEditableUnderLock(
                        "field-99", "INTERN", unlocked),
                "every other intern field must be rejected — this is the guard "
                        + "that stops a client bug from smuggling a locked edit past the UI");
        assertFalse(DocumentInstanceService.isFieldEditableUnderLock(
                        "", "INTERN", unlocked),
                "an empty / spoofed field id doesn't slip through the narrowing check");
    }

    /**
     * (b) Empty/legacy selection — a null unlockedFieldIds set = the
     * old "all intern fields editable" behaviour, so existing rows
     * created before this feature keep working without a backfill.
     */
    @Test
    void null_unlocked_set_falls_back_to_legacy_all_editable() {
        assertTrue(DocumentInstanceService.isFieldEditableUnderLock(
                        "any-field", "INTERN", null),
                "null narrowing set = legacy behaviour: every intern field editable");
        assertTrue(DocumentInstanceService.isFieldEditableUnderLock(
                        "another-field", "INTERN", null),
                "null narrowing set covers arbitrary field ids");
    }

    /**
     * ERM writes are NEVER narrowed by this lock — the ERM's fills
     * happen before the correction cycle. The narrowing is intern-only.
     */
    @Test
    void erm_writes_bypass_the_narrowing() {
        List<String> unlocked = List.of("field-42");
        assertTrue(DocumentInstanceService.isFieldEditableUnderLock(
                        "field-99", "ERM", unlocked),
                "ERM can edit any of their own fields regardless of the intern's "
                        + "unlocked-set — the lock is a correction-cycle constraint for the intern");
        assertTrue(DocumentInstanceService.isFieldEditableUnderLock(
                        "field-99", "AUTO", unlocked),
                "AUTO-filled system fields also bypass the narrowing");
        assertTrue(DocumentInstanceService.isFieldEditableUnderLock(
                        "field-99", "SUPER_ADMIN", unlocked),
                "any non-INTERN caller (SUPER_ADMIN escape-hatch) bypasses the narrowing");
    }

    /**
     * (d) Signature persistence — signatures aren't cleared on RETURN.
     * They're only re-openable when the ERM explicitly includes the
     * signature's field id in the unlocked set. This test locks the
     * predicate for that case: a signature id in the set → editable;
     * NOT in the set → locked (the persisted signature stays).
     */
    @Test
    void signature_field_locked_by_default_reopenable_when_explicitly_listed() {
        // Not in the unlocked set — signature stays as-is on RETURN.
        // The write path enforces this; the signature bytes on disk
        // are simply not overwritten because a re-sign call would be
        // rejected under the narrowing.
        assertFalse(DocumentInstanceService.isFieldEditableUnderLock(
                        "sig-1", "INTERN", List.of("text-field")),
                "signature stays locked when ERM only unlocked a text field — "
                        + "regression cover for the 'signature auto-invalidated on return' bug");
        // Explicitly in the unlocked set — the ERM decided the
        // signature itself is what's wrong, so the intern can re-sign.
        assertTrue(DocumentInstanceService.isFieldEditableUnderLock(
                        "sig-1", "INTERN", List.of("sig-1")),
                "signature reopens ONLY when ERM explicitly ticks its checkbox");
    }

    /**
     * (e) Generic across doc types — the predicate carries no offer-
     * family coupling. The field id is opaque; template.key is not
     * part of the check. Same lock semantics for onboarding docs,
     * NDAs, custom editable templates, etc.
     */
    @Test
    void narrowing_is_generic_no_offer_family_coupling() {
        // Field id patterns used across the codebase — offer-letter
        // style, W-4 style, NDA style. Every one honours the same
        // narrowing rule.
        List<String> unlocked = List.of("i9-line-3-address");
        assertTrue(DocumentInstanceService.isFieldEditableUnderLock(
                        "i9-line-3-address", "INTERN", unlocked),
                "an I-9 field flows through the same lock as an offer field");
        assertFalse(DocumentInstanceService.isFieldEditableUnderLock(
                        "w4-allowances", "INTERN", unlocked),
                "a different-template field is rejected same way an offer field would be");
        assertFalse(DocumentInstanceService.isFieldEditableUnderLock(
                        "nda-effective-date", "INTERN", unlocked),
                "NDA field also rejected — the lock is fully template-agnostic");
    }

    /**
     * Extra invariant — empty list (not null) is treated as "nothing
     * unlocked". This case is prevented at write time (empty →
     * normalised to null) but the predicate stays safe either way.
     */
    @Test
    void empty_list_locks_every_field() {
        List<String> empty = List.of();
        assertFalse(DocumentInstanceService.isFieldEditableUnderLock(
                        "any-field", "INTERN", empty),
                "empty list = zero fields editable; the returnForCorrections "
                        + "normaliser converts empty → null before persisting, but the "
                        + "predicate stays safe if a corrupt row surfaces");
    }
}
