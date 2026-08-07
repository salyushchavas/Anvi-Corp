package com.anvicorp.api.service;

import com.anvicorp.api.dto.users.ChangePasswordRequest;
import com.anvicorp.api.dto.users.NotificationPreferencesResponse;
import com.anvicorp.api.dto.users.UpdateNotificationPreferencesRequest;
import com.anvicorp.api.dto.users.UpdateProfileRequest;
import com.anvicorp.api.dto.users.UserProfileResponse;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WorkAuthorizationRecord;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.profile.ProfileNotificationService;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.repository.UserSessionRepository;
import com.anvicorp.api.repository.WorkAuthorizationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
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
    private final WorkAuthorizationRecordRepository workAuthRepository;
    private final ProfileNotificationService profileNotificationService;

    // ── B2 profile expansion — server-side validation constants ─────────
    /** US ZIP: 5-digit ({@code 12345}) or 5+4 ({@code 12345-6789}). Mirrors the client regex. */
    private static final Pattern US_ZIP = Pattern.compile("^\\d{5}(-\\d{4})?$");
    /** 50 states + DC — canonical whitelist for {@code address_state}. */
    private static final Set<String> US_STATES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL",
            "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME",
            "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH",
            "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI",
            "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY");

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

        // B2 — snapshot BEFORE mutation for the post-submission edit-diff
        // notification. Only fields listed in the operator spec (name /
        // phone / address / skillset / work-auth attestation) are tracked
        // here; education edits are diffed inside EducationService instead.
        Candidate before = candidateRepository.findByUserId(user.getId()).orElse(null);
        MonitoredSnapshot beforeSnap = MonitoredSnapshot.of(user, before);

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

            // B2 — US address writes with server-side validation mirroring the
            // client. Only validate fields that are present; a fully-null
            // address means "candidate hasn't filled it yet" and we still
            // save that (the stricter submission bar catches missing
            // address separately).
            String street = emptyToNull(req.getAddressStreet());
            String apt    = emptyToNull(req.getAddressApt());
            String city   = emptyToNull(req.getAddressCity());
            String state  = emptyToNull(req.getAddressState());
            String zip    = emptyToNull(req.getAddressZip());
            String country = emptyToNull(req.getAddressCountry());
            if (state != null) {
                state = state.toUpperCase(java.util.Locale.ROOT);
                if (!US_STATES.contains(state)) {
                    throw new BadRequestException(
                            "State must be a 2-letter US state code (or DC)");
                }
            }
            if (zip != null && !US_ZIP.matcher(zip).matches()) {
                throw new BadRequestException(
                        "ZIP must be 5 digits or 5+4 (e.g. 12345 or 12345-6789)");
            }
            candidate.setAddressStreet(street);
            candidate.setAddressApt(apt);
            candidate.setAddressCity(city);
            candidate.setAddressState(state);
            candidate.setAddressZip(zip);
            // Country hidden from the client; default to US when the profile
            // is being written.
            candidate.setAddressCountry(country != null
                    ? country.toUpperCase(java.util.Locale.ROOT) : "US");
            candidateRepository.save(candidate);

            // B2 — surface authorizedFrom / authorizedUntil into the
            // WorkAuthorizationRecord via an inline upsert. WAR is the
            // canonical store for these two dates (post-hire compliance),
            // and the profile is the self-service surface where the intern
            // can disclose them pre-hire. If no WAR row exists yet we
            // create a minimal one seeded from the candidate's expectedTrack
            // (mapped to WAR.workAuthType — the two enums share the same
            // string values via .name()).
            if (req.getAuthorizedFrom() != null || req.getAuthorizedUntil() != null) {
                upsertWorkAuthDates(user, candidate,
                        req.getAuthorizedFrom(), req.getAuthorizedUntil());
            }

            // B2 — submission-ack trigger (one-shot) + edit-notify trigger
            // (throttled). Both are inert no-ops until their respective
            // guards are cleared. Ordering: submission-ack first — if this
            // save is what FLIPS the profile to submitted, no edit-notify
            // needs to fire because there's nothing "post-submission" to
            // diff against yet.
            profileNotificationService.maybeFireSubmissionAck(user, candidate);

            MonitoredSnapshot afterSnap = MonitoredSnapshot.of(user, candidate);
            String changedArea = beforeSnap.diff(afterSnap);
            if (changedArea != null) {
                profileNotificationService.maybeNotifyOnEdit(user, candidate, changedArea);
            }
        }
        return toResponse(user, candidate);
    }

    /**
     * B2 — minimal WAR upsert reachable from the intern profile. If a WAR
     * row exists we patch only the two date columns (never touch encrypted
     * document fields or ERM-owned notes). If none exists we create one
     * with {@code workAuthType} derived from {@code Candidate.expectedTrack}
     * (or {@code "OTHER"} when the candidate hasn't picked a track yet) and
     * {@code lastUpdatedById} stamped to the intern themself.
     */
    private void upsertWorkAuthDates(User user, Candidate candidate,
                                      LocalDate authorizedFrom,
                                      LocalDate authorizedUntil) {
        WorkAuthorizationRecord war = workAuthRepository.findByUserId(user.getId())
                .orElse(null);
        if (war == null) {
            String type = candidate.getExpectedTrack() != null
                    ? candidate.getExpectedTrack().name()
                    : "OTHER";
            war = WorkAuthorizationRecord.builder()
                    .userId(user.getId())
                    .workAuthType(type)
                    .authorizedFrom(authorizedFrom)
                    .authorizedUntil(authorizedUntil)
                    .i983Required(Boolean.FALSE)
                    .lastUpdatedById(user.getId())
                    .build();
        } else {
            war.setAuthorizedFrom(authorizedFrom);
            war.setAuthorizedUntil(authorizedUntil);
            war.setLastUpdatedById(user.getId());
        }
        try {
            workAuthRepository.save(war);
        } catch (Exception e) {
            log.warn("[UserProfile] WAR upsert on profile write failed "
                    + "(non-fatal, dates not persisted): {}", e.getMessage());
        }
    }

    /**
     * B2 edit-diff mechanism.
     *
     * <p>Snapshot of every monitored field taken BEFORE the DTO writes are
     * applied and again AFTER. Any {@link Objects#equals}-non-equal pair
     * flips the diff, and {@link #diff} returns a human-readable label of
     * the changed area (used in the notification body). Skillset and address
     * are grouped as single areas rather than per-field so the notification
     * copy stays terse.</p>
     */
    private record MonitoredSnapshot(
            String fullName, String phone,
            String skillset,
            String addressStreet, String addressApt, String addressCity,
            String addressState, String addressZip, String addressCountry,
            Boolean authorizedToWork, Boolean sponsorshipNeeded,
            String expectedTrack, LocalDate validityDate, LocalDate validityStartDate) {

        static MonitoredSnapshot of(User u, Candidate c) {
            return new MonitoredSnapshot(
                    u == null ? null : u.getFullName(),
                    u == null ? null : u.getPhoneNumber(),
                    c == null ? null : c.getSkillset(),
                    c == null ? null : c.getAddressStreet(),
                    c == null ? null : c.getAddressApt(),
                    c == null ? null : c.getAddressCity(),
                    c == null ? null : c.getAddressState(),
                    c == null ? null : c.getAddressZip(),
                    c == null ? null : c.getAddressCountry(),
                    c == null ? null : c.getAuthorizedToWork(),
                    c == null ? null : c.getSponsorshipNeeded(),
                    c == null || c.getExpectedTrack() == null ? null : c.getExpectedTrack().name(),
                    c == null ? null : c.getValidityDate(),
                    c == null ? null : c.getValidityStartDate());
        }

        /** First area that differs, or null if nothing monitored changed. */
        String diff(MonitoredSnapshot after) {
            if (!Objects.equals(fullName, after.fullName)) return "name";
            if (!Objects.equals(phone, after.phone)) return "phone";
            if (!Objects.equals(addressStreet, after.addressStreet)
                    || !Objects.equals(addressApt, after.addressApt)
                    || !Objects.equals(addressCity, after.addressCity)
                    || !Objects.equals(addressState, after.addressState)
                    || !Objects.equals(addressZip, after.addressZip)
                    || !Objects.equals(addressCountry, after.addressCountry)) {
                return "address";
            }
            if (!Objects.equals(skillset, after.skillset)) return "skillset";
            if (!Objects.equals(authorizedToWork, after.authorizedToWork)
                    || !Objects.equals(sponsorshipNeeded, after.sponsorshipNeeded)
                    || !Objects.equals(expectedTrack, after.expectedTrack)
                    || !Objects.equals(validityDate, after.validityDate)
                    || !Objects.equals(validityStartDate, after.validityStartDate)) {
                return "work authorization";
            }
            return null;
        }
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
                    .validityStartDate(candidate.getValidityStartDate())
                    // B2 additions.
                    .addressStreet(candidate.getAddressStreet())
                    .addressApt(candidate.getAddressApt())
                    .addressCity(candidate.getAddressCity())
                    .addressState(candidate.getAddressState())
                    .addressZip(candidate.getAddressZip())
                    .addressCountry(candidate.getAddressCountry())
                    .profileSubmittedAt(candidate.getProfileSubmittedAt());
            // Pull authorizedFrom / authorizedUntil off the WAR row (may
            // not exist yet — treat missing as null).
            workAuthRepository.findByUserId(user.getId()).ifPresent(war -> {
                b.authorizedFrom(war.getAuthorizedFrom());
                b.authorizedUntil(war.getAuthorizedUntil());
            });
        }
        return b.build();
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
