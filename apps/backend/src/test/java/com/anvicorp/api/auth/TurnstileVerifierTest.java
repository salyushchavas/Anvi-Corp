package com.anvicorp.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TurnstileVerifier — the two branches that don't require a live
 * Cloudflare round-trip. The success-path test is covered end-to-end
 * by a manual smoke against a test SITE key + secret in the sandbox
 * dashboard (documented in the PR); we intentionally don't hit the
 * live siteverify endpoint from CI so the test suite stays
 * network-independent.
 */
class TurnstileVerifierTest {

    private final TurnstileVerifier verifier = new TurnstileVerifier(new ObjectMapper());

    @Test
    void disabled_returns_true_regardless_of_token() {
        // Dev / CI path — TURNSTILE_ENABLED unset means verifier waives
        // the check so /auth/register works without a Cloudflare account.
        verifier.configure(false, "");
        assertTrue(verifier.verify(null, "127.0.0.1"));
        assertTrue(verifier.verify("", "127.0.0.1"));
        assertTrue(verifier.verify("anything", "127.0.0.1"));
    }

    @Test
    void enabled_with_blank_secret_fails_closed() {
        // Fail-closed on mis-config: if ops flipped the switch on but
        // forgot to set the secret, the endpoint MUST reject rather
        // than silently ship CAPTCHA-less.
        verifier.configure(true, "");
        assertFalse(verifier.verify("real-looking-token", "127.0.0.1"));
    }

    @Test
    void enabled_with_secret_but_blank_token_fails_closed() {
        // No token from the client = challenge not attempted = reject.
        verifier.configure(true, "0x0000000000000000000000000000000000");
        assertFalse(verifier.verify(null, "127.0.0.1"));
        assertFalse(verifier.verify("", "127.0.0.1"));
        assertFalse(verifier.verify("   ", "127.0.0.1"));
    }
}
