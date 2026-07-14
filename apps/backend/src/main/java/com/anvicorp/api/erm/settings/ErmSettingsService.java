package com.anvicorp.api.erm.settings;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.erm.CommunicationTemplate;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.erm.CommunicationTemplateRepository;
import com.anvicorp.api.erm.CommunicationTemplateSeeder;
import com.anvicorp.api.erm.CommunicationTemplateService;
import com.anvicorp.api.erm.ReasonCode;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ERM Phase 7 — Settings service: CommunicationTemplate editor with
 * Mustache live preview + restore-default; read-only ReasonCode
 * taxonomy view; ERM workload overview.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErmSettingsService {

    private final CommunicationTemplateRepository templateRepository;
    private final CommunicationTemplateService templateService;
    private final CommunicationTemplateSeeder templateSeeder;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbc;

    // ── Templates ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ErmSettingsDtos.TemplateRow> listTemplates() {
        List<CommunicationTemplate> all = templateRepository.findAll();
        all.sort(Comparator.comparing(CommunicationTemplate::getKey));
        List<ErmSettingsDtos.TemplateRow> out = new ArrayList<>();
        for (CommunicationTemplate t : all) {
            out.add(toRow(t));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ErmSettingsDtos.TemplateRow getTemplate(String key) {
        CommunicationTemplate t = templateService.get(key, "EMAIL")
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Template not found: " + key));
        return toRow(t);
    }

    @Transactional
    public ErmSettingsDtos.TemplateRow updateTemplate(
            String key, ErmSettingsDtos.TemplateUpdateRequest req, User caller) {
        CommunicationTemplate t = templateService.get(key, "EMAIL")
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Template not found: " + key));
        if (req == null) throw new BadRequestException("body is required");
        if (req.subjectTemplate() == null || req.subjectTemplate().isBlank()) {
            throw new BadRequestException("subjectTemplate is required");
        }
        if (req.bodyTemplate() == null || req.bodyTemplate().isBlank()) {
            throw new BadRequestException("bodyTemplate is required");
        }
        t.setSubjectTemplate(req.subjectTemplate());
        t.setBodyTemplate(req.bodyTemplate());
        if (req.active() != null) t.setActive(req.active());
        return toRow(templateService.save(t, caller.getId()));
    }

    @Transactional
    public ErmSettingsDtos.TemplateRow restoreDefault(String key, User caller) {
        var seed = templateSeeder.findSeed(key, "EMAIL")
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No seeded default for " + key));
        CommunicationTemplate t = templateService.get(key, "EMAIL")
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Template not found: " + key));
        t.setSubjectTemplate(seed.subject());
        t.setBodyTemplate(seed.body());
        t.setVariablesCsv(seed.vars());
        t.setActive(true);
        return toRow(templateService.save(t, caller.getId()));
    }

    @Transactional(readOnly = true)
    public ErmSettingsDtos.TemplatePreviewResponse preview(
            String key, ErmSettingsDtos.TemplatePreviewRequest req) {
        Map<String, Object> vars = req != null && req.variables() != null
                ? req.variables()
                : SampleVarsRegistry.samplesFor(key);
        // Layer on defaults for any vars the caller didn't supply so the
        // preview doesn't 400 on every missing field.
        Map<String, Object> merged = new LinkedHashMap<>(
                SampleVarsRegistry.samplesFor(key));
        merged.putAll(vars);
        try {
            var rendered = templateService.render(key, "EMAIL", merged)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Template not found: " + key));
            return new ErmSettingsDtos.TemplatePreviewResponse(
                    rendered.subject(), rendered.body());
        } catch (IllegalArgumentException e) {
            // Missing-variable surfaces as 400 with the offending var name.
            throw new BadRequestException(e.getMessage());
        }
    }

    private ErmSettingsDtos.TemplateRow toRow(CommunicationTemplate t) {
        return new ErmSettingsDtos.TemplateRow(
                t.getKey(),
                t.getChannel(),
                t.getSubjectTemplate(),
                t.getBodyTemplate(),
                t.getVariablesCsv(),
                t.getActive(),
                t.getUpdatedAt(),
                t.getUpdatedById(),
                categoryOf(t.getKey()));
    }

    private static String categoryOf(String key) {
        if (key == null) return "Other";
        if (key.startsWith("APPLICATION_")) return "Application";
        if (key.startsWith("INTERVIEW_")) return "Interview";
        if (key.startsWith("OFFER_") || key.startsWith("REPORTING_STRUCTURE")
                || key.startsWith("START_DATE_")) return "Offer / New Hire";
        if (key.startsWith("ONBOARDING_")) return "Onboarding";
        if (key.startsWith("EVERIFY_") || key.startsWith("WORK_AUTH_")) return "Compliance";
        if (key.startsWith("EXIT_")) return "Exit";
        return "Other";
    }

    // ── Reason codes (read-only) ─────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ErmSettingsDtos.ReasonCodeGroup> listReasonCodes() {
        Map<ReasonCode.Category, List<ErmSettingsDtos.ReasonCodeOption>> bucket =
                new LinkedHashMap<>();
        for (ReasonCode rc : ReasonCode.values()) {
            bucket.computeIfAbsent(rc.category(), k -> new ArrayList<>())
                    .add(new ErmSettingsDtos.ReasonCodeOption(
                            rc.name(), rc.humanLabel(), rc.requiresFreeText()));
        }
        List<ErmSettingsDtos.ReasonCodeGroup> out = new ArrayList<>();
        for (var e : bucket.entrySet()) {
            out.add(new ErmSettingsDtos.ReasonCodeGroup(
                    e.getKey().name(), e.getValue()));
        }
        return out;
    }

    // ── Workload ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ErmSettingsDtos.WorkloadRow> listWorkload(User caller) {
        requireSuperAdmin(caller);
        return computeWorkload(null);
    }

    @Transactional(readOnly = true)
    public ErmSettingsDtos.WorkloadRow myWorkload(User caller) {
        requireErm(caller);
        var rows = computeWorkload(caller.getId());
        return rows.isEmpty()
                ? new ErmSettingsDtos.WorkloadRow(
                        caller.getId(), caller.getFullName(), caller.getEmail(),
                        0L, 0L, 0L, 0L)
                : rows.get(0);
    }

    private List<ErmSettingsDtos.WorkloadRow> computeWorkload(UUID singleErmId) {
        // Walk the ERM users via JPA (Set<UserRole> is mapped via join
        // table; safer than a stringly-typed SQL LIKE). Per-user counts
        // are 4 indexed COUNT queries — fine for ERM-count cardinality.
        List<User> erms;
        try {
            erms = userRepository.findAll().stream()
                    .filter(u -> u.getRoles() != null
                            && u.getRoles().contains(UserRole.ERM))
                    .filter(u -> singleErmId == null || singleErmId.equals(u.getId()))
                    .sorted(Comparator.comparing(
                            u -> u.getFullName() != null ? u.getFullName() : ""))
                    .toList();
        } catch (Exception e) {
            log.warn("[ErmSettings] ERM user enumeration failed: {}", e.getMessage());
            return List.of();
        }
        List<ErmSettingsDtos.WorkloadRow> out = new ArrayList<>();
        for (User u : erms) {
            long interns = countSafe(
                    "SELECT COUNT(*) FROM intern_lifecycles "
                            + " WHERE erm_id = ? AND active_status = 'ACTIVE'",
                    u.getId());
            long apps = countSafe(
                    "SELECT COUNT(*) FROM applications "
                            + " WHERE erm_owner_id = ? "
                            + "   AND status IN ('APPLIED','SHORTLISTED','INTERVIEWED','SELECTED_CONDITIONAL','OFFERED')",
                    u.getId());
            long offers = countSafe(
                    "SELECT COUNT(*) FROM offers "
                            + " WHERE created_by = ? AND status = 'SENT'",
                    u.getId());
            long exceptions = countSafe(
                    "SELECT COUNT(*) FROM exception_records "
                            + " WHERE assigned_to_id = ? "
                            + "   AND status IN ('OPEN','ASSIGNED','IN_PROGRESS')",
                    u.getId());
            out.add(new ErmSettingsDtos.WorkloadRow(
                    u.getId(), u.getFullName(), u.getEmail(),
                    interns, apps, offers, exceptions));
        }
        return out;
    }

    private long countSafe(String sql, Object... params) {
        try {
            Long c = jdbc.queryForObject(sql, Long.class, params);
            return c == null ? 0L : c;
        } catch (Exception e) {
            log.debug("[ErmSettings] count fallback failed: {}", e.getMessage());
            return 0L;
        }
    }

    private void requireSuperAdmin(User caller) {
        if (caller == null || !caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            throw new ForbiddenException("SUPER_ADMIN required");
        }
    }

    private void requireErm(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        if (!caller.getRoles().contains(UserRole.ERM)
                && !caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            throw new ForbiddenException("ERM or SUPER_ADMIN required");
        }
    }
}
