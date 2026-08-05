package com.anvicorp.api.erm.directonboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.admin.onboardingtemplates.OnboardingDocumentTemplate;
import com.anvicorp.api.admin.onboardingtemplates.OnboardingDocumentTemplateRepository;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.DocumentPacket;
import com.anvicorp.api.entity.DocumentTask;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.StaffingEntity;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WorkAuthorizationRecord;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.enums.WorkAuthTrack;
import com.anvicorp.api.event.InternActivatedEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.intern.DocumentVaultService;
import com.anvicorp.api.intern.ReportingStructureAutoLinker;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.DocumentPacketRepository;
import com.anvicorp.api.repository.DocumentTaskRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.StaffingEntityRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.repository.WorkAuthorizationRecordRepository;
import com.anvicorp.api.service.ApplicantIdGenerator;
import com.anvicorp.api.service.EmployeeIdGenerator;
import com.anvicorp.api.service.EngagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * ERM "Direct Onboarding" — one-shot registration of a pre-platform
 * employee straight into the platform. Bypasses the entire
 * application → screening → interview → offer funnel: the ERM has
 * already hired the person off-platform, and this flow imports them
 * so they land in Active Interns and can be tracked from
 * the current registration month onward.
 *
 * <h2>Landmines this flow sidesteps</h2>
 * <ul>
 *   <li><b>Engagement FKs:</b> engagement.application_id / offer_id go
 *       nullable via {@code SchemaFixupRunner.ensureEngagementsNullableUniqueSchema()}
 *       so a direct-hire row can exist without a funnel origin. Uniqueness
 *       on the offer-flow rows remains, enforced by partial indexes.</li>
 *   <li><b>Activation gate:</b> {@code InternActivationJob.activateNow}
 *       still requires {@code lifecycleStatus == ONBOARDING_ACCEPTED} and
 *       looks up a SIGNED/ACCEPTED offer via
 *       {@code offerRepository.findByApplication_Candidate_User_Id...} —
 *       both would fail for a direct-hire. We activate <em>inline</em>
 *       instead: advance the lifecycle straight to {@code ACTIVE_INTERN}
 *       via {@code InternLifecycleService.advance}, then set the same
 *       {@code activeStatus=ACTIVE / startedAt=now} the job would have,
 *       and publish the same {@code InternActivatedEvent} so downstream
 *       listeners fire once. Documented in method Javadoc + report.</li>
 *   <li><b>Month anchoring:</b> {@code startedAt = Instant.now()} anchors
 *       the intern to the REGISTRATION month regardless of the ERM-supplied
 *       {@code joiningDate} — the latter is retained as a display-only
 *       historical fact. Every month-aware surface keys on {@code startedAt}
 *       already; the intern shows up in the current month's rosters
 *       immediately.</li>
 * </ul>
 *
 * <h2>Atomicity</h2>
 * The entire create + activate flow runs in a single {@code @Transactional}
 * block on {@link #directOnboard}. Mailbox provisioning is deliberately
 * OUT of that transaction (the controller calls
 * {@code CareersMailProvisioningService.provisionForIntern} after this
 * method returns) so mailbox failures don't roll back the successful
 * intern create — the ERM can retry the mailbox handoff later via the
 * existing AssignCompanyEmailDialog.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectOnboardingService {

    private final UserRepository userRepository;
    private final CandidateRepository candidateRepository;
    private final InternLifecycleRepository internLifecycleRepository;
    private final StaffingEntityRepository staffingEntityRepository;
    private final WorkAuthorizationRecordRepository workAuthRepository;
    private final OnboardingDocumentTemplateRepository templateRepository;
    private final DocumentPacketRepository documentPacketRepository;
    private final DocumentTaskRepository documentTaskRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    private final ApplicantIdGenerator applicantIdGenerator;
    private final EmployeeIdGenerator employeeIdGenerator;
    private final EngagementService engagementService;
    private final DocumentVaultService documentVaultService;
    private final ReportingStructureAutoLinker reportingStructureAutoLinker;
    private final com.anvicorp.api.intern.InternLifecycleService internLifecycleService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Single-transaction direct-onboarding pipeline.
     *
     * <ol>
     *   <li><b>Guards</b> — ERM/SUPER_ADMIN check; email + phone shape;
     *       resume required; document keys resolve to active
     *       onboarding-document templates.</li>
     *   <li><b>Email uniqueness pre-check</b> — 409 with a clean message
     *       instead of letting the DB unique constraint surface as a raw
     *       server error.</li>
     *   <li><b>User</b> — {@code roles={INTERN}}, {@code emailVerified=true},
     *       {@code active=true}, {@code passwordHash=null} (staff-activation-link
     *       pattern — the controller sends the credentials email
     *       out-of-band via mailbox provisioning if the ERM opted in),
     *       {@code applicantId} minted via {@link ApplicantIdGenerator},
     *       {@code tosAcceptedAt=now / tosVersion=EMPLOYER_REGISTERED} so
     *       audit shows the employer-registered path.</li>
     *   <li><b>Candidate</b> — minimal shell (user FK + expectedTrack
     *       snapshotted). {@code existsBy user} guarded for the
     *       "once-registered candidate re-directed-onboarded" edge case.</li>
     *   <li><b>Employee ID</b> — via {@link EmployeeIdGenerator}, stamped
     *       on the User row.</li>
     *   <li><b>InternLifecycle</b> — the {@code (userId, employeeId)}
     *       uniqueness guards mirror the offer-signing path
     *       ({@code OfferIdmsSigningService:381}); {@code ermId=caller.id},
     *       {@code hiredAt=now}, {@code joiningDate} from the request
     *       (informational only), {@code activeStatus=PROSPECTIVE}
     *       initially so the auto-linker + inline-activate flip it once
     *       the reporting structure is in place.</li>
     *   <li><b>Reporting structure</b> — apply
     *       {@link ReportingStructureAutoLinker}; explicit trainer /
     *       evaluator / manager overrides from the request take precedence
     *       (they're set BEFORE the linker, and the linker preserves
     *       existing non-null assignments).</li>
     *   <li><b>Engagement</b> — via
     *       {@link EngagementService#createForDirectHire}.</li>
     *   <li><b>WorkAuthorizationRecord</b> — same upsert shape as
     *       {@code ErmComplianceService.updateWorkAuth} to keep exactly one
     *       write path.</li>
     *   <li><b>Documents</b> — one {@code DocumentPacket}
     *       ({@code status=COMPLETED, completedAt=now, assignedById=caller.id});
     *       one {@code DocumentTask} per uploaded file ({@code status=ACCEPTED},
     *       {@code reviewedBy=caller.id}, {@code reviewedAt=now},
     *       {@code reviewComments="ERM-provided at direct onboarding"}),
     *       file bytes written via
     *       {@link DocumentVaultService#saveDocument} — the SAME S3/disk
     *       pipeline the intern packet flow uses.</li>
     *   <li><b>Activation (inline)</b> — advance lifecycleStatus to
     *       {@code ACTIVE_INTERN} via {@code internLifecycleService.advance};
     *       set {@code activeStatus=ACTIVE / startedAt=now} directly on the
     *       lifecycle row; publish {@link InternActivatedEvent}. The
     *       scheduled {@code InternActivationJob} is bypassed because its
     *       {@code activateNow} still requires {@code ONBOARDING_ACCEPTED}
     *       and looks up a SIGNED/ACCEPTED offer that direct-hires don't
     *       have.</li>
     *   <li><b>Audit</b> — {@code DIRECT_ONBOARDED} action on the User row.</li>
     * </ol>
     */
    @Transactional
    public DirectOnboardingDtos.DirectOnboardingResponse directOnboard(
            DirectOnboardingDtos.DirectOnboardingRequest req,
            MultipartFile resume,
            Map<String, MultipartFile> documentFiles,
            User caller) {

        requireErmOrSuperAdmin(caller);
        if (req == null) throw new BadRequestException("request body is required");

        // ── Step 1 — Validate + normalise personal input ─────────────────────
        String email = requireBlank(req.email(), "email").trim().toLowerCase(Locale.ROOT);
        if (!email.contains("@")) throw new BadRequestException("email must be a valid email address");
        String fullName = requireBlank(req.fullName(), "fullName").trim();
        String legalName = req.legalName() != null && !req.legalName().isBlank()
                ? req.legalName().trim() : fullName;
        String phone = req.phoneNumber() != null ? req.phoneNumber().trim() : null;
        if (req.joiningDate() == null) {
            throw new BadRequestException("joiningDate is required");
        }
        if (resume == null || resume.isEmpty()) {
            throw new BadRequestException("resume file is required");
        }

        // Email uniqueness — pre-check so we return a clean 409 with a
        // useful message instead of letting the users.email UNIQUE
        // constraint surface as a raw 500.
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException(
                    "A user with email " + email + " already exists. Direct onboarding is for "
                            + "brand-new pre-platform employees; if this person already has an "
                            + "account, edit them from the Users page instead.");
        }

        // ── Step 2 — Resolve the StaffingEntity (required for engagement) ────
        StaffingEntity entity = resolveEntity(req.entityId());

        // ── Step 3 — Validate document keys against the admin catalog ────────
        List<ResolvedDocument> resolvedDocs = resolveDocuments(req.documents(), documentFiles);

        // ── Step 4 — Create the User row ─────────────────────────────────────
        Instant now = Instant.now();
        User intern = User.builder()
                .email(email)
                .fullName(fullName)
                .phoneNumber(phone)
                .roles(EnumSet.of(UserRole.INTERN))
                .active(true)
                // Employer vouches for the address; no email-verify hoop.
                .emailVerified(true)
                // Null hash — the intern gets a password via mailbox
                // provisioning (if the ERM opted in on the wizard) or via
                // the existing AssignCompanyEmailDialog later. This matches
                // the staff-activation-link contract already documented on
                // User.passwordHash.
                .passwordHash(null)
                .mustChangePassword(false)
                .applicantId(applicantIdGenerator.nextApplicantId())
                .applicantIdCreatedAt(now)
                // Stamp ToS acceptance with a distinct version so the
                // audit trail shows this was an employer-registered path
                // rather than a candidate-self-accept.
                .tosAcceptedAt(now)
                .tosVersion("EMPLOYER_REGISTERED")
                .lifecycleStatus(InternLifecycleStatus.REGISTERED)
                .build();
        intern = userRepository.save(intern);

        // ── Step 5 — Candidate shell ────────────────────────────────────────
        WorkAuthTrack track = mapTrackFromWorkAuthType(req.workAuthType());
        // existsBy guard mirrors the once-registered edge case: a User just
        // created can't already have a Candidate row, but the pre-check
        // keeps this future-safe if the flow is ever re-entered.
        final User internForLambda = intern;
        Candidate candidate = candidateRepository.findByUserId(intern.getId()).orElseGet(() -> {
            Candidate c = Candidate.builder()
                    .user(internForLambda)
                    .legalName(legalName)
                    .expectedTrack(track)
                    .authorizedToWork(Boolean.TRUE)
                    .build();
            return candidateRepository.save(c);
        });

        // ── Step 6 — Mint employee id + InternLifecycle ─────────────────────
        String employeeId = employeeIdGenerator.nextEmployeeId();
        intern.setEmployeeId(employeeId);
        userRepository.save(intern);

        // Advance the lifecycle status one legal transition at a time so
        // the InternLifecycleService.advance regression guard doesn't
        // reject us. REGISTERED → EMPLOYEE_ID_CREATED is legal.
        internLifecycleService.advance(intern,
                InternLifecycleStatus.EMPLOYEE_ID_CREATED, caller.getId());

        if (internLifecycleRepository.existsByUserId(intern.getId())) {
            throw new ConflictException(
                    "An InternLifecycle already exists for user " + intern.getId()
                            + " — this direct-onboarding flow is for brand-new employees.");
        }
        if (internLifecycleRepository.findByEmployeeId(employeeId).isPresent()) {
            // The sequence is atomic so this is nearly impossible, but the
            // employee_id UNIQUE would still throw a raw 500 if it ever
            // slipped through; surface a clean 409 instead.
            throw new ConflictException(
                    "Generated employee id " + employeeId
                            + " collides with an existing lifecycle row.");
        }

        InternLifecycle lc = InternLifecycle.builder()
                .userId(intern.getId())
                .employeeId(employeeId)
                .ermId(caller.getId())
                .activeStatus("PROSPECTIVE")
                .hiredAt(now)
                .joiningDate(req.joiningDate())
                .trainerId(req.trainerUserId())
                .evaluatorId(req.evaluatorUserId())
                .managerId(req.managerUserId())
                .build();
        // Auto-link fills any of trainer/evaluator that the ERM left null;
        // explicit request-level overrides stay put because the linker
        // preserves existing non-null assignments.
        reportingStructureAutoLinker.apply(lc, caller.getId(), now);
        // Independent of auto-link — if all three roles are populated (whether
        // by request overrides, auto-link, or a mix), stamp the
        // reporting-structure-complete markers so the downstream new-hire
        // gates release.
        if (lc.getTrainerId() != null && lc.getEvaluatorId() != null
                && lc.getManagerId() != null
                && !Boolean.TRUE.equals(lc.getReportingStructureComplete())) {
            lc.setReportingStructureComplete(Boolean.TRUE);
            lc.setReportingStructureCompletedAt(now);
            lc.setReportingStructureCompletedById(caller.getId());
        }
        lc = internLifecycleRepository.save(lc);

        // ── Step 7 — Engagement (nullable app + offer FKs) ──────────────────
        engagementService.createForDirectHire(intern, candidate, entity, track,
                req.joiningDate(), caller);

        // ── Step 8 — Seed WorkAuthorizationRecord (same upsert shape as
        //             ErmComplianceService.updateWorkAuth) ──────────────────
        upsertWorkAuth(intern.getId(), req, caller);

        // ── Step 9 — Documents: packet + tasks (all ACCEPTED, ERM-provided) ──
        UUID resumeDocId = writeResume(intern.getId(), resume, caller.getId());
        candidate.setDefaultResumeId(resumeDocId);
        candidateRepository.save(candidate);

        DocumentPacket packet = DocumentPacket.builder()
                .internLifecycleId(lc.getId())
                .assignedById(caller.getId())
                .status("COMPLETED")
                .assignedAt(now)
                .allSubmittedAt(now)
                .completedAt(now)
                .customInstructions("Assembled by ERM during direct onboarding on "
                        + now + ".")
                .build();
        packet = documentPacketRepository.save(packet);
        for (ResolvedDocument doc : resolvedDocs) {
            Document stored = documentVaultService.saveDocument(
                    intern.getId(),
                    doc.file.getOriginalFilename(),
                    doc.file.getContentType(),
                    readBytes(doc.file),
                    doc.template.getCategory(),
                    doc.template.getSensitivity(),
                    caller.getId());
            DocumentTask task = DocumentTask.builder()
                    .packetId(packet.getId())
                    .documentKey(doc.template.getKey())
                    .status("ACCEPTED")
                    .uploadedFileId(stored.getId())
                    .submittedAt(now)
                    .reviewedAt(now)
                    .reviewedById(caller.getId())
                    .reviewComments("ERM-provided at direct onboarding.")
                    .version(1)
                    .build();
            documentTaskRepository.save(task);
        }

        // ── Step 10 — Inline activation ─────────────────────────────────────
        // Walk the lifecycle up to ONBOARDING_ACCEPTED so the transition
        // to ACTIVE_INTERN is legal under the standard state machine
        // (both steps are otherwise skipped for direct-hires; keeping the
        // stamps preserves audit continuity with the funnel path).
        internLifecycleService.advance(intern,
                InternLifecycleStatus.ONBOARDING_ASSIGNED, caller.getId());
        internLifecycleService.advance(intern,
                InternLifecycleStatus.ONBOARDING_ACCEPTED, caller.getId());
        internLifecycleService.advance(intern,
                InternLifecycleStatus.ACTIVE_INTERN, caller.getId());

        lc.setActiveStatus("ACTIVE");
        lc.setStartedAt(now); // ← month anchoring: current month begins here.
        InternLifecycle activeLc = internLifecycleRepository.save(lc);
        try {
            eventPublisher.publishEvent(
                    new InternActivatedEvent(intern.getId(), activeLc.getId()));
        } catch (Exception e) {
            log.warn("[DirectOnboard] InternActivatedEvent publish failed (non-fatal): {}",
                    e.getMessage());
        }

        // ── Step 11 — Audit ─────────────────────────────────────────────────
        writeAudit(intern, activeLc, caller, resolvedDocs.size());

        log.info("[DirectOnboard] activated user={} employee_id={} lifecycle={} "
                        + "entity={} docs={} joiningDate={} startedAt={}",
                intern.getId(), employeeId, activeLc.getId(), entity.getId(),
                resolvedDocs.size(), req.joiningDate(), now);

        return new DirectOnboardingDtos.DirectOnboardingResponse(
                intern.getId(),
                activeLc.getId(),
                employeeId,
                intern.getApplicantId(),
                intern.getEmail(),
                null, // companyEmail — populated by the controller if mailbox is provisioned
                Boolean.FALSE,
                null,
                now,
                "/careers/erm/active-interns/" + activeLc.getId());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void requireErmOrSuperAdmin(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        if (caller.getRoles() == null
                || (!caller.getRoles().contains(UserRole.ERM)
                        && !caller.getRoles().contains(UserRole.SUPER_ADMIN))) {
            throw new ForbiddenException("ERM or SUPER_ADMIN role required for direct onboarding");
        }
    }

    private static String requireBlank(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new BadRequestException(field + " is required");
        }
        return v;
    }

    private StaffingEntity resolveEntity(UUID entityId) {
        if (entityId != null) {
            return staffingEntityRepository.findById(entityId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "StaffingEntity not found: " + entityId));
        }
        // Fallback — use the first ACTIVE entity. The single-tenant deploys
        // this feature ships to have exactly one, so this is the natural
        // default; multi-entity deploys must pass entityId explicitly.
        List<StaffingEntity> all = staffingEntityRepository.findAll();
        return all.stream()
                .filter(e -> Boolean.TRUE.equals(e.getIsActive()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "No active StaffingEntity configured — create one via "
                                + "the Admin > Entities screen before direct-onboarding, "
                                + "or pass an explicit entityId."));
    }

    /** Map the wizard's WorkAuthType (which mirrors the compliance-modal
     *  enum values) to the {@link WorkAuthTrack} used on Engagement /
     *  Candidate. */
    private static WorkAuthTrack mapTrackFromWorkAuthType(String workAuthType) {
        if (workAuthType == null) return WorkAuthTrack.OTHER;
        return switch (workAuthType.trim().toUpperCase(Locale.ROOT)) {
            case "US_CITIZEN", "PERMANENT_RESIDENT" -> WorkAuthTrack.CITIZEN;
            case "F1_CPT" -> WorkAuthTrack.CPT;
            case "F1_OPT" -> WorkAuthTrack.OPT;
            case "F1_STEM_OPT" -> WorkAuthTrack.STEM_OPT;
            default -> WorkAuthTrack.OTHER;
        };
    }

    private List<ResolvedDocument> resolveDocuments(
            List<DirectOnboardingDtos.DocumentAssignment> requested,
            Map<String, MultipartFile> files) {
        List<ResolvedDocument> resolved = new ArrayList<>();
        if (requested == null || requested.isEmpty()) return resolved;
        for (DirectOnboardingDtos.DocumentAssignment a : requested) {
            if (a.documentKey() == null || a.documentKey().isBlank()) {
                throw new BadRequestException("documents[].documentKey is required");
            }
            if (a.formPartName() == null || a.formPartName().isBlank()) {
                throw new BadRequestException(
                        "documents[].formPartName is required for key " + a.documentKey());
            }
            MultipartFile file = files == null ? null : files.get(a.formPartName());
            if (file == null || file.isEmpty()) {
                throw new BadRequestException(
                        "missing file for document key " + a.documentKey()
                                + " (expected multipart part '" + a.formPartName() + "')");
            }
            OnboardingDocumentTemplate template = templateRepository
                    .findByKey(a.documentKey())
                    .orElseThrow(() -> new BadRequestException(
                            "unknown documentKey: " + a.documentKey()));
            if (!template.isActive()) {
                throw new BadRequestException(
                        "document template " + a.documentKey() + " is not active");
            }
            resolved.add(new ResolvedDocument(template, file));
        }
        return resolved;
    }

    private void upsertWorkAuth(UUID userId,
                                DirectOnboardingDtos.DirectOnboardingRequest req,
                                User caller) {
        WorkAuthorizationRecord existing = workAuthRepository.findByUserId(userId).orElse(null);
        String workAuthType = req.workAuthType() != null && !req.workAuthType().isBlank()
                ? req.workAuthType().trim().toUpperCase(Locale.ROOT) : "OTHER";
        WorkAuthorizationRecord w = existing != null
                ? existing
                : WorkAuthorizationRecord.builder()
                        .userId(userId)
                        .workAuthType(workAuthType)
                        .i983Required(Boolean.TRUE.equals(req.i983Required()))
                        .lastUpdatedById(caller.getId())
                        .build();
        w.setWorkAuthType(workAuthType);
        if (req.authorizedFrom() != null) w.setAuthorizedFrom(req.authorizedFrom());
        if (req.authorizedUntil() != null) w.setAuthorizedUntil(req.authorizedUntil());
        if (req.eadCardNumber() != null && !req.eadCardNumber().isBlank()) {
            w.setEadCardNumber(req.eadCardNumber().trim());
        }
        if (req.eadExpiration() != null) w.setEadExpiration(req.eadExpiration());
        if (req.i20Expiration() != null) w.setI20Expiration(req.i20Expiration());
        if (req.i983Required() != null) w.setI983Required(req.i983Required());
        if (req.dsoName() != null && !req.dsoName().isBlank()) w.setDsoName(req.dsoName().trim());
        if (req.dsoEmail() != null && !req.dsoEmail().isBlank()) w.setDsoEmail(req.dsoEmail().trim());
        if (req.dsoPhone() != null && !req.dsoPhone().isBlank()) w.setDsoPhone(req.dsoPhone().trim());
        if (req.workAuthNotes() != null && !req.workAuthNotes().isBlank()) {
            w.setErmNotes(req.workAuthNotes().trim());
        }
        w.setLastUpdatedById(caller.getId());
        workAuthRepository.save(w);
    }

    private UUID writeResume(UUID ownerUserId, MultipartFile resume, UUID uploaderId) {
        Document stored = documentVaultService.saveDocument(
                ownerUserId,
                resume.getOriginalFilename(),
                resume.getContentType(),
                readBytes(resume),
                "RESUME",
                DocumentVaultService.defaultSensitivityForCategory("RESUME"),
                uploaderId);
        return stored.getId();
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (java.io.IOException e) {
            throw new BadRequestException("Could not read file bytes for "
                    + file.getOriginalFilename() + ": " + e.getMessage());
        }
    }

    private void writeAudit(User intern, InternLifecycle lc, User caller, int documentCount) {
        try {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("userId", intern.getId());
            after.put("email", intern.getEmail());
            after.put("employeeId", lc.getEmployeeId());
            after.put("internLifecycleId", lc.getId());
            after.put("startedAt", lc.getStartedAt());
            after.put("joiningDate", lc.getJoiningDate());
            after.put("trainerId", lc.getTrainerId());
            after.put("evaluatorId", lc.getEvaluatorId());
            after.put("managerId", lc.getManagerId());
            after.put("documentCount", documentCount);
            AuditLog entry = AuditLog.builder()
                    .entityType("User")
                    .entityId(intern.getId())
                    .action("DIRECT_ONBOARDED")
                    .userId(caller.getId())
                    .subjectUserId(intern.getId())
                    .afterJson(objectMapper.writeValueAsString(after))
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("[DirectOnboard] audit write failed: {}", e.getMessage());
        }
    }

    private record ResolvedDocument(OnboardingDocumentTemplate template, MultipartFile file) {}
}
