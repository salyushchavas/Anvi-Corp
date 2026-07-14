package com.anvicorp.api.trainer.history;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Trainer Phase 4 — DTOs for the read-only Feedback History page. */
public final class TrainerFeedbackHistoryDtos {

    private TrainerFeedbackHistoryDtos() {}

    public record HistoryRow(
            UUID submissionId,
            UUID projectId,
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String employeeId,
            String projectTitle,
            String technologyArea,
            String monthYear,
            Short projectNumber,
            int version,
            String trainerDecision,
            String completionStatus,
            Short technicalScore,
            Short communicationScore,
            Instant submittedAt,
            Instant reviewedAt
    ) {}

    public record HistoryPage(
            List<HistoryRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}

    public record HistoryDetail(
            UUID submissionId,
            UUID projectId,
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String employeeId,
            String projectTitle,
            String technologyArea,
            String projectInstructions,
            String monthYear,
            Short projectNumber,
            int version,
            // The original submission
            String description,
            String linksJson,
            Instant submittedAt,
            // The feedback we left
            String trainerDecision,
            String completionStatus,
            Short technicalScore,
            Short communicationScore,
            String trainerFeedback,
            String blockersNote,
            String nextAction,
            LocalDate nextActionDueDate,
            String reviewedLinksCsv,
            Instant reviewedAt,
            UUID reviewedById,
            String reviewedByName,
            // Project status now (might have changed since the review)
            String projectStatus,
            LocalDate projectDueDate,
            Instant projectCompletedAt
    ) {}

    public record InternTimeline(
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String employeeId,
            int totalFeedback,
            int acceptedCount,
            int revisionRequestedCount,
            int escalatedCount,
            int noActionYetCount,
            Double averageTechnicalScore,
            Double averageCommunicationScore,
            Instant latestReviewAt,
            List<HistoryRow> rows
    ) {}
}
