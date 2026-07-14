package com.anvicorp.api.workspace.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Workspace-file response. The {@code content} field is populated for
 * single-file fetches and omitted (null) on directory listings to keep the
 * tree payload small.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkspaceFileResponse(
        UUID id,
        UUID projectId,
        String path,
        Long sizeBytes,
        Instant lastModifiedAt,
        UUID lastModifiedBy,
        String content
) {}
