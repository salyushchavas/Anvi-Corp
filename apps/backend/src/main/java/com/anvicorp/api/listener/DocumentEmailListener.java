package com.anvicorp.api.listener;

import com.anvicorp.api.entity.DocumentPacket;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.erm.CommunicationTemplateService;
import com.anvicorp.api.erm.ReasonCode;
import com.anvicorp.api.event.DocumentPacketAssignedEvent;
import com.anvicorp.api.event.DocumentPacketCompletedEvent;
import com.anvicorp.api.event.DocumentTaskReviewedEvent;
import com.anvicorp.api.event.DocumentTaskSubmittedEvent;
import com.anvicorp.api.intern.OrgTeamResolver;
import com.anvicorp.api.notification.EmailProvider;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.DocumentPacketRepository;
import com.anvicorp.api.repository.DocumentTaskRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ERM Phase 8 — fans out the 5 document-packet-workflow templates to
 * the right recipients. All AFTER_COMMIT, all best-effort: failure
 * never blocks the upstream transaction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentEmailListener {

    private static final String INTERN_DASH = "/careers/intern/documents";
    private static final String ERM_REVIEW = "/careers/erm/document-review";

    private final UserRepository userRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final DocumentPacketRepository packetRepository;
    private final DocumentTaskRepository taskRepository;
    private final CommunicationTemplateService templateService;
    private final EmailProvider emailProvider;
    private final UserNotificationDispatcher dispatcher;
    private final OrgTeamResolver orgTeamResolver;
    private final com.anvicorp.api.config.BrandConfig brand;

    /**
     * Frontend base URL used to build absolute {@code deepLink} values in
     * email bodies (so the template renders a real clickable URL rather
     * than a bare "/careers/..." string most mail clients don't
     * auto-linkify). Same {@code app.frontend.base-url} config peer
     * email code injects (e.g. {@code EvaluationNotificationFanout}).
     */
    @Value("${app.frontend.base-url:https://www.anvicorp.com}")
    private String frontendBaseUrl;

    private String absoluteLink(String path) {
        String base = frontendBaseUrl == null ? "" : frontendBaseUrl.replaceAll("/+$", "");
        return base + path;
    }

    // ── Packet assigned ──────────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPacketAssigned(DocumentPacketAssignedEvent e) {
        if (e == null || e.getInternUserId() == null) return;
        try {
            User intern = userRepository.findById(e.getInternUserId()).orElse(null);
            if (intern == null) return;
            User erm = e.getAssignedByUserId() != null
                    ? userRepository.findById(e.getAssignedByUserId()).orElse(null)
                    : null;
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("firstName", firstName(intern));
            vars.put("ermName", erm != null ? nz(erm.getFullName()) : brand.getName() + " ERM");
            vars.put("templateCount", e.getTemplateTitles() == null ? 0
                    : e.getTemplateTitles().size());
            vars.put("templateTitlesList", joinBulleted(e.getTemplateTitles()));
            vars.put("deepLink", absoluteLink(INTERN_DASH));
            renderAndSend("DOCUMENT_PACKET_ASSIGNED", vars, intern);
            dispatcher.dispatch(intern.getId(), "DOCUMENT_PACKET_ASSIGNED",
                    intern.getId(),
                    "Your document packet is ready",
                    "Open your dashboard to start the " + vars.get("templateCount")
                            + " documents.",
                    INTERN_DASH, true);
        } catch (Exception ex) {
            log.warn("[DocumentEmail] packet-assigned handler failed: {}", ex.getMessage());
        }
    }

    // ── Task submitted (ERM nudge only — no email to intern) ─────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskSubmitted(DocumentTaskSubmittedEvent e) {
        if (e == null || e.getInternLifecycleId() == null) return;
        try {
            InternLifecycle lc = lifecycleRepository
                    .findById(e.getInternLifecycleId()).orElse(null);
            if (lc == null || lc.getErmId() == null) return;
            User intern = userRepository.findById(e.getInternUserId()).orElse(null);
            String label = e.getTemplateTitle() != null
                    ? e.getTemplateTitle() : "a document";
            dispatcher.dispatch(lc.getErmId(), "DOCUMENT_SUBMITTED_FOR_REVIEW",
                    e.getInternUserId(),
                    "Submission ready: " + label,
                    (intern != null ? intern.getFullName() : "an intern")
                            + " uploaded " + label + " for review.",
                    ERM_REVIEW + "/tasks/" + e.getTaskId(), false);
        } catch (Exception ex) {
            log.warn("[DocumentEmail] task-submitted handler failed: {}", ex.getMessage());
        }
    }

    // ── Task reviewed (3 decisions → 1 of 3 templates; NO_ACTION_YET-style
    //    handled by ACCEPT silently if the spec ever needs it) ────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskReviewed(DocumentTaskReviewedEvent e) {
        if (e == null || e.getInternUserId() == null) return;
        try {
            User intern = userRepository.findById(e.getInternUserId()).orElse(null);
            if (intern == null) return;
            User erm = e.getReviewerUserId() != null
                    ? userRepository.findById(e.getReviewerUserId()).orElse(null) : null;
            String templateKey = switch (e.getDecision()) {
                case "ACCEPT" -> "DOCUMENT_TASK_ACCEPTED";
                case "REJECT" -> "DOCUMENT_TASK_REJECTED";
                case "RESEND_REQUEST" -> "DOCUMENT_TASK_RESEND";
                default -> null;
            };
            if (templateKey == null) return;
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("firstName", firstName(intern));
            vars.put("templateTitle", nz(e.getTemplateTitle()));
            vars.put("ermName", erm != null ? nz(erm.getFullName()) : brand.getName() + " ERM");
            vars.put("reasonHuman", humanReason(e.getReasonCode()));
            vars.put("ermComments", nz(e.getErmComments()));
            vars.put("deepLink", absoluteLink(INTERN_DASH));
            vars.put("remainingTasksBlurb", buildRemainingBlurb(e.getPacketId()));
            renderAndSend(templateKey, vars, intern);
            dispatcher.dispatch(intern.getId(), "DOCUMENT_TASK_" + e.getDecision(),
                    intern.getId(),
                    "Document " + e.getDecision().toLowerCase() + ": "
                            + nz(e.getTemplateTitle()),
                    "ACCEPT".equals(e.getDecision())
                            ? "Your submission was accepted."
                            : "Please open your dashboard to act on this document.",
                    INTERN_DASH, true);
        } catch (Exception ex) {
            log.warn("[DocumentEmail] task-reviewed handler failed: {}", ex.getMessage());
        }
    }

    // ── Packet completed (welcome email) ─────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPacketCompleted(DocumentPacketCompletedEvent e) {
        if (e == null || e.getInternUserId() == null) return;
        try {
            User intern = userRepository.findById(e.getInternUserId()).orElse(null);
            if (intern == null) return;
            InternLifecycle lc = lifecycleRepository
                    .findById(e.getInternLifecycleId()).orElse(null);
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("firstName", firstName(intern));
            vars.put("tentativeStartDate",
                    lc != null && lc.getTentativeStartDate() != null
                            ? lc.getTentativeStartDate().toString()
                            : "your scheduled start date");
            // Trainer + Evaluator route through OrgTeamResolver so the
            // welcome email shows the org-wide singleton's name when
            // the per-intern FK is null; Manager stays direct.
            vars.put("trainerName", nameFor(lc != null ? orgTeamResolver.resolveTrainerId(lc) : null));
            vars.put("evaluatorName", nameFor(lc != null ? orgTeamResolver.resolveEvaluatorId(lc) : null));
            vars.put("managerName", nameFor(lc != null ? lc.getManagerId() : null));
            renderAndSend("DOCUMENT_PACKET_COMPLETED", vars, intern);
            dispatcher.dispatch(intern.getId(), "DOCUMENT_PACKET_COMPLETED",
                    intern.getId(),
                    "Onboarding complete — welcome to " + brand.getName() + "!",
                    "All your documents have been accepted.",
                    INTERN_DASH, true);
        } catch (Exception ex) {
            log.warn("[DocumentEmail] packet-completed handler failed: {}", ex.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void renderAndSend(String templateKey, Map<String, Object> vars, User recipient) {
        if (recipient == null || recipient.getEmail() == null) return;
        try {
            var rendered = templateService.render(templateKey, "EMAIL", vars).orElse(null);
            if (rendered == null) {
                log.debug("[DocumentEmail] template {} missing — skipping send", templateKey);
                return;
            }
            emailProvider.sendRendered(recipient.getEmail(),
                    rendered.subject() != null ? rendered.subject() : templateKey,
                    rendered.body() != null ? rendered.body() : "");
        } catch (Exception e) {
            log.warn("[DocumentEmail] renderAndSend failed for {}: {}",
                    templateKey, e.getMessage());
        }
    }

    private String buildRemainingBlurb(UUID packetId) {
        if (packetId == null) return "";
        try {
            DocumentPacket pk = packetRepository.findById(packetId).orElse(null);
            if (pk == null) return "";
            long remaining = taskRepository.countByPacketIdAndStatusNotIn(
                    packetId, java.util.List.of("ACCEPTED", "WAIVED"));
            if (remaining == 0) return "All your documents are now accepted.";
            return remaining + " document" + (remaining == 1 ? "" : "s")
                    + " left in your packet.";
        } catch (Exception e) {
            return "";
        }
    }

    private String nameFor(UUID userId) {
        if (userId == null) return "TBD";
        try {
            return userRepository.findById(userId)
                    .map(u -> nz(u.getFullName())).orElse("TBD");
        } catch (Exception e) {
            return "TBD";
        }
    }

    private static String humanReason(String code) {
        if (code == null || code.isBlank()) return "Not specified";
        try { return ReasonCode.valueOf(code).humanLabel(); }
        catch (Exception e) { return code.replace('_', ' ').toLowerCase(); }
    }

    private static String firstName(User u) {
        if (u == null || u.getFullName() == null) return "there";
        String[] parts = u.getFullName().trim().split("\\s+", 2);
        return parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "there";
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String joinBulleted(java.util.List<String> titles) {
        if (titles == null || titles.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String t : titles) {
            sb.append(" · ").append(t).append('\n');
        }
        return sb.toString();
    }
}
