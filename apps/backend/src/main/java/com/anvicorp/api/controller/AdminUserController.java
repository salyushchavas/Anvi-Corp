package com.anvicorp.api.controller;

import com.anvicorp.api.dto.admin.AdminUserResponse;
import com.anvicorp.api.dto.admin.CreateStaffUserResponse;
import com.anvicorp.api.dto.admin.CreateUserRequest;
import com.anvicorp.api.dto.admin.SuspectedBotPurgeResponse;
import com.anvicorp.api.dto.admin.UpdateUserCredentialsRequest;
import com.anvicorp.api.dto.admin.UpdateUserRoleRequest;
import com.anvicorp.api.dto.admin.UpdateUserStatusRequest;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<AdminUserResponse> list(
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) String search,
            @RequestParam(name = "includeUnverified", defaultValue = "false")
            boolean includeUnverified) {
        return adminUserService.list(role, search, includeUnverified);
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<CreateStaffUserResponse> create(@Valid @RequestBody CreateUserRequest req,
                                                          @AuthenticationPrincipal User caller) {
        CreateStaffUserResponse created = adminUserService.create(req, caller);
        return ResponseEntity.created(URI.create("/api/v1/admin/users/" + created.getId()))
                .body(created);
    }

    @PutMapping("/{id}/role")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public AdminUserResponse updateRole(@PathVariable UUID id,
                                        @Valid @RequestBody UpdateUserRoleRequest req,
                                        @AuthenticationPrincipal User caller) {
        return adminUserService.updateRole(id, req, caller);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public AdminUserResponse updateStatus(@PathVariable UUID id,
                                          @Valid @RequestBody UpdateUserStatusRequest req,
                                          @AuthenticationPrincipal User caller) {
        return adminUserService.updateStatus(id, req, caller);
    }

    /**
     * Update the target user's email and/or password. Both body fields
     * are optional; at least one must be non-blank. Dual-writes to the
     * paired MailAccount when one exists so careers login + mail login
     * stay unified. 409 on email or mailbox-address collision.
     */
    @PutMapping("/{id}/credentials")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public AdminUserResponse updateCredentials(@PathVariable UUID id,
                                                @Valid @RequestBody UpdateUserCredentialsRequest req,
                                                @AuthenticationPrincipal User caller) {
        return adminUserService.updateCredentials(id, req, caller);
    }

    /**
     * Purge a user. Two-tier by history:
     *
     * <ul>
     *   <li>Never actually started an internship → hard-delete of every
     *       row scoped to them (minus the {@code *_event_log} /
     *       {@code *_review_logs} retention spine).</li>
     *   <li>Ever actually started → soft delete. Retention-protected
     *       rows are tombstoned ({@code deleted_at = now()}) so past-
     *       month dashboards can still render the history; the account
     *       is deactivated so it can no longer sign in.</li>
     * </ul>
     *
     * <p>Response is a {@code Map<String, Object>} with an
     * {@code _outcome} key ({@code "hard"} or {@code "soft"}), a
     * user-facing {@code _message} explaining what was done, and the
     * per-table row-count breakdown so the operator can see the blast
     * radius (or the retention footprint) of the action.</p>
     *
     * <p>Refuses on self-delete and on the last active SUPER_ADMIN.</p>
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> delete(@PathVariable UUID id,
                                       @AuthenticationPrincipal User caller) {
        return adminUserService.deleteUser(id, caller);
    }

    /**
     * Purge an unverified account. Strict guard — verified accounts return
     * 409; only the standard delete handles them. Runs the same FK sweep
     * as the standard delete and audits as {@code USER_UNVERIFIED_PURGE}.
     */
    @DeleteMapping("/{id}/unverified")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> deleteUnverified(@PathVariable UUID id,
                                                 @AuthenticationPrincipal User caller) {
        return adminUserService.deleteUnverifiedUser(id, caller);
    }

    /**
     * One-off bulk purge of suspected bot INTERN accounts — the
     * gibberish-name, REGISTERED-status, email-verified-via-alias,
     * never-applied pool the current bot wave produced.
     *
     * <p>Two-phase to keep the operator in the loop:</p>
     * <ul>
     *   <li>Default ({@code confirm=false}) — DRY-RUN. Returns the
     *       matched accounts without deleting; the operator reviews
     *       the list before pulling the trigger.</li>
     *   <li>{@code confirm=true} — actually purges each matched account
     *       via {@link AdminUserService#deleteUser}, which routes
     *       every REGISTERED-status intern to the audited
     *       {@code hardPurge} FK sweep + {@code USER_DELETED} audit
     *       line + S3 vault cleanup. NO manual DELETE cascade lives
     *       in this endpoint's code path; every row's deletion goes
     *       through the same public method the per-user DELETE button
     *       in the admin UI calls.</li>
     * </ul>
     *
     * <p>SUPER_ADMIN only. The bot signature ({@link
     * AdminUserService#purgeSuspectedBots}) never matches staff
     * (single-INTERN-role + gibberish-name + never-applied), but the
     * role gate is the primary guarantee.</p>
     *
     * <p>POST rather than GET-for-dry-run + POST-for-confirm to keep
     * the two phases on one endpoint — cleaner in the frontend
     * (single fetch call parameterised) and honest at the HTTP-verb
     * layer (both phases READ from the DB; the confirm phase also
     * WRITES).</p>
     */
    @PostMapping("/purge-suspected-bots")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public SuspectedBotPurgeResponse purgeSuspectedBots(
            @RequestParam(name = "confirm", defaultValue = "false") boolean confirm,
            @AuthenticationPrincipal User caller) {
        return adminUserService.purgeSuspectedBots(confirm, caller);
    }
}
