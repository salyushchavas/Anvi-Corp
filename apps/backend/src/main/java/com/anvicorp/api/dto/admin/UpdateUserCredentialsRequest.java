package com.anvicorp.api.dto.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Admin-issued credentials edit for an existing user. Both fields are
 * OPTIONAL — the request may carry just one, but the service enforces
 * that at least one is non-blank (400 otherwise).
 *
 * <p>Both fields, when present, are also written to the target user's
 * paired {@code MailAccount} (matched by the pre-edit email) so the
 * unified careers+mail credential story stays true. Domain-change on
 * the mailbox side is best-effort: if the new email's domain isn't a
 * provisioned {@code MailDomain}, the mailbox keeps its current
 * address and only the careers-side {@code User.email} moves.</p>
 */
@Getter
@Setter
public class UpdateUserCredentialsRequest {

    /** New login email. Null / blank = leave unchanged. */
    @Email(message = "email must be a valid address")
    private String email;

    /** New password (raw). Null / blank = leave unchanged. */
    @Size(min = 8, max = 128, message = "password must be 8-128 characters")
    private String password;
}
