package com.anvicorp.api.trainer.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Trainer Phase 4 — per-user Trainer preferences. Persists to the
 *  {@code prefs_trainer_*} columns added on the {@code users} table.
 *  Sensible defaults applied client-side and reflected here when the
 *  user hasn't picked anything yet. */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrainerSettingsService {

    private static final Set<String> RECURRENCE = Set.of("NONE", "WEEKLY");
    private static final Set<String> PRIORITY = Set.of("OLDEST", "NEWEST", "INTERN");
    private static final Set<String> EMAIL_FREQ = Set.of("DAILY", "WEEKLY", "NEVER");

    public record TrainerSettingsDto(
            String defaultRecurrence,
            Short defaultDuration,
            String reviewPriority,
            Boolean notifyStakeholders,
            String emailFrequency,
            Boolean notifySubmissions,
            Boolean notifyEscalationResolved,
            // Global account flags surfaced on the same page
            Boolean prefsReminders,
            Boolean prefsEngagementUpdates
    ) {}

    public record UpdateSettingsRequest(
            String defaultRecurrence,
            Short defaultDuration,
            String reviewPriority,
            Boolean notifyStakeholders,
            String emailFrequency,
            Boolean notifySubmissions,
            Boolean notifyEscalationResolved,
            Boolean prefsReminders,
            Boolean prefsEngagementUpdates
    ) {}

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public TrainerSettingsDto get(User caller) {
        requireTrainer(caller);
        User u = userRepository.findById(caller.getId())
                .orElseThrow(() -> new ForbiddenException("Caller not found"));
        return toDto(u);
    }

    @Transactional
    public TrainerSettingsDto update(UpdateSettingsRequest req, User caller) {
        requireTrainer(caller);
        if (req == null) throw new BadRequestException("body required");
        validate(req);

        User u = userRepository.findById(caller.getId())
                .orElseThrow(() -> new ForbiddenException("Caller not found"));
        Map<String, Object> before = snapshot(u);

        if (req.defaultRecurrence() != null) {
            u.setPrefsTrainerDefaultRecurrence(req.defaultRecurrence().toUpperCase());
        }
        if (req.defaultDuration() != null) {
            u.setPrefsTrainerDefaultDuration(req.defaultDuration());
        }
        if (req.reviewPriority() != null) {
            u.setPrefsTrainerReviewPriority(req.reviewPriority().toUpperCase());
        }
        if (req.notifyStakeholders() != null) {
            u.setPrefsTrainerNotifyStakeholders(req.notifyStakeholders());
        }
        if (req.emailFrequency() != null) {
            u.setPrefsTrainerEmailFrequency(req.emailFrequency().toUpperCase());
        }
        if (req.notifySubmissions() != null) {
            u.setPrefsTrainerNotifySubmissions(req.notifySubmissions());
        }
        if (req.notifyEscalationResolved() != null) {
            u.setPrefsTrainerNotifyEscalationResolved(req.notifyEscalationResolved());
        }
        if (req.prefsReminders() != null) {
            u.setPrefsReminders(req.prefsReminders());
        }
        if (req.prefsEngagementUpdates() != null) {
            u.setPrefsEngagementUpdates(req.prefsEngagementUpdates());
        }
        u = userRepository.save(u);

        writeAudit(u, before, snapshot(u));
        return toDto(u);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void requireTrainer(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        if (!caller.getRoles().contains(UserRole.TRAINER)
                && !caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            throw new ForbiddenException("TRAINER or SUPER_ADMIN required");
        }
    }

    private void validate(UpdateSettingsRequest req) {
        if (req.defaultRecurrence() != null
                && !RECURRENCE.contains(req.defaultRecurrence().toUpperCase())) {
            throw new BadRequestException(
                    "defaultRecurrence must be one of " + RECURRENCE);
        }
        if (req.defaultDuration() != null
                && (req.defaultDuration() < 15 || req.defaultDuration() > 180)) {
            throw new BadRequestException(
                    "defaultDuration must be 15-180 minutes");
        }
        if (req.reviewPriority() != null
                && !PRIORITY.contains(req.reviewPriority().toUpperCase())) {
            throw new BadRequestException(
                    "reviewPriority must be one of " + PRIORITY);
        }
        if (req.emailFrequency() != null
                && !EMAIL_FREQ.contains(req.emailFrequency().toUpperCase())) {
            throw new BadRequestException(
                    "emailFrequency must be one of " + EMAIL_FREQ);
        }
    }

    private TrainerSettingsDto toDto(User u) {
        return new TrainerSettingsDto(
                u.getPrefsTrainerDefaultRecurrence(),
                u.getPrefsTrainerDefaultDuration(),
                u.getPrefsTrainerReviewPriority(),
                u.getPrefsTrainerNotifyStakeholders(),
                u.getPrefsTrainerEmailFrequency(),
                u.getPrefsTrainerNotifySubmissions(),
                u.getPrefsTrainerNotifyEscalationResolved(),
                u.getPrefsReminders(),
                u.getPrefsEngagementUpdates());
    }

    private Map<String, Object> snapshot(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("defaultRecurrence", u.getPrefsTrainerDefaultRecurrence());
        m.put("defaultDuration", u.getPrefsTrainerDefaultDuration());
        m.put("reviewPriority", u.getPrefsTrainerReviewPriority());
        m.put("notifyStakeholders", u.getPrefsTrainerNotifyStakeholders());
        m.put("emailFrequency", u.getPrefsTrainerEmailFrequency());
        m.put("notifySubmissions", u.getPrefsTrainerNotifySubmissions());
        m.put("notifyEscalationResolved", u.getPrefsTrainerNotifyEscalationResolved());
        m.put("prefsReminders", u.getPrefsReminders());
        m.put("prefsEngagementUpdates", u.getPrefsEngagementUpdates());
        return m;
    }

    private void writeAudit(User u, Map<String, Object> before,
                             Map<String, Object> after) {
        try {
            AuditLog row = AuditLog.builder()
                    .userId(u.getId())
                    .subjectUserId(u.getId())
                    .entityType("User")
                    .entityId(u.getId())
                    .action("TRAINER_SETTINGS_UPDATE")
                    .beforeJson(before != null
                            ? objectMapper.writeValueAsString(before) : null)
                    .afterJson(after != null
                            ? objectMapper.writeValueAsString(after) : null)
                    .build();
            auditLogRepository.save(row);
        } catch (Exception e) {
            log.warn("[TrainerSettings] audit write failed: {}", e.getMessage());
        }
    }
}
