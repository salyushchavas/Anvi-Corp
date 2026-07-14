package com.anvicorp.api.dto.common;

import lombok.*;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Generic paged DTO returned by all list endpoints. We never serialize Spring's
 * {@code PageImpl} directly: Spring Boot 3 logs a "Serializing PageImpl instances
 * as-is is not supported" warning on every call, and its JSON shape can shift
 * between versions — see PRODUCT.md §6.
 *
 * Field shape mirrors the audit-log paged DTO from D2 for cross-endpoint
 * consistency: {@code content, page, size, totalElements, totalPages}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PagedResponse<T> {

    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    /**
     * Wraps a Spring {@code Page} directly when the page already holds DTOs.
     * For entity pages, prefer {@link #of(Page, Function)} so the service can
     * apply its own DTO mapper.
     */
    public static <T> PagedResponse<T> of(Page<T> page) {
        return PagedResponse.<T>builder()
                .content(page.getContent())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .build();
    }

    /**
     * Wraps a Spring {@code Page<E>} of entities, mapping each entity through
     * {@code mapper} into a response DTO.
     */
    public static <E, T> PagedResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return PagedResponse.<T>builder()
                .content(page.map(mapper).getContent())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .build();
    }
}
