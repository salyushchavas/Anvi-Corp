package com.anvicorp.api.dto.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.anvicorp.api.enums.UserRole;
import lombok.*;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Response to {@code POST /api/v1/admin/users}. Mirrors
 * {@link AdminUserResponse} for the list-of-users view AND surfaces the
 * one-time activation URL + expiry so the admin can copy it as a
 * fallback (the same link is also emailed to the user).
 *
 * <p>SECURITY — {@link #activationUrl} contains the RAW token; this is
 * the ONLY time it leaves the server. The DB stores only its SHA-256
 * hash, and the email body is the only other delivery channel. Don't
 * log it, don't surface it on subsequent reads, don't put it in audit
 * snapshots.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateStaffUserResponse {
    private UUID id;
    private String name;
    private String email;
    private Set<UserRole> roles;
    private Boolean active;
    private Instant createdAt;
    /** Always null for admin-created staff (no applicant funnel). Kept for shape parity. */
    private String applicantId;

    /** Full activation URL — frontend renders this in the admin's copy-fallback. */
    private String activationUrl;
    /** Wall-clock expiry of the activation token (24h from issue). */
    private Instant activationExpiresAt;
    /**
     * TRUE when the invite email was attempted (regardless of delivery
     * success). FALSE when the email send threw — the admin can still
     * copy the URL above and share it out-of-band.
     */
    private Boolean inviteEmailSent;
}
