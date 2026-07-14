package com.anvicorp.api.judge0;

/**
 * Minimal language row surfaced to the frontend playground dropdown.
 * Mirrors the {@code id} + {@code name} fields Judge0's
 * {@code GET /languages} returns.
 */
public record LanguageResponse(int id, String name) {}
