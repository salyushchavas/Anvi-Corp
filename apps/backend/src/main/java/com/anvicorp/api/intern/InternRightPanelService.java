package com.anvicorp.api.intern;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserNotificationRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7 — server-computed right-side panel payload for the intern surface.
 * Single endpoint returns the four contact slots + bell badge count + a
 * rule-driven list of compliance reminders. Frontend renders verbatim.
 *
 * <p>This phase ships the contacts + unread badge + a small set of
 * lifecycle-stage reminders. The full reminder rule matrix (I-9 §2
 * deadline, weekly report due, timesheet due, project due, etc.) will be
 * extended as the upstream services land their dedicated repositories.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InternRightPanelService {

    private final InternLifecycleRepository lifecycleRepository;
    private final UserRepository userRepository;
    private final UserNotificationRepository userNotificationRepository;
    private final InternEvaluationService internEvaluationService;
    private final OrgTeamResolver orgTeamResolver;

    public Map<String, Object> build(User caller) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("contacts", buildContacts(caller));
        long unread = 0;
        try {
            unread = userNotificationRepository.countByRecipientUserIdAndReadAtIsNull(caller.getId());
        } catch (Exception e) {
            log.warn("[RightPanel] unread count failed (non-fatal): {}", e.getMessage());
        }
        resp.put("unreadCount", unread);
        resp.put("reminders", buildReminders(caller));
        return resp;
    }

    // ── Contacts ──────────────────────────────────────────────────────────

    private Map<String, Object> buildContacts(User caller) {
        Map<String, Object> contacts = new LinkedHashMap<>();
        contacts.put("erm", null);
        contacts.put("trainer", null);
        contacts.put("evaluator", null);
        contacts.put("manager", null);
        Optional<InternLifecycle> lcOpt = lifecycleRepository.findByUserId(caller.getId());
        if (lcOpt.isEmpty()) return contacts;
        InternLifecycle lc = lcOpt.get();
        // Trainer + Evaluator route through OrgTeamResolver so the
        // org-wide singleton (DEFAULT_TRAINER_EMAIL /
        // DEFAULT_EVALUATOR_EMAIL) surfaces in the team box even when
        // the per-intern FK is null — empty slots were the visible
        // symptom of the same null-link gap that bit the assignProject
        // notify path.
        contacts.put("erm", resolveContact(lc.getErmId(), "ERM"));
        contacts.put("trainer", resolveContact(orgTeamResolver.resolveTrainerId(lc), "Trainer"));
        contacts.put("evaluator", resolveContact(orgTeamResolver.resolveEvaluatorId(lc), "Evaluator"));
        contacts.put("manager", resolveContact(lc.getManagerId(), "Manager"));
        return contacts;
    }

    private Map<String, Object> resolveContact(UUID userId, String role) {
        if (userId == null) return null;
        return userRepository.findById(userId).map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", u.getFullName() != null ? u.getFullName() : u.getEmail());
            m.put("email", u.getEmail());
            m.put("role", role);
            return m;
        }).orElse(null);
    }

    // ── Reminders ─────────────────────────────────────────────────────────

    private List<Map<String, Object>> buildReminders(User caller) {
        List<Map<String, Object>> reminders = new ArrayList<>();

        // Email-not-verified — URGENT — Phase 1/2.
        if (!Boolean.TRUE.equals(caller.getEmailVerified())) {
            reminders.add(reminder("URGENT",
                    "Verify your email to continue.",
                    "/careers/intern"));
        }

        // Unacknowledged published evaluation — INFO — Phase 6.
        try {
            internEvaluationService.listForIntern(caller.getId()).stream()
                    .filter(e -> ("PUBLISHED".equals(e.getStatus()) || "AMENDED".equals(e.getStatus()))
                            && e.getInternAcknowledgedAt() == null)
                    .findFirst()
                    .ifPresent(e -> reminders.add(reminder("INFO",
                            "New evaluation feedback available.",
                            "/careers/intern/evaluations/" + e.getId())));
        } catch (Exception e) {
            log.warn("[RightPanel] evaluation reminder check failed: {}", e.getMessage());
        }

        // Phase 7 baseline — onboarding-stage gentle nudge.
        if (caller.getLifecycleStatus() != null) {
            switch (caller.getLifecycleStatus()) {
                case ONBOARDING_ASSIGNED -> reminders.add(reminder("WARN",
                        "Complete your onboarding documents.",
                        "/careers/intern/documents"));
                case OFFER_SENT -> reminders.add(reminder("URGENT",
                        "Sign your offer letter.",
                        "/careers/intern/offer"));
                default -> {
                    // No additional baseline reminder for other states.
                }
            }
        }
        return reminders;
    }

    private static Map<String, Object> reminder(String severity, String text, String actionUrl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severity", severity);
        m.put("text", text);
        m.put("actionUrl", actionUrl);
        return m;
    }
}
