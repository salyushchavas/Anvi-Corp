package com.anvicorp.api.service;

import com.anvicorp.api.dto.users.ChangePasswordRequest;
import com.anvicorp.api.dto.users.NotificationPreferencesResponse;
import com.anvicorp.api.dto.users.UpdateNotificationPreferencesRequest;
import com.anvicorp.api.dto.users.UpdateProfileRequest;
import com.anvicorp.api.dto.users.UserProfileResponse;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Current-user profile read + update + password change. All ownership is
 * derived strictly from {@code @AuthenticationPrincipal} — never trust a
 * client-supplied user id.
 *
 * Phase 1.4 — reads/writes the intake profile + neutral work-authorization
 * self-attestation on the Candidate row. Staff users (no Candidate row) see
 * those fields as null and edits to them are silently dropped.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserProfileService {

    private final UserRepository userRepository;
    private final CandidateRepository candidateRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserSessionRepository userSessionRepository;
    private final AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(User caller) {
        if (caller == null || caller.getId() == null) {
            throw new BadRequestException("Authentication required");
        }
        User user = userRepository.findById(caller.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Optional<Candidate> candidate = candidateRepository.findByUserId(user.getId());
        return toResponse(user, candidate.orElse(null));
    }

    /**
     * Self-service GitHub username write. Validated on the DTO via @Pattern;
     * this method just trims and saves under the authenticated user's id.
     * Returns the new value so the frontend can refresh without a re-fetch.
     */
    @Transactional
    public java.util.Map<String, Object> setGithubUsername(User caller, String username) {
        if (caller == null || caller.getId() == null) {
            throw new BadRequestException("Authentication required");
        }
        User user = userRepository.findById(caller.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setGithubUsername(username == null ? null : username.trim());
        userRepository.save(user);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("userId", user.getId());
        out.put("githubUsername", user.getGithubUsername());
        return out;
    }

    /**
     * Self-service Zoom host email. Validated on the DTO via @Email;
     * trims + nulls blank. Used by ZoomService as the {@code userId} in
     * {@code POST /users/{userId}/meetings} so the user hosts their own
     * meetings. Falls back to the service account when blank.
     */
    @Transactional
    public java.util.Map<String, Object> setZoomEmail(User caller, String zoomEmail) {
        if (caller == null || caller.getId() == null) {
            throw new BadRequestException("Authentication required");
        }
        User user = userRepository.findById(caller.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setZoomEmail(emptyToNull(zoomEmail));
        userRepository.save(user);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("userId", user.getId());
        out.put("zoomEmail", user.getZoomEmail());
        return out;
    }

    @Transactional
    public UserProfileResponse updateProfile(User caller, UpdateProfileRequest req) {
        if (caller == null || caller.getId() == null) {
            throw new BadRequestException("Authentication required");
        }
        User user = userRepository.findById(caller.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        user.setFullName(req.getFullName().trim());
        user.setPhoneNumber(emptyToNull(req.getPhone()));
        userRepository.save(user);

        // Candidate-row fields. Staff without a Candidate row see null reads
        // and skipped writes — the role separation prevents 500s on edit.
        Optional<Candidate> candidateOpt = candidateRepository.findByUserId(user.getId());
        Candidate candidate = null;
        if (candidateOpt.isPresent()) {
            candidate = candidateOpt.get();
            candidate.setDateOfBirth(req.getDateOfBirth());
            // Phase 1.4 intake — explicit null wins over the existing value so
            // the candidate can blank a field. We trim non-null strings.
            candidate.setLegalName(emptyToNull(req.getLegalName()));
            candidate.setPreferredName(emptyToNull(req.getPreferredName()));
            candidate.setEducation(emptyToNull(req.getEducation()));
            candidate.setSchool(emptyToNull(req.getSchool()));
            candidate.setDegree(emptyToNull(req.getDegree()));
            candidate.setSkillset(emptyToNull(req.getSkillset()));
            // Phase 1.5 — structured education replaces the free-text trio.
            candidate.setDegreeLevel(req.getDegreeLevel());
            candidate.setSpecialization(emptyToNull(req.getSpecialization()));
            candidate.setGraduationYear(req.getGraduationYear());
            // Phase 1.4 self-attestation + Phase 1.5 visa-conditional dates.
            // The frontend already nulls out fields that don't apply to the
            // chosen track (per VisaDateRequirement); the server stores
            // whatever it receives — null is "not applicable / not disclosed".
            candidate.setAuthorizedToWork(req.getAuthorizedToWork());
            candidate.setSponsorshipNeeded(req.getSponsorshipNeeded());
            candidate.setExpectedTrack(req.getExpectedTrack());
            candidate.setValidityDate(req.getValidityDate());
            candidate.setValidityStartDate(req.getValidityStartDate());
            candidateRepository.save(candidate);
        }
        return toResponse(user, candidate);
    }

    @Transactional
    public void changePassword(User caller, ChangePasswordRequest req, UUID currentSessionId) {
        if (caller == null || caller.getId() == null) {
            throw new BadRequestException("Authentication required");
        }
        User user = userRepository.findById(caller.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPasswordHash())) {
            // 400 not 500. Mapped by GlobalExceptionHandler.
            throw new BadRequestException("Current password is incorrect");
        }
        if (req.getNewPassword().equals(req.getCurrentPassword())) {
            throw new BadRequestException("New password must be different from current");
        }
        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        // Clear the force-change gate atomically with the hash save. If the
        // user was created by an admin with a temp password, this is the
        // moment the ForcePasswordChangeFilter stops blocking their other
        // requests. Safe to set unconditionally — a normal change-password
        // call from an already-cleared user just rewrites false to false.
        user.setMustChangePassword(false);
        userRepository.save(user);

        // Security: revoke every other session. The caller stays signed in on
        // the device they just changed the password from (their refresh token
        // is still valid), but every other browser / phone is forced to
        // re-authenticate on next request. Standard practice. Pre-session
        // legacy callers (no session_id claim) have currentSessionId == null,
        // and revokeAllForUser treats that as "no exclusion" — i.e. ALL
        // sessions including the caller's are revoked. Acceptable: a legacy
        // user's JWT continues until natural expiry anyway, so the worst case
        // is a "sign in again next access-token cycle".
        try {
            int n = userSessionRepository.revokeAllForUser(
                    user.getId(), currentSessionId, Instant.now(), "password_changed");
            log.info("Password changed for {} — revoked {} other session(s)",
                    user.getEmail(), n);
        } catch (Exception e) {
            log.warn("Failed to revoke other sessions on password change (non-fatal): {}",
                    e.getMessage());
        }

        // Audit row — best-effort.
        try {
            AuditLog row = AuditLog.builder()
                    .entityType("User")
                    .entityId(user.getId())
                    .action("PASSWORD_CHANGED")
                    .userId(user.getId())
                    .build();
            auditLogRepository.save(row);
        } catch (Exception e) {
            log.warn("Failed to write PASSWORD_CHANGED audit row (non-fatal): {}",
                    e.getMessage());
        }
    }

    // ── Notification preferences ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public NotificationPreferencesResponse getNotificationPreferences(User caller) {
        if (caller == null || caller.getId() == null) {
            throw new BadRequestException("Authentication required");
        }
        User user = userRepository.findById(caller.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return NotificationPreferencesResponse.builder()
                // Legacy rows with null flags surface as opt-in (the default).
                .reminders(!Boolean.FALSE.equals(user.getPrefsReminders()))
                .engagementUpdates(!Boolean.FALSE.equals(user.getPrefsEngagementUpdates()))
                .build();
    }

    @Transactional
    public NotificationPreferencesResponse updateNotificationPreferences(
            User caller, UpdateNotificationPreferencesRequest req) {
        if (caller == null || caller.getId() == null) {
            throw new BadRequestException("Authentication required");
        }
        User user = userRepository.findById(caller.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (req != null) {
            if (req.reminders() != null) user.setPrefsReminders(req.reminders());
            if (req.engagementUpdates() != null) {
                user.setPrefsEngagementUpdates(req.engagementUpdates());
            }
        }
        userRepository.save(user);
        return NotificationPreferencesResponse.builder()
                .reminders(!Boolean.FALSE.equals(user.getPrefsReminders()))
                .engagementUpdates(!Boolean.FALSE.equals(user.getPrefsEngagementUpdates()))
                .build();
    }

    private UserProfileResponse toResponse(User user, Candidate candidate) {
        UserProfileResponse.UserProfileResponseBuilder b = UserProfileResponse.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .phone(user.getPhoneNumber())
                .zoomEmail(user.getZoomEmail())
                .roles(user.getRoles() == null
                        ? java.util.Set.of()
                        : user.getRoles().stream()
                                .map(Enum::name)
                                .collect(Collectors.toCollection(java.util.LinkedHashSet::new)));
        if (candidate != null) {
            b.dateOfBirth(candidate.getDateOfBirth())
                    .legalName(candidate.getLegalName())
                    .preferredName(candidate.getPreferredName())
                    .education(candidate.getEducation())
                    .school(candidate.getSchool())
                    .degree(candidate.getDegree())
                    .degreeLevel(candidate.getDegreeLevel())
                    .specialization(candidate.getSpecialization())
                    .graduationYear(candidate.getGraduationYear())
                    .skillset(candidate.getSkillset())
                    .authorizedToWork(candidate.getAuthorizedToWork())
                    .sponsorshipNeeded(candidate.getSponsorshipNeeded())
                    .expectedTrack(candidate.getExpectedTrack())
                    .validityDate(candidate.getValidityDate())
                    .validityStartDate(candidate.getValidityStartDate());
        }
        return b.build();
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
