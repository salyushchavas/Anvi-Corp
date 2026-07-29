package com.anvicorp.api.admin.onboardingtemplates;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DTOs for the Admin ⟶ Onboarding Documents page and the intern
 *  blank-template download endpoint. */
public final class OnboardingTemplateDtos {

    private OnboardingTemplateDtos() {}

    // ── Admin list + row ─────────────────────────────────────────────

    public record TemplateRow(
            UUID id,
            String key,
            String title,
            String category,
            String sensitivity,
            String description,
            /** TEMPLATE (admin-uploads-file, intern-downloads-fills-uploads)
             *  or NORMAL (no file, intern uploads their own scan). */
            String documentType,
            /** Present when the admin has uploaded a replacement file. */
            UUID currentDocumentId,
            /** File name of the current uploaded file, else null. */
            String currentFileName,
            /** Bytes of the current uploaded file, else null. */
            Long currentFileBytes,
            /** MIME type of the current uploaded file, else null. */
            String currentMimeType,
            /** Legacy public URL from the SkyzenDocument enum — shown as
             *  the fallback source when no upload has replaced it. */
            String legacyStaticUrl,
            boolean active,
            int sortOrder,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record TemplateListResponse(
            List<TemplateRow> items
    ) {}

    // ── Add + update payloads ────────────────────────────────────────

    public record CreateTemplateRequest(
            String key,
            String title,
            String category,
            String sensitivity,
            String description,
            /** TEMPLATE (default) or NORMAL — decides whether Replace
             *  is offered on the admin row and whether the intern sees a
             *  Download-template button on their packet task. */
            String documentType
    ) {}

    public record UpdateTemplateMetadataRequest(
            String title,
            String category,
            String sensitivity,
            String description,
            Integer sortOrder
    ) {}

    public record PresignUploadRequest(
            String fileName,
            String contentType,
            Long fileSize
    ) {}

    public record PresignUploadResponse(
            String uploadUrl,
            UUID documentId,
            String storageKey,
            Instant expiresAt
    ) {}

    public record AttachFileRequest(
            UUID documentId
    ) {}

    // ── Intern download resolution ────────────────────────────────────

    public record DownloadResolutionResponse(
            /** Either an S3 presigned URL (when the admin has uploaded a
             *  replacement) or the legacy static asset path (fallback). */
            String downloadUrl,
            /** S3 | STATIC — lets the frontend know whether to open with
             *  cache-buster or straight. */
            String source,
            /** Suggested filename for the download attachment. */
            String fileName,
            /** Expiry (S3 source only; null for static). */
            Instant expiresAt
    ) {}
}
