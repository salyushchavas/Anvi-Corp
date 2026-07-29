package com.anvicorp.api.recordings;

import com.anvicorp.api.entity.InternEvaluation;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.InternEvaluationRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Backend side of the evaluator's standalone "Upload Recording" page.
 * Pre-flights the upload by:
 * <ol>
 *   <li>Auto-retrieving the project title from
 *       {@code (internLifecycleId, monthYear, projectNumber)} —
 *       {@code scope=P1} / {@code scope=P2} both map to that lookup;
 *       {@code scope=P1_P2} requires the caller to supply the name.</li>
 *   <li>Finding OR creating an {@link InternEvaluation} row that the
 *       upload will attach to — {@code POST_PROJECT} for scope P1/P2
 *       (with {@code linkedProjectId}), {@code MONTHLY} for scope
 *       P1_P2 (linkedProjectId null; the combined recording covers
 *       both projects).</li>
 * </ol>
 * The frontend then hits the existing presign-upload endpoint against
 * the returned evaluationId, which flows through the approval gate
 * (PENDING_APPROVAL → manager review → APPROVED / REVISION_REQUESTED)
 * exactly the same way the compose-page upload does.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluatorStandaloneUploadService {

    private final InternLifecycleRepository lifecycleRepo;
    private final ProjectRepository projectRepo;
    private final InternEvaluationRepository evaluationRepo;
    private final UserRepository userRepo;

    @Transactional
    public StandaloneRecordingDtos.EvaluatorPrepareResponse prepare(
            StandaloneRecordingDtos.EvaluatorPrepareRequest req, User caller) {
        if (req == null) throw new BadRequestException("body required");
        if (req.internLifecycleId() == null) {
            throw new BadRequestException("internLifecycleId required");
        }
        if (req.monthYear() == null || !req.monthYear().matches("^\\d{4}-\\d{2}$")) {
            throw new BadRequestException("monthYear must be in yyyy-MM format");
        }
        String scope = req.scope() == null ? "" : req.scope().trim().toUpperCase();
        if (!"P1".equals(scope) && !"P2".equals(scope) && !"P1_P2".equals(scope)) {
            throw new BadRequestException("scope must be P1, P2, or P1_P2");
        }

        InternLifecycle lc = lifecycleRepo.findById(req.internLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + req.internLifecycleId()));

        // ── Resolve project name (auto-retrieve when the projects table
        // can supply it) + resolve the target project id for single-scope
        // recordings so the eval row links correctly.
        ResolvedName resolved = resolveProjectName(lc.getId(), req.monthYear(), scope);
        UUID linkedProjectId = resolved.linkedProjectId;
        String projectName = (req.projectNameOverride() != null
                && !req.projectNameOverride().isBlank())
                ? req.projectNameOverride().trim()
                : resolved.name;
        boolean autoRetrieved = (req.projectNameOverride() == null
                || req.projectNameOverride().isBlank())
                && resolved.name != null;
        if (projectName == null || projectName.isBlank()) {
            // Auto-fill couldn't resolve AND the caller didn't type a name —
            // fail at submit time (the field is required in the UI). This
            // is the ONLY hard-error path; the pure lookup endpoint below
            // returns nulls gracefully instead of throwing.
            throw new BadRequestException(
                    "Project name required — no matching project row for "
                            + "(intern, " + req.monthYear() + ", " + scope
                            + "); type one into the Project name field.");
        }

        // ── Find-or-create the target evaluation ───────────────────────
        InternEvaluation ev;
        if ("P1_P2".equals(scope)) {
            // Combined P1+P2 → MONTHLY evaluation with linkedProjectId=null.
            // Look up existing by (lifecycle, periodStart-month) to avoid
            // duplicates on re-upload.
            YearMonth ym = YearMonth.parse(req.monthYear());
            LocalDate periodStart = ym.atDay(1);
            LocalDate periodEnd = ym.atEndOfMonth();
            ev = findMonthlyForPeriod(lc.getId(), periodStart, periodEnd)
                    .orElseGet(() -> createEvaluation(lc, caller, "MONTHLY",
                            null, periodStart, periodEnd));
        } else {
            // Single project → POST_PROJECT tied to the resolved project id.
            final UUID projectId = linkedProjectId;
            ev = projectId != null
                    ? evaluationRepo.findByLinkedProjectId(projectId)
                        .orElseGet(() -> createEvaluation(lc, caller, "POST_PROJECT",
                                projectId, null, null))
                    : createEvaluation(lc, caller, "POST_PROJECT", null, null, null);
        }

        log.info("[EvaluatorStandaloneUpload] prepared eval={} lifecycle={} scope={} month={} auto={}",
                ev.getId(), lc.getId(), scope, req.monthYear(), autoRetrieved);
        return new StandaloneRecordingDtos.EvaluatorPrepareResponse(
                ev.getId(), projectName, autoRetrieved, scope, req.monthYear());
    }

    /**
     * Side-effect-free project-name lookup. Powers the auto-fill on the
     * evaluator upload form: called as the caller changes intern /
     * month / scope, returns whatever the projects table can resolve
     * without creating an evaluation row. Returns a null / blank
     * projectName when the lookup finds nothing — the UI then prompts
     * for a manual entry rather than the caller seeing an error.
     */
    @Transactional(readOnly = true)
    public StandaloneRecordingDtos.LookupProjectNameResponse lookupProjectName(
            UUID lifecycleId, String monthYear, String scopeRaw) {
        if (lifecycleId == null || monthYear == null
                || !monthYear.matches("^\\d{4}-\\d{2}$")) {
            return new StandaloneRecordingDtos.LookupProjectNameResponse(null, false);
        }
        String scope = scopeRaw == null ? "" : scopeRaw.trim().toUpperCase();
        if (!"P1".equals(scope) && !"P2".equals(scope) && !"P1_P2".equals(scope)) {
            return new StandaloneRecordingDtos.LookupProjectNameResponse(null, false);
        }
        // Verify the lifecycle exists — silently returns empty otherwise
        // so a stale dropdown selection can't crash the panel.
        if (lifecycleRepo.findById(lifecycleId).isEmpty()) {
            return new StandaloneRecordingDtos.LookupProjectNameResponse(null, false);
        }
        ResolvedName r = resolveProjectName(lifecycleId, monthYear, scope);
        return new StandaloneRecordingDtos.LookupProjectNameResponse(
                r.name, r.name != null);
    }

    /**
     * Shared resolution helper — used both by the lookup endpoint (no
     * side effects) and by {@link #prepare} (which uses the resolved
     * name + linkedProjectId to seed the evaluation row).
     *
     * <p>P1 / P2 → single row lookup by (lifecycle, month, projectNumber).
     * P1_P2 → fetch project 1 AND project 2 for the (lifecycle, month)
     * pair separately and combine their titles as "A / B". Falls back
     * gracefully when only one exists: returns that one's name alone.
     * Returns a null name when nothing resolves — the caller decides
     * whether to prompt for manual input or hard-error.</p>
     */
    private ResolvedName resolveProjectName(UUID lifecycleId, String monthYear, String scope) {
        if ("P1".equals(scope) || "P2".equals(scope)) {
            short pn = (short) (scope.equals("P1") ? 1 : 2);
            Optional<Project> p = projectRepo
                    .findFirstByInternLifecycleIdAndMonthYearAndProjectNumber(
                            lifecycleId, monthYear, pn);
            if (p.isEmpty()) return new ResolvedName(null, null);
            Project f = p.get();
            String name = nameOf(f);
            return new ResolvedName(
                    name != null && !name.isBlank() ? name : null, f.getId());
        }
        // P1_P2 → combined "A / B" from the two separate project rows.
        Optional<Project> p1 = projectRepo
                .findFirstByInternLifecycleIdAndMonthYearAndProjectNumber(
                        lifecycleId, monthYear, (short) 1);
        Optional<Project> p2 = projectRepo
                .findFirstByInternLifecycleIdAndMonthYearAndProjectNumber(
                        lifecycleId, monthYear, (short) 2);
        String n1 = p1.map(EvaluatorStandaloneUploadService::nameOf).orElse(null);
        String n2 = p2.map(EvaluatorStandaloneUploadService::nameOf).orElse(null);
        boolean has1 = n1 != null && !n1.isBlank();
        boolean has2 = n2 != null && !n2.isBlank();
        String combined;
        if (has1 && has2)      combined = n1 + " / " + n2;
        else if (has1)         combined = n1; // only P1 exists — surface it alone
        else if (has2)         combined = n2; // only P2 exists — surface it alone
        else                   combined = null;
        // For P1_P2 the eval row keeps linkedProjectId=null (the recording
        // spans both projects; no single row it can point at).
        return new ResolvedName(combined, null);
    }

    private static String nameOf(Project p) {
        return p.getName() != null ? p.getName() : p.getTitle();
    }

    /** Small container so the shared resolver can return both the
     *  resolved name and the (optional) linked project id in one shot. */
    private record ResolvedName(String name, UUID linkedProjectId) {}

    /** Active evaluees — populates the intern dropdown on the standalone
     *  upload pages. Joins User in one pass so the picker can show
     *  "{name} · {employeeId}" without a follow-up round trip. */
    @Transactional(readOnly = true)
    public List<StandaloneRecordingDtos.ActiveEvalueeOption> listActiveInterns() {
        List<InternLifecycle> lcs = lifecycleRepo.findByActiveStatusOrderByEmployeeIdAsc("ACTIVE");
        if (lcs.isEmpty()) return List.of();
        java.util.Set<UUID> userIds = new java.util.HashSet<>();
        for (InternLifecycle lc : lcs) if (lc.getUserId() != null) userIds.add(lc.getUserId());
        Map<UUID, String> nameByUser = new HashMap<>();
        for (User u : userRepo.findAllById(userIds)) nameByUser.put(u.getId(), u.getFullName());
        return lcs.stream()
                .map(lc -> new StandaloneRecordingDtos.ActiveEvalueeOption(
                        lc.getId(),
                        lc.getUserId(),
                        nameByUser.get(lc.getUserId()),
                        lc.getEmployeeId()))
                .toList();
    }

    private Optional<InternEvaluation> findMonthlyForPeriod(
            UUID lifecycleId, LocalDate periodStart, LocalDate periodEnd) {
        return evaluationRepo.findByInternLifecycleIdOrderByCreatedAtDesc(lifecycleId)
                .stream()
                .filter(e -> "MONTHLY".equals(e.getEvaluationType()))
                .filter(e -> e.getPeriodStart() != null
                        && !e.getPeriodStart().isBefore(periodStart)
                        && !e.getPeriodStart().isAfter(periodEnd))
                .findFirst();
    }

    private InternEvaluation createEvaluation(
            InternLifecycle lc, User caller, String type,
            UUID linkedProjectId, LocalDate periodStart, LocalDate periodEnd) {
        InternEvaluation ev = InternEvaluation.builder()
                .internLifecycleId(lc.getId())
                .internId(lc.getUserId())
                .evaluatorId(caller.getId())
                .evaluationType(type)
                .linkedProjectId(linkedProjectId)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .status("DRAFT")
                .version(1)
                .build();
        return evaluationRepo.save(ev);
    }
}
