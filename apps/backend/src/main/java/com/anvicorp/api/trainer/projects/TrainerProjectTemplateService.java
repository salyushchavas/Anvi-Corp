package com.anvicorp.api.trainer.projects;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.ProjectTemplate;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.intern.DocumentVaultService;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.repository.ProjectTemplateRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.trainer.projects.TrainerProjectDtos.CreateTemplateRequest;
import com.anvicorp.api.trainer.projects.TrainerProjectDtos.TemplateAttachment;
import com.anvicorp.api.trainer.projects.TrainerProjectDtos.TemplateDetail;
import com.anvicorp.api.trainer.projects.TrainerProjectDtos.TemplateListPage;
import com.anvicorp.api.trainer.projects.TrainerProjectDtos.TemplateRow;
import com.anvicorp.api.trainer.projects.TrainerProjectDtos.UpdateTemplateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Trainer Phase 2 — Project Templates catalog (the doc §3 shared
 * blueprint library). Trainer, ERM, and SUPER_ADMIN can create + publish.
 * Non-author Trainers cannot edit each other's templates; ERM + SUPER_ADMIN
 * can edit anything.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrainerProjectTemplateService {

    private static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024;
    private static final Set<String> ALLOWED_ATTACHMENT_MIME = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream"
    );

    private final ProjectTemplateRepository repository;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final DocumentVaultService documentVault;
    private final ObjectMapper objectMapper;

    // ── List + read ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public TemplateListPage list(String technologyArea, String publishedFilter,
                                  String search, int page, int pageSize,
                                  User caller) {
        requireAuthor(caller);
        int p = Math.max(0, page);
        int ps = Math.min(100, Math.max(1, pageSize));

        List<ProjectTemplate> all = new ArrayList<>(repository.findAll());
        // Filters
        String q = search == null ? null : search.trim().toLowerCase();
        String tech = technologyArea == null || technologyArea.isBlank()
                ? null : technologyArea.trim();
        all.removeIf(t -> {
            if (tech != null && !tech.equalsIgnoreCase(t.getTechnologyArea())) return true;
            if (q != null && !q.isEmpty()
                    && !(t.getTitle() != null && t.getTitle().toLowerCase().contains(q))
                    && !(t.getDescription() != null
                            && t.getDescription().toLowerCase().contains(q))) {
                return true;
            }
            if ("PUBLISHED".equalsIgnoreCase(publishedFilter)
                    && (!Boolean.TRUE.equals(t.getPublished())
                            || t.getArchivedAt() != null)) {
                return true;
            }
            if ("DRAFT".equalsIgnoreCase(publishedFilter)
                    && (Boolean.TRUE.equals(t.getPublished())
                            || t.getArchivedAt() != null)) {
                return true;
            }
            if ("ARCHIVED".equalsIgnoreCase(publishedFilter)
                    && t.getArchivedAt() == null) return true;
            if (publishedFilter == null || publishedFilter.isBlank()
                    || "ALL".equalsIgnoreCase(publishedFilter)) {
                // default view hides archived
                return t.getArchivedAt() != null;
            }
            return false;
        });
        all.sort(Comparator
                .comparing(ProjectTemplate::getUpdatedAt, Comparator.reverseOrder()));

        int total = all.size();
        int from = Math.min(p * ps, total);
        int to = Math.min(from + ps, total);
        List<TemplateRow> rows = new ArrayList<>();
        for (ProjectTemplate t : all.subList(from, to)) {
            rows.add(toRow(t));
        }
        int totalPages = ps == 0 ? 0 : (int) Math.ceil((double) total / ps);
        return new TemplateListPage(rows, p, ps, total, totalPages);
    }

    @Transactional(readOnly = true)
    public TemplateDetail get(UUID id, User caller) {
        requireAuthor(caller);
        ProjectTemplate t = mustLoad(id);
        return toDetail(t);
    }

    /** Used by the wizard's "Use Template" picker — only published +
     *  non-archived templates appear, regardless of caller role. */
    @Transactional(readOnly = true)
    public List<TemplateRow> listPublishedForWizard(String technologyArea, User caller) {
        requireAuthor(caller);
        List<ProjectTemplate> list = (technologyArea == null || technologyArea.isBlank())
                ? repository.findByPublishedTrueAndArchivedAtIsNullOrderByTitleAsc()
                : repository
                    .findByTechnologyAreaAndPublishedTrueAndArchivedAtIsNullOrderByTitleAsc(
                            technologyArea.trim());
        List<TemplateRow> rows = new ArrayList<>();
        for (ProjectTemplate t : list) rows.add(toRow(t));
        return rows;
    }

    // ── Writes ────────────────────────────────────────────────────────────

    @Transactional
    public TemplateDetail create(CreateTemplateRequest req, User caller) {
        requireAuthor(caller);
        if (req == null) throw new BadRequestException("body required");
        if (req.title() == null || req.title().isBlank() || req.title().length() > 200) {
            throw new BadRequestException("title is required (1-200 chars)");
        }
        if (req.technologyArea() == null || req.technologyArea().isBlank()
                || req.technologyArea().length() > 100) {
            throw new BadRequestException("technologyArea is required (1-100 chars)");
        }
        if (req.instructionsMd() == null || req.instructionsMd().isBlank()) {
            throw new BadRequestException("instructionsMd is required");
        }
        ProjectTemplate t = ProjectTemplate.builder()
                .title(req.title().trim())
                .technologyArea(req.technologyArea().trim())
                .description(safeTrim(req.description()))
                .instructionsMd(req.instructionsMd())
                .githubInstructionsMd(safeTrim(req.githubInstructionsMd()))
                .learningObjectiveLabel(safeTrim(req.learningObjectiveLabel()))
                .createdById(caller.getId())
                .published(Boolean.TRUE.equals(req.publish()))
                .publishedAt(Boolean.TRUE.equals(req.publish()) ? Instant.now() : null)
                .usageCount(0)
                .attachedDocumentIds("[]")
                .build();
        t = repository.save(t);
        return toDetail(t);
    }

    @Transactional
    public TemplateDetail update(UUID id, UpdateTemplateRequest req, User caller) {
        ProjectTemplate t = mustLoad(id);
        requireEditAccess(t, caller);
        if (req.title() != null) {
            if (req.title().isBlank() || req.title().length() > 200) {
                throw new BadRequestException("title must be 1-200 chars");
            }
            t.setTitle(req.title().trim());
        }
        if (req.technologyArea() != null) {
            if (req.technologyArea().isBlank() || req.technologyArea().length() > 100) {
                throw new BadRequestException("technologyArea must be 1-100 chars");
            }
            t.setTechnologyArea(req.technologyArea().trim());
        }
        if (req.description() != null) t.setDescription(safeTrim(req.description()));
        if (req.instructionsMd() != null) {
            if (req.instructionsMd().isBlank()) {
                throw new BadRequestException("instructionsMd cannot be blank");
            }
            t.setInstructionsMd(req.instructionsMd());
        }
        if (req.githubInstructionsMd() != null) {
            t.setGithubInstructionsMd(safeTrim(req.githubInstructionsMd()));
        }
        if (req.learningObjectiveLabel() != null) {
            t.setLearningObjectiveLabel(safeTrim(req.learningObjectiveLabel()));
        }
        return toDetail(repository.save(t));
    }

    @Transactional
    public TemplateDetail publish(UUID id, User caller) {
        ProjectTemplate t = mustLoad(id);
        requireEditAccess(t, caller);
        if (t.getArchivedAt() != null) {
            throw new BadRequestException("Archived templates cannot be published");
        }
        t.setPublished(true);
        if (t.getPublishedAt() == null) t.setPublishedAt(Instant.now());
        return toDetail(repository.save(t));
    }

    @Transactional
    public TemplateDetail unpublish(UUID id, User caller) {
        ProjectTemplate t = mustLoad(id);
        requireEditAccess(t, caller);
        t.setPublished(false);
        return toDetail(repository.save(t));
    }

    @Transactional
    public TemplateDetail archive(UUID id, User caller) {
        ProjectTemplate t = mustLoad(id);
        requireEditAccess(t, caller);
        t.setArchivedAt(Instant.now());
        t.setPublished(false);
        return toDetail(repository.save(t));
    }

    @Transactional
    public TemplateDetail unarchive(UUID id, User caller) {
        ProjectTemplate t = mustLoad(id);
        requireEditAccess(t, caller);
        t.setArchivedAt(null);
        return toDetail(repository.save(t));
    }

    @Transactional
    public TemplateDetail duplicate(UUID id, User caller) {
        ProjectTemplate src = mustLoad(id);
        requireAuthor(caller);
        ProjectTemplate copy = ProjectTemplate.builder()
                .title(src.getTitle() + " (copy)")
                .technologyArea(src.getTechnologyArea())
                .description(src.getDescription())
                .instructionsMd(src.getInstructionsMd())
                .githubInstructionsMd(src.getGithubInstructionsMd())
                .learningObjectiveLabel(src.getLearningObjectiveLabel())
                .createdById(caller.getId())
                .published(false)
                .usageCount(0)
                .attachedDocumentIds("[]")
                .build();
        return toDetail(repository.save(copy));
    }

    // ── File attachments ──────────────────────────────────────────────────

    @Transactional
    public TemplateDetail attachFile(UUID id, MultipartFile file, User caller) {
        ProjectTemplate t = mustLoad(id);
        requireEditAccess(t, caller);
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("file is required");
        }
        if (file.getSize() > MAX_ATTACHMENT_BYTES) {
            throw new BadRequestException("Attachment exceeds 25 MB limit");
        }
        String mime = file.getContentType();
        if (mime != null && !ALLOWED_ATTACHMENT_MIME.contains(mime)) {
            throw new BadRequestException(
                    "Unsupported file type (PDF / DOCX / ZIP only). Got: " + mime);
        }
        try {
            Document doc = documentVault.saveDocument(
                    caller.getId(),
                    file.getOriginalFilename(),
                    mime != null ? mime : "application/octet-stream",
                    file.getBytes(),
                    "TEMPLATE_ATTACHMENT",
                    "GENERAL",
                    caller.getId());
            List<String> ids = parseAttachedIds(t.getAttachedDocumentIds());
            ids.add(doc.getId().toString());
            t.setAttachedDocumentIds(writeAttachedIds(ids));
            return toDetail(repository.save(t));
        } catch (BadRequestException re) {
            throw re;
        } catch (Exception e) {
            log.warn("[ProjectTemplate] attach failed: {}", e.getMessage());
            throw new RuntimeException("Attachment failed: " + e.getMessage(), e);
        }
    }

    @Transactional
    public TemplateDetail detachFile(UUID id, UUID documentId, User caller) {
        ProjectTemplate t = mustLoad(id);
        requireEditAccess(t, caller);
        List<String> ids = parseAttachedIds(t.getAttachedDocumentIds());
        if (!ids.remove(documentId.toString())) {
            throw new BadRequestException("Document not attached to this template");
        }
        t.setAttachedDocumentIds(writeAttachedIds(ids));
        return toDetail(repository.save(t));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void requireAuthor(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        if (caller.getRoles().contains(UserRole.SUPER_ADMIN)) return;
        if (caller.getRoles().contains(UserRole.TRAINER)) return;
        if (caller.getRoles().contains(UserRole.ERM)) return;
        throw new ForbiddenException("TRAINER / ERM / SUPER_ADMIN required");
    }

    private void requireEditAccess(ProjectTemplate t, User caller) {
        requireAuthor(caller);
        if (caller.getRoles().contains(UserRole.SUPER_ADMIN)) return;
        if (caller.getRoles().contains(UserRole.ERM)) return;
        if (t.getCreatedById() == null
                || !t.getCreatedById().equals(caller.getId())) {
            throw new ForbiddenException("Only the template author can edit this row.");
        }
    }

    private ProjectTemplate mustLoad(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ProjectTemplate not found: " + id));
    }

    private List<String> parseAttachedIds(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return new ArrayList<>(objectMapper.readValue(json,
                    new TypeReference<List<String>>() {}));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String writeAttachedIds(List<String> ids) {
        try { return objectMapper.writeValueAsString(ids); }
        catch (Exception e) { return "[]"; }
    }

    private TemplateRow toRow(ProjectTemplate t) {
        String createdByName = null;
        if (t.getCreatedById() != null) {
            createdByName = userRepository.findById(t.getCreatedById())
                    .map(User::getFullName).orElse(null);
        }
        int attachmentCount = parseAttachedIds(t.getAttachedDocumentIds()).size();
        return new TemplateRow(
                t.getId(), t.getTitle(), t.getTechnologyArea(), t.getDescription(),
                Boolean.TRUE.equals(t.getPublished()), t.getPublishedAt(),
                t.getUsageCount() == null ? 0 : t.getUsageCount(),
                t.getArchivedAt() != null, t.getArchivedAt(),
                t.getCreatedById(), createdByName,
                t.getCreatedAt(), t.getUpdatedAt(),
                attachmentCount);
    }

    private TemplateDetail toDetail(ProjectTemplate t) {
        String createdByName = null;
        if (t.getCreatedById() != null) {
            createdByName = userRepository.findById(t.getCreatedById())
                    .map(User::getFullName).orElse(null);
        }
        List<TemplateAttachment> attachments = new ArrayList<>();
        for (String idStr : parseAttachedIds(t.getAttachedDocumentIds())) {
            try {
                UUID id = UUID.fromString(idStr);
                Document doc = documentRepository.findById(id).orElse(null);
                if (doc != null) {
                    attachments.add(new TemplateAttachment(
                            doc.getId(), doc.getFileName(),
                            doc.getMimeType(), doc.getFileSize()));
                }
            } catch (Exception ignored) {}
        }
        return new TemplateDetail(
                t.getId(), t.getTitle(), t.getTechnologyArea(), t.getDescription(),
                t.getInstructionsMd(), t.getGithubInstructionsMd(),
                t.getLearningObjectiveLabel(),
                Boolean.TRUE.equals(t.getPublished()), t.getPublishedAt(),
                t.getUsageCount() == null ? 0 : t.getUsageCount(),
                t.getArchivedAt() != null, t.getArchivedAt(),
                t.getCreatedById(), createdByName,
                t.getCreatedAt(), t.getUpdatedAt(),
                attachments);
    }

    private static String safeTrim(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
