package com.anvicorp.api.workspace.api.dto;

/** {@code PUT /workspace/files/**} body — UTF-8 file content as a JSON string. */
public record WorkspaceFileRequest(String content) {}
