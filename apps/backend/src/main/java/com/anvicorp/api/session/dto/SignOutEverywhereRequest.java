package com.anvicorp.api.session.dto;

/**
 * {@code POST /api/v1/me/sessions/sign-out-everywhere} body.
 *
 * <p>Default behaviour is to leave the caller's CURRENT session alive — the
 * user just signed in from this device, and revoking it would force them to
 * re-login right away. {@code includeCurrent=true} flips that for the
 * scorched-earth "kick me off everything" case.</p>
 */
public record SignOutEverywhereRequest(
        Boolean includeCurrent
) {
    public boolean includeCurrentOrDefault() {
        return Boolean.TRUE.equals(includeCurrent);
    }
}
