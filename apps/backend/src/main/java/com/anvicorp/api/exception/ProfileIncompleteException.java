package com.anvicorp.api.exception;

import java.util.List;

/**
 * Thrown by {@link com.anvicorp.api.service.ApplicationService#apply}
 * when the calling intern hasn't filled the 6 required-to-apply profile
 * fields (phone, school, degree, graduation year, skillset, resume).
 *
 * <p>Mapped by {@link GlobalExceptionHandler} to 409 with
 * {@code code="PROFILE_INCOMPLETE"} and {@code details.missing} carrying
 * the stable keys of the unfilled fields, so the frontend can redirect
 * to the profile editor with the right step pre-focused.</p>
 */
public class ProfileIncompleteException extends RuntimeException {

    private final List<String> missing;

    public ProfileIncompleteException(String message, List<String> missing) {
        super(message);
        this.missing = missing == null ? List.of() : List.copyOf(missing);
    }

    public List<String> getMissing() {
        return missing;
    }
}
