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
 * mailbox-provisioning outcome so the admin can share the credentials
 * with the new user.
 *
 * <p>The credentials themselves (email + password) are NOT echoed in
 * this response — the admin already knows them (they just typed the
 * password) and the frontend renders the copyable panel from the form
 * state. The response only communicates provisioning success and
 * delivery-email status.</p>
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

    /**
     * TRUE when the paired {@code MailAccount} was successfully created
     * in the same transaction. Because the dual-write is atomic, this
     * will always be TRUE on a 200 response — a mailbox failure rolls
     * the whole request back and surfaces as a non-2xx status.
     */
    private Boolean mailboxProvisioned;

    /**
     * The full mailbox address ({@code localPart@domain}) — normally the
     * same as {@link #email}. Surfaced explicitly so the frontend can
     * render the mailbox line without re-deriving it from the login
     * email.
     */
    private String mailboxAddress;

    /**
     * TRUE when a credentials handoff email was attempted (regardless
     * of downstream delivery). FALSE when the send threw. NULL when the
     * admin left {@code deliveryEmail} blank — the copy-panel is the
     * only handoff channel in that case.
     */
    private Boolean credentialsEmailSent;
}
