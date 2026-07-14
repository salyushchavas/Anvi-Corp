package com.anvicorp.api.controller;

import com.anvicorp.api.dto.common.PagedResponse;
import com.anvicorp.api.dto.documents.DocumentRecordResponse;
import com.anvicorp.api.enums.DocumentType;
import com.anvicorp.api.service.DocumentService;
import com.anvicorp.api.service.DocumentService.DocumentFilters;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @GetMapping
    @PreAuthorize("hasRole('ERM')")
    public PagedResponse<DocumentRecordResponse> list(
            @RequestParam(required = false) DocumentType type,
            @RequestParam(required = false) UUID candidateId,
            @RequestParam(required = false) String statusContains,
            @RequestParam(required = false) String searchQuery,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            LocalDate fromDate,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            LocalDate toDate,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(500, Math.max(1, size))
        );
        DocumentFilters filters = new DocumentFilters(
                type, candidateId, statusContains, searchQuery, fromDate, toDate, sort
        );
        return PagedResponse.of(documentService.listAll(filters, pageable));
    }
}
