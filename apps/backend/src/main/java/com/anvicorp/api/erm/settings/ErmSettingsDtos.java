package com.anvicorp.api.erm.settings;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ERM Phase 7 — Settings DTO surface. */
public final class ErmSettingsDtos {

    private ErmSettingsDtos() {}

    public record TemplateRow(
            String key,
            String channel,
            String subjectTemplate,
            String bodyTemplate,
            String variablesCsv,
            Boolean active,
            Instant updatedAt,
            UUID updatedById,
            String category                 // grouping label inferred from key prefix
    ) {}

    public record TemplateUpdateRequest(
            String subjectTemplate,
            String bodyTemplate,
            Boolean active
    ) {}

    public record TemplatePreviewRequest(Map<String, Object> variables) {}

    public record TemplatePreviewResponse(String subject, String body) {}

    public record ReasonCodeOption(
            String code, String label, boolean requiresFreeText) {}

    public record ReasonCodeGroup(
            String category,
            List<ReasonCodeOption> options) {}

    public record WorkloadRow(
            UUID ermUserId,
            String ermName,
            String ermEmail,
            long activeInterns,
            long applicationsOwned,
            long offersCreated,
            long openExceptions
    ) {}
}
