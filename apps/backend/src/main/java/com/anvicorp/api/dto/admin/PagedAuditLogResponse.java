package com.anvicorp.api.dto.admin;

import lombok.*;

import java.util.List;

/**
 * Custom paged DTO for the admin audit log viewer. We hand-roll the shape
 * instead of returning Spring's {@code PageImpl} because PageImpl is not
 * a stable serialization contract (it warns on every boot in Spring Boot 3
 * and its JSON shape can change between versions) — see PRODUCT.md §6.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PagedAuditLogResponse {
    private List<AuditLogEntryResponse> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}
