package com.anvicorp.api.service;

import com.anvicorp.api.dto.documents.DocumentRecordResponse;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.I983Plan;
import com.anvicorp.api.entity.I9Form;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.Resume;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.DocumentType;
import com.anvicorp.api.enums.I983Status;
import com.anvicorp.api.enums.I9Status;
import com.anvicorp.api.enums.OfferStatus;
import com.anvicorp.api.repository.I983PlanRepository;
import com.anvicorp.api.repository.I9FormRepository;
import com.anvicorp.api.repository.OfferRepository;
import com.anvicorp.api.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregates I-9, I-983, Offer, and Resume rows into a single unified
 * "documents" listing for HR's vault view. Reads in-memory for v1 demo scale;
 * tracked as tech debt for refactor to a DB-level UNION view once data grows.
 */
@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final Map<DocumentType, String> RETENTION_POLICIES = Map.of(
            DocumentType.I9,
            "Retain for 3 years after hire OR 1 year after termination, whichever is later",
            DocumentType.I983,
            "Retain for duration of STEM OPT + 3 years",
            DocumentType.OFFER,
            "Retain for duration of employment + 7 years",
            DocumentType.RESUME,
            "Retain for 2 years from upload OR duration of employment + 1 year, whichever is longer"
    );

    private final I9FormRepository i9FormRepository;
    private final I983PlanRepository i983PlanRepository;
    private final OfferRepository offerRepository;
    private final ResumeRepository resumeRepository;

    public record DocumentFilters(
            DocumentType type,
            UUID candidateId,
            String statusContains,
            String searchQuery,
            LocalDate fromDate,
            LocalDate toDate,
            String sortField
    ) {}

    @Transactional(readOnly = true)
    public Page<DocumentRecordResponse> listAll(DocumentFilters filters, Pageable pageable) {
        List<DocumentRecordResponse> all = new ArrayList<>();

        // I-9 forms
        for (I9Form f : i9FormRepository.findAll()) {
            all.add(mapI9(f));
        }
        // I-983 plans
        for (I983Plan p : i983PlanRepository.findAll()) {
            all.add(mapI983(p));
        }
        // Offers (exclude DRAFT)
        for (Offer o : offerRepository.findAll()) {
            if (o.getStatus() != OfferStatus.DRAFT) {
                all.add(mapOffer(o));
            }
        }
        // Resumes
        for (Resume r : resumeRepository.findAll()) {
            all.add(mapResume(r));
        }

        // Filters
        List<DocumentRecordResponse> filtered = all.stream()
                .filter(d -> filters.type() == null || d.getType() == filters.type())
                .filter(d -> filters.candidateId() == null
                        || filters.candidateId().equals(d.getCandidateId()))
                .filter(d -> {
                    String s = filters.statusContains();
                    if (s == null || s.isBlank()) return true;
                    String label = d.getStatusLabel() != null
                            ? d.getStatusLabel().toLowerCase()
                            : "";
                    return label.contains(s.toLowerCase());
                })
                .filter(d -> {
                    String q = filters.searchQuery();
                    if (q == null || q.isBlank()) return true;
                    String lower = q.toLowerCase();
                    return (d.getTitle() != null
                            && d.getTitle().toLowerCase().contains(lower))
                            || (d.getCandidateName() != null
                                    && d.getCandidateName().toLowerCase().contains(lower));
                })
                .filter(d -> {
                    if (filters.fromDate() == null) return true;
                    Instant cutoff = filters.fromDate().atStartOfDay(java.time.ZoneId.systemDefault())
                            .toInstant();
                    return d.getCreatedAt() != null && !d.getCreatedAt().isBefore(cutoff);
                })
                .filter(d -> {
                    if (filters.toDate() == null) return true;
                    Instant cutoff = filters.toDate().plusDays(1)
                            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
                    return d.getCreatedAt() != null && d.getCreatedAt().isBefore(cutoff);
                })
                .toList();

        // Sort
        Comparator<DocumentRecordResponse> cmp = chooseComparator(filters.sortField());
        List<DocumentRecordResponse> sorted = new ArrayList<>(filtered);
        sorted.sort(cmp);

        // Paginate
        int from = (int) Math.min(pageable.getOffset(), sorted.size());
        int to = Math.min(from + pageable.getPageSize(), sorted.size());
        List<DocumentRecordResponse> page = sorted.subList(from, to);
        return new PageImpl<>(page, pageable, sorted.size());
    }

    private Comparator<DocumentRecordResponse> chooseComparator(String sortField) {
        return switch (sortField == null ? "updatedAt" : sortField) {
            case "createdAt" -> Comparator.comparing(
                    DocumentRecordResponse::getCreatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case "type" -> Comparator.comparing(
                    DocumentRecordResponse::getType,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(
                    DocumentRecordResponse::getUpdatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
        };
    }

    // ── Per-type mappers ────────────────────────────────────────────────────

    private DocumentRecordResponse mapI9(I9Form f) {
        Candidate c = f.getCandidate();
        User u = c != null ? c.getUser() : null;
        I9Status s = f.getStatus();
        return DocumentRecordResponse.builder()
                .id(f.getId())
                .type(DocumentType.I9)
                .title("I-9 Form")
                .candidateId(c != null ? c.getId() : null)
                .candidateName(u != null ? u.getFullName() : null)
                .candidateEmail(u != null ? u.getEmail() : null)
                .status(s != null ? s.name() : null)
                .statusLabel(humanize(s))
                .statusColor(colorForI9(s))
                .createdAt(f.getCreatedAt())
                .updatedAt(f.getUpdatedAt())
                .retentionPolicyText(RETENTION_POLICIES.get(DocumentType.I9))
                .linkUrl("/careers/erm/i9-everify/i9/" + f.getId())
                .immutable(s == I9Status.COMPLETED)
                .hasAuditLog(true)
                .build();
    }

    private DocumentRecordResponse mapI983(I983Plan p) {
        Candidate c = p.getCandidate();
        User u = c != null ? c.getUser() : null;
        I983Status s = p.getStatus();
        return DocumentRecordResponse.builder()
                .id(p.getId())
                .type(DocumentType.I983)
                .title("I-983 Training Plan")
                .candidateId(c != null ? c.getId() : null)
                .candidateName(u != null ? u.getFullName() : null)
                .candidateEmail(u != null ? u.getEmail() : null)
                .entityName(p.getEntity() != null ? p.getEntity().getName() : null)
                .status(s != null ? s.name() : null)
                .statusLabel(humanizeI983(s))
                .statusColor(colorForI983(s))
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .retentionPolicyText(RETENTION_POLICIES.get(DocumentType.I983))
                .linkUrl("/careers/erm/training-plans/" + p.getId())
                .immutable(s == I983Status.COMPLETE
                        || s == I983Status.SUBMITTED_TO_DSO
                        || s == I983Status.DSO_APPROVED)
                .hasAuditLog(true)
                .build();
    }

    private DocumentRecordResponse mapOffer(Offer o) {
        var app = o.getApplication();
        Candidate c = app != null ? app.getCandidate() : null;
        User u = c != null ? c.getUser() : null;
        var posting = app != null ? app.getJobPosting() : null;
        var entity = posting != null ? posting.getEntity() : null;
        String title = "Offer Letter — "
                + (posting != null && posting.getTitle() != null
                        ? posting.getTitle()
                        : "Position");
        OfferStatus s = o.getStatus();
        return DocumentRecordResponse.builder()
                .id(o.getId())
                .type(DocumentType.OFFER)
                .title(title)
                .candidateId(c != null ? c.getId() : null)
                .candidateName(u != null ? u.getFullName() : null)
                .candidateEmail(u != null ? u.getEmail() : null)
                .entityName(entity != null ? entity.getName() : null)
                .status(s != null ? s.name() : null)
                .statusLabel(humanize(s))
                .statusColor(colorForOffer(s))
                .createdAt(o.getCreatedAt())
                .updatedAt(o.getUpdatedAt())
                .retentionPolicyText(RETENTION_POLICIES.get(DocumentType.OFFER))
                .linkUrl("/careers/erm/offers/" + o.getId())
                .immutable(s == OfferStatus.ACCEPTED)
                .hasAuditLog(false)
                .build();
    }

    private DocumentRecordResponse mapResume(Resume r) {
        Candidate c = r.getCandidate();
        User u = c != null ? c.getUser() : null;
        boolean isDefault = Boolean.TRUE.equals(r.getIsDefault());
        return DocumentRecordResponse.builder()
                .id(r.getId())
                .type(DocumentType.RESUME)
                .title("Resume: " + (r.getFileName() != null ? r.getFileName() : r.getId().toString()))
                .candidateId(c != null ? c.getId() : null)
                .candidateName(u != null ? u.getFullName() : null)
                .candidateEmail(u != null ? u.getEmail() : null)
                .status(isDefault ? "DEFAULT" : "STANDARD")
                .statusLabel(isDefault ? "Default Resume" : "Resume")
                .statusColor(isDefault ? "blue" : "gray")
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getCreatedAt()) // resumes have no separate updatedAt
                .retentionPolicyText(RETENTION_POLICIES.get(DocumentType.RESUME))
                // Resume rows trigger download via the dedicated endpoint; no in-app page.
                .linkUrl(null)
                .immutable(false)
                .hasAuditLog(false)
                .build();
    }

    // ── Color + label helpers ───────────────────────────────────────────────

    private static String colorForI9(I9Status s) {
        if (s == null) return "gray";
        return switch (s) {
            case NOT_STARTED -> "gray";
            // SECTION_1_COMPLETE is the legacy alias for SECTION_2_PENDING —
            // both render with the same amber "waiting on Section 2" treatment.
            case SECTION_2_PENDING, SECTION_1_COMPLETE -> "amber";
            case COMPLETED -> "green";
            case REOPENED -> "orange";
        };
    }

    private static String colorForI983(I983Status s) {
        if (s == null) return "gray";
        return switch (s) {
            case DRAFT -> "gray";
            case COMPLETE -> "blue";
            case SUBMITTED_TO_DSO -> "purple";
            case DSO_APPROVED -> "green";
            case DSO_REJECTED -> "red";
            case AMENDMENT_REQUESTED -> "amber";
        };
    }

    @SuppressWarnings("deprecation")
    private static String colorForOffer(OfferStatus s) {
        if (s == null) return "gray";
        return switch (s) {
            case DRAFT -> "gray";
            case SENT -> "blue";
            // SIGNED is the IDMS-completed terminal; ACCEPTED is the
            // legacy manual-accept equivalent.
            case SIGNED, ACCEPTED -> "green";
            case DECLINED -> "orange";
            case EXPIRED -> "amber";
            // VOIDED is the current term; REVOKED is the legacy alias.
            case VOIDED, REVOKED -> "red";
        };
    }

    private static String humanize(I9Status s) {
        if (s == null) return null;
        return switch (s) {
            case NOT_STARTED -> "Not Started";
            case SECTION_2_PENDING -> "Section 2 Pending";
            case SECTION_1_COMPLETE -> "Section 1 Complete";
            case COMPLETED -> "Completed";
            case REOPENED -> "Reopened";
        };
    }

    private static String humanizeI983(I983Status s) {
        if (s == null) return null;
        return switch (s) {
            case DRAFT -> "Draft";
            case COMPLETE -> "Complete";
            case SUBMITTED_TO_DSO -> "Submitted to DSO";
            case DSO_APPROVED -> "DSO Approved";
            case DSO_REJECTED -> "DSO Rejected";
            case AMENDMENT_REQUESTED -> "Amendment Requested";
        };
    }

    @SuppressWarnings("deprecation")
    private static String humanize(OfferStatus s) {
        if (s == null) return null;
        return switch (s) {
            case DRAFT -> "Draft";
            case SENT -> "Sent";
            case SIGNED -> "Signed";
            case ACCEPTED -> "Accepted";
            case DECLINED -> "Declined";
            case EXPIRED -> "Expired";
            case VOIDED -> "Voided";
            case REVOKED -> "Revoked";
        };
    }
}
