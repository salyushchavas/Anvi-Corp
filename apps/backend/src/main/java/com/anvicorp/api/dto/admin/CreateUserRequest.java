package com.anvicorp.api.dto.admin;

import com.anvicorp.api.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Admin-issued staff creation. Unified flow: ONE action creates BOTH a
 * careers {@code User} row AND provisions the matching {@code MailAccount}
 * so the SAME email + password authenticates against BOTH the careers
 * app and the internal /mail app.
 *
 * <p>Password is set by the admin (no activation-link flow). Name is
 * optional — defaults to the local-part of the email if blank.
 * {@link #deliveryEmail} is an OPTIONAL out-of-band address (personal
 * inbox) where the credentials handoff email is sent; if blank, the
 * admin conveys credentials via the copy-panel returned on success.</p>
 */
@Getter
@Setter
public class CreateUserRequest {

    /** Optional — defaults to the local-part of the email if blank. */
    private String name;

    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid address")
    private String email;

    /**
     * The password the admin sets for the new user. Applied to BOTH the
     * careers User row AND the provisioned MailAccount so both logins
     * accept the same credential.
     */
    @NotBlank(message = "password is required")
    @Size(min = 8, max = 128, message = "password must be 8-128 characters")
    private String password;

    /** Must be a STAFF role; SUPER_ADMIN + INTERN are rejected at the service layer. */
    @NotNull(message = "role is required")
    private UserRole role;

    /**
     * OPTIONAL out-of-band inbox to receive the credentials handoff email
     * (typically the user's personal address, since their brand-new
     * {@code @anvicorp.com} mailbox obviously can't receive its own
     * welcome mail). When blank / null, no email is sent — the admin
     * shares credentials via the on-screen copy panel.
     */
    @Email(message = "deliveryEmail must be a valid address")
    private String deliveryEmail;
}
