package com.anvicorp.api.mail.dto;

import java.util.List;

/** Minimal paged envelope (0-based page). total is the full match count. */
public record MailPage<T>(
        List<T> items,
        int page,
        int size,
        long total
) {
}
