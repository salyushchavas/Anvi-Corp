package com.anvicorp.api.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Approach 1 — signup is reduced to the four legal-essentials. Every
 * remaining intake field (phone, school/degree/graduationYear/skillset,
 * work-authorization attestation, preferred name, DOB, resume) is collected
 * on the post-signup profile editor at /careers/intern/profile/complete,
 * and the apply endpoint guards on the same derived check
 * (ProfileCompletionService) so the contract holds.
 *
 * <p>{@code fullName} is the user's display name (User row). {@code
 * legalName} mirrors it on the Candidate row at signup so the compliance
 * flows that key off legalName don't see a null — the editor can split
 * them later if the intern types a different legal vs. preferred name.</p>
 *
 * <p>Older clients sending the dropped fields are unaffected: Jackson
 * silently ignores unknown JSON properties under Spring's default
 * configuration, so the previous 12-field POST still succeeds and the
 * extras are just dropped.</p>
 *
 * <p>Legal: {@code acceptedTos} must be true at submit — proof of consent
 * is stamped on the user (tos_accepted_at + tos_version) by AuthService.</p>
 */
public record RegisterRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(min = 8) String password,
        @NotBlank String fullName,
        /** Same string as fullName at signup; the profile editor can split
         *  legal vs. preferred later if needed. Null tolerated for back-
         *  compat with the prior multi-field form. */
        String legalName,
        Boolean acceptedTos,
        /** Bot-mitigation — Cloudflare Turnstile response token from the
         *  {@code cf-turnstile-response} form field. Verified by
         *  {@link com.anvicorp.api.auth.TurnstileVerifier}. Required when
         *  {@code app.captcha.turnstile.enabled=true} (prod); optional
         *  when disabled (dev / CI). Null / blank triggers rejection
         *  once enabled — see the verifier's fail-closed semantics. */
        @Size(max = 4000) String captchaToken,
        /** Honeypot field — a form input we render off-screen with
         *  {@code autocomplete=off} and {@code tabindex=-1}. Humans
         *  don't see it; naive form-filling bots fill it. Any non-null,
         *  non-blank value is a definitive bot signal and the register
         *  request is rejected without further processing (no CAPTCHA
         *  round-trip needed). Kept quiet — we return a normal 400 so
         *  a bot author can't grep the response for a distinctive
         *  fingerprint. Field name intentionally plausible
         *  ("companyWebsite") rather than obviously-decoy so class-
         *  based blocklists don't skip it. */
        @Size(max = 200) String companyWebsite
) {
    @AssertTrue(message = "You must accept the Privacy Policy and Terms of Service")
    public boolean isTosAccepted() {
        return Boolean.TRUE.equals(acceptedTos);
    }
}
