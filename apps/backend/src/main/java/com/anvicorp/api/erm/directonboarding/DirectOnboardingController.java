package com.anvicorp.api.erm.directonboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.mail.service.CareersMailProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ERM Direct Onboarding — the endpoint the wizard POSTs to. Accepts
 * {@code multipart/form-data} with:
 * <ul>
 *   <li>a {@code metadata} JSON part carrying the
 *       {@link DirectOnboardingDtos.DirectOnboardingRequest} body;</li>
 *   <li>a {@code resume} file part (required);</li>
 *   <li>zero or more {@code doc_<documentKey>} file parts, one per
 *       entry in {@code metadata.documents[]}.</li>
 * </ul>
 *
 * <p>RBAC: {@code hasAnyRole('ERM','SUPER_ADMIN')} — same allowlist the
 * rest of the ERM surface enforces via {@code ErmComplianceService.requireErm}.</p>
 *
 * <p>The core write happens inside
 * {@link DirectOnboardingService#directOnboard} (one {@code @Transactional}
 * block). Mailbox provisioning is invoked AFTER that call returns so a
 * mailbox failure never rolls back the successful intern create — the
 * ERM can still assign a mailbox later via the existing
 * {@code AssignCompanyEmailDialog}. Same pattern
 * {@code AdminUserService.create} uses for the credentials-email fallback.</p>
 */
@RestController
@RequestMapping("/api/v1/erm/direct-onboarding")
@RequiredArgsConstructor
@Slf4j
public class DirectOnboardingController {

    private final DirectOnboardingService directOnboardingService;
    private final CareersMailProvisioningService mailProvisioningService;
    private final ObjectMapper objectMapper;

    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ResponseEntity<DirectOnboardingDtos.DirectOnboardingResponse> create(
            @RequestParam("metadata") String metadataJson,
            @RequestParam("resume") MultipartFile resume,
            MultipartHttpServletRequest multipart,
            @AuthenticationPrincipal User caller) {

        DirectOnboardingDtos.DirectOnboardingRequest req;
        try {
            req = objectMapper.readValue(metadataJson,
                    DirectOnboardingDtos.DirectOnboardingRequest.class);
        } catch (Exception e) {
            throw new BadRequestException(
                    "Could not parse metadata JSON: " + e.getMessage());
        }

        // Harvest every doc_<key> file part into a name→file map. The
        // service resolves the mapping against req.documents[].formPartName
        // so the client stays free to name the parts however it likes as
        // long as the metadata references match.
        Map<String, MultipartFile> documentFiles = new HashMap<>();
        Map<String, List<MultipartFile>> allParts = multipart.getMultiFileMap();
        allParts.forEach((partName, files) -> {
            if ("metadata".equals(partName) || "resume".equals(partName)) return;
            if (files == null || files.isEmpty()) return;
            documentFiles.put(partName, files.get(0));
        });

        DirectOnboardingDtos.DirectOnboardingResponse created =
                directOnboardingService.directOnboard(req, resume, documentFiles, caller);

        // Post-commit mailbox provisioning — deliberately outside the
        // create transaction so a mailbox failure does NOT roll back
        // the successful intern create. Mirrors the AdminUserService
        // credentials-email failure model.
        Boolean assignMailbox = req.assignMailboxNow();
        if (Boolean.TRUE.equals(assignMailbox)) {
            try {
                CareersMailProvisioningService.ProvisionResult result =
                        mailProvisioningService.provisionForIntern(
                                created.userId(),
                                req.mailboxLocalPart(),
                                req.mailboxStartingPassword(),
                                caller);
                DirectOnboardingDtos.DirectOnboardingResponse augmented =
                        new DirectOnboardingDtos.DirectOnboardingResponse(
                                created.userId(),
                                created.internLifecycleId(),
                                created.employeeId(),
                                created.applicantId(),
                                result.companyEmail(),
                                result.companyEmail(),
                                Boolean.TRUE,
                                result.credentialsEmailSent(),
                                created.activatedAt(),
                                created.activeInternDetailPath());
                return ResponseEntity.ok(augmented);
            } catch (Exception e) {
                // The intern is created + active; only the mailbox side
                // failed. Return the create result unchanged with a
                // logged WARN so the ERM sees the success banner + can
                // retry mailbox from the new-hire detail screen.
                log.warn("[DirectOnboard] mailbox provisioning failed post-create "
                                + "for user {} — intern is active, ERM must retry mailbox "
                                + "assignment via AssignCompanyEmailDialog: {}",
                        created.userId(), e.getMessage(), e);
                DirectOnboardingDtos.DirectOnboardingResponse augmented =
                        new DirectOnboardingDtos.DirectOnboardingResponse(
                                created.userId(),
                                created.internLifecycleId(),
                                created.employeeId(),
                                created.applicantId(),
                                created.email(),
                                null,
                                Boolean.FALSE,
                                Boolean.FALSE,
                                created.activatedAt(),
                                created.activeInternDetailPath());
                return ResponseEntity.ok(augmented);
            }
        }
        return ResponseEntity.ok(created);
    }

}
