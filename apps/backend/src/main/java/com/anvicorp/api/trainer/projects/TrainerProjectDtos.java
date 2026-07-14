package com.anvicorp.api.trainer.projects;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Trainer Phase 2 — DTO surface for the doc §7 Project Assignment
 *  wizard + the Project Templates catalog. */
public final class TrainerProjectDtos {

    private TrainerProjectDtos() {}

    // ── Project assignment ─────────────────────────────────────────────────

    /** Doc §7 — the 11 fields the trainer fills on the wizard. The optional
     *  {@code projectTemplateId} flags template instantiation; the wizard
     *  prefills the rest of the body from the template before submit. */
    public record AssignProjectRequest(
            UUID internLifecycleId,
            String monthYear,           // YYYY-MM
            Short projectNumber,        // 1 | 2
            String title,
            String technologyArea,
            String secondaryTag,
            String instructions,        // Markdown, max 20000
            String githubInstructions,  // required when usesGithub
            Boolean usesGithub,
            LocalDate dueDate,
            String learningObjectiveLabel,
            Short i983ObjectiveIndex,
            Boolean notifyStakeholdersInternal,
            // Backdating (required when monthYear < current month)
            String backdateAuthorizedByName,
            String backdateReason,
            // Template instantiation
            UUID projectTemplateId
    ) {}

    /** Slot status indicator the wizard polls when the user picks
     *  (intern, monthYear). Used to grey-out the slot radio + show the
     *  occupant's title; service-layer 409 catches anything raced past
     *  the UI gate. */
    public record SlotStatusResponse(
            String monthYear,
            boolean slot1Taken,
            String slot1Title,
            UUID slot1ProjectId,
            boolean slot2Taken,
            String slot2Title,
            UUID slot2ProjectId,
            boolean bothTaken,
            boolean backdatingRequired
    ) {}

    public record ProjectDetail(
            UUID id,
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String monthYear,
            Short projectNumber,
            String title,
            String technologyArea,
            String secondaryTag,
            String instructions,
            String githubInstructions,
            LocalDate dueDate,
            String learningObjectiveLabel,
            Short i983ObjectiveIndex,
            String status,
            Boolean notifyStakeholdersInternal,
            UUID assignedById,
            String assignedByName,
            UUID projectTemplateId,
            UUID projectFileId,         // attached project_file (optional)
            String projectFileName,
            String backdateAuthorizedBy,
            String backdateReason,
            Instant backdateAuthorizedAt,
            Instant createdAt,
            Instant updatedAt
    ) {}

    // ── Project Templates catalog ──────────────────────────────────────────

    public record TemplateRow(
            UUID id,
            String title,
            String technologyArea,
            String description,
            boolean published,
            Instant publishedAt,
            int usageCount,
            boolean archived,
            Instant archivedAt,
            UUID createdById,
            String createdByName,
            Instant createdAt,
            Instant updatedAt,
            int attachmentCount
    ) {}

    public record TemplateListPage(
            List<TemplateRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}

    public record TemplateAttachment(
            UUID documentId,
            String fileName,
            String mimeType,
            Long fileSize
    ) {}

    public record TemplateDetail(
            UUID id,
            String title,
            String technologyArea,
            String description,
            String instructionsMd,
            String githubInstructionsMd,
            String learningObjectiveLabel,
            boolean published,
            Instant publishedAt,
            int usageCount,
            boolean archived,
            Instant archivedAt,
            UUID createdById,
            String createdByName,
            Instant createdAt,
            Instant updatedAt,
            List<TemplateAttachment> attachments
    ) {}

    public record CreateTemplateRequest(
            String title,
            String technologyArea,
            String description,
            String instructionsMd,
            String githubInstructionsMd,
            String learningObjectiveLabel,
            Boolean publish      // when true, publish immediately on create
    ) {}

    public record UpdateTemplateRequest(
            String title,
            String technologyArea,
            String description,
            String instructionsMd,
            String githubInstructionsMd,
            String learningObjectiveLabel
    ) {}
}
