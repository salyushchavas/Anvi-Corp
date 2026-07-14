package com.anvicorp.api.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Self-service body for {@code PUT /api/v1/users/me/zoom-email}. The
 * Zoom email is the host id passed to Zoom's
 * {@code POST /users/{userId}/meetings} call when this user schedules an
 * interview or weekly meeting. Must match the user's licensed Zoom seat
 * on the company Zoom account — when blank or wrong, the create call
 * either falls back to the service account ("me") or fails outright.
 *
 * <p>Empty string is accepted and treated as "clear the field" — the
 * service trims and converts blank to null.</p>
 */
public record SetZoomEmailRequest(
        @Email(message = "Must be a valid email address")
        @Size(max = 100, message = "Zoom email must be 100 characters or fewer")
        String zoomEmail
) {}
