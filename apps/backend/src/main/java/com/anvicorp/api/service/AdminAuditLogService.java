package com.anvicorp.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.dto.admin.AuditLogEntryResponse;
import com.anvicorp.api.dto.admin.PagedAuditLogResponse;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.AuditLogSpecifications;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Audit-log read + CSV export for the SUPER_ADMIN viewer.
 *
 * <h2>Filters</h2>
 * <ul>
 *   <li>action — exact match</li>
 *   <li>actorSearch — case-insensitive substring on User.fullName / email</li>
 *   <li>actorRole — limits to users currently holding the given role</li>
 *   <li>entityType — exact match on AuditLog.entityType</li>
 *   <li>from / to — Instant bounds, inclusive</li>
 * </ul>
 *
 * <p>Actor-search and actor-role both narrow the actor user-id set passed
 * into the IN clause — they intersect when both are given.
 *
 * <h2>Read-only</h2>
 * The service writes EXACTLY one audit row, and only inside
 * {@link #exportCsv} — a meta-audit ({@code AUDIT_LOG_EXPORTED}) recording
 * who exported, when, and which filters they used. There are no other
 * write paths.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAuditLogService {

    private static final int DETAILS_MAX = 200;

    /**
     * Hard cap for the CSV export. Higher than the page size to make the
     * export useful, but bounded so a SUPER_ADMIN doesn't accidentally
     * stream a 10M-row download.
     */
    private static final int EXPORT_MAX_ROWS = 5_000;

    /**
     * Priority order used to pick a single "actor role" label for the CSV
     * export. SUPER_ADMIN first, candidate roles last.
     */
    private static final List<UserRole> ROLE_PRIORITY = List.of(
            UserRole.SUPER_ADMIN,
            UserRole.MANAGER,
            UserRole.ERM,
            UserRole.ERM,
            UserRole.TRAINER,
            UserRole.INTERN,
            UserRole.INTERN);

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /**
     * Per-user audit feed (SUPER_ADMIN L3) — paginated, filterable by action +
     * date range. The user-scope OR is built by the caller-supplied entity
     * buckets ({@link com.anvicorp.api.service.UserAuditEntityResolver}).
     */
    @Transactional(readOnly = true)
    public PagedAuditLogResponse userAudit(int page, int size, java.util.UUID userId,
                                           java.util.Map<String, java.util.Set<UUID>> entityIdsByType,
                                           String action, Instant from, Instant to) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "timestamp"));

        String normalizedAction = (action != null && !action.isBlank()) ? action.trim() : null;

        Page<AuditLog> resultPage = auditLogRepository.findAll(
                AuditLogSpecifications.forUserAuditFeed(
                        userId, entityIdsByType, normalizedAction, from, to),
                pageable);

        Map<UUID, String> actorNameById = lookupActorNames(resultPage.getContent());

        List<AuditLogEntryResponse> content = resultPage.getContent().stream()
                .map(a -> toResponse(a, actorNameById.get(a.getUserId())))
                .toList();

        return PagedAuditLogResponse.builder()
                .content(content)
                .page(resultPage.getNumber())
                .size(resultPage.getSize())
                .totalElements(resultPage.getTotalElements())
                .totalPages(resultPage.getTotalPages())
                .build();
    }

    @Transactional(readOnly = true)
    public PagedAuditLogResponse search(int page, int size, String action,
                                        String actorSearch, UserRole actorRole,
                                        String entityType, Instant from, Instant to) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "timestamp"));

        String normalizedAction = (action != null && !action.isBlank()) ? action.trim() : null;
        String normalizedEntityType = (entityType != null && !entityType.isBlank())
                ? entityType.trim() : null;

        Collection<UUID> userIds = resolveActorIds(actorSearch, actorRole);

        Page<AuditLog> resultPage = auditLogRepository.findAll(
                AuditLogSpecifications.withFilters(
                        normalizedAction, userIds, normalizedEntityType, from, to),
                pageable);

        Map<UUID, String> actorNameById = lookupActorNames(resultPage.getContent());

        List<AuditLogEntryResponse> content = resultPage.getContent().stream()
                .map(a -> toResponse(a, actorNameById.get(a.getUserId())))
                .toList();

        return PagedAuditLogResponse.builder()
                .content(content)
                .page(resultPage.getNumber())
                .size(resultPage.getSize())
                .totalElements(resultPage.getTotalElements())
                .totalPages(resultPage.getTotalPages())
                .build();
    }

    @Transactional(readOnly = true)
    public List<String> distinctActions() {
        List<String> actions = auditLogRepository.findDistinctActions();
        return actions != null ? actions : Collections.emptyList();
    }

    @Transactional(readOnly = true)
    public List<String> distinctEntityTypes() {
        List<String> rows = auditLogRepository.findDistinctEntityTypes();
        return rows != null ? rows : Collections.emptyList();
    }

    /**
     * CSV export of the audit log honouring the supplied filters. Writes a
     * meta-audit row ({@code AUDIT_LOG_EXPORTED}, entityType = {@code AuditLog},
     * userId = caller, afterJson = the filters) BEFORE returning bytes — so
     * even if the download itself fails downstream, the export attempt is
     * recorded.
     *
     * <p>Bounded to {@link #EXPORT_MAX_ROWS} rows. A truncation marker line
     * is appended when the cap is hit so the operator sees they got a slice.
     */
    @Transactional
    public byte[] exportCsv(String action, String actorSearch, UserRole actorRole,
                            String entityType, Instant from, Instant to, User caller) {
        String normalizedAction = (action != null && !action.isBlank()) ? action.trim() : null;
        String normalizedEntityType = (entityType != null && !entityType.isBlank())
                ? entityType.trim() : null;
        Collection<UUID> userIds = resolveActorIds(actorSearch, actorRole);

        // Meta-audit first — capture the export intent regardless of what the
        // result-set size turns out to be.
        writeExportAudit(caller, action, actorSearch, actorRole, entityType, from, to);

        Pageable pageable = PageRequest.of(0, EXPORT_MAX_ROWS,
                Sort.by(Sort.Direction.DESC, "timestamp"));
        Page<AuditLog> resultPage = auditLogRepository.findAll(
                AuditLogSpecifications.withFilters(
                        normalizedAction, userIds, normalizedEntityType, from, to),
                pageable);

        List<AuditLog> rows = resultPage.getContent();
        Map<UUID, String> actorNameById = lookupActorNames(rows);
        Map<UUID, String> actorRoleById = lookupActorRoles(rows);

        StringBuilder sb = new StringBuilder(rows.size() * 160);
        sb.append("timestamp,actor_id,actor_name,actor_role,action,entity_type,entity_id,details\n");
        for (AuditLog a : rows) {
            String ts = a.getTimestamp() != null
                    ? DateTimeFormatter.ISO_INSTANT.format(a.getTimestamp())
                    : "";
            UUID actorId = a.getUserId();
            String actorName = actorId != null ? actorNameById.getOrDefault(actorId, "") : "";
            String actorRoleLabel = actorId != null ? actorRoleById.getOrDefault(actorId, "") : "";
            String entType = a.getEntityType() != null ? a.getEntityType() : "";
            String entId = a.getEntityId() != null ? a.getEntityId().toString() : "";
            String details = a.getAfterJson() != null ? a.getAfterJson() : "";
            if (details.length() > 1_000) details = details.substring(0, 1_000) + "…";
            sb.append(csvField(ts)).append(',')
                    .append(csvField(actorId != null ? actorId.toString() : "")).append(',')
                    .append(csvField(actorName)).append(',')
                    .append(csvField(actorRoleLabel)).append(',')
                    .append(csvField(a.getAction() != null ? a.getAction() : "")).append(',')
                    .append(csvField(entType)).append(',')
                    .append(csvField(entId)).append(',')
                    .append(csvField(details)).append('\n');
        }
        if (resultPage.getTotalElements() > EXPORT_MAX_ROWS) {
            sb.append("# Truncated at ")
                    .append(EXPORT_MAX_ROWS)
                    .append(" rows. Total matched: ")
                    .append(resultPage.getTotalElements())
                    .append(". Tighten filters to export the remainder.\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── Internal ────────────────────────────────────────────────────────────

    /**
     * Resolves actor-search + actor-role into a userId set. Returns
     * {@code null} when neither filter is supplied (caller skips the IN
     * clause entirely). Returns a sentinel set with a random UUID when
     * the filters match no users — Hibernate 6 handles an empty IN list
     * but we belt-and-brace against any dialect that doesn't.
     */
    private Collection<UUID> resolveActorIds(String actorSearch, UserRole actorRole) {
        boolean hasSearch = actorSearch != null && !actorSearch.isBlank();
        boolean hasRole = actorRole != null;
        if (!hasSearch && !hasRole) return null;

        String q = hasSearch ? actorSearch.trim().toLowerCase(Locale.ROOT) : null;
        List<UUID> ids = userRepository.findAll().stream()
                .filter(u -> !hasSearch
                        || (u.getFullName() != null && u.getFullName().toLowerCase(Locale.ROOT).contains(q))
                        || (u.getEmail() != null && u.getEmail().toLowerCase(Locale.ROOT).contains(q)))
                .filter(u -> !hasRole
                        || (u.getRoles() != null && u.getRoles().contains(actorRole)))
                .map(User::getId)
                .toList();
        return ids.isEmpty() ? List.of(new UUID(0L, 0L)) : ids;
    }

    private Map<UUID, String> lookupActorNames(List<AuditLog> rows) {
        Set<UUID> actorIds = rows.stream()
                .map(AuditLog::getUserId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Map<UUID, String> out = new HashMap<>();
        if (actorIds.isEmpty()) return out;
        for (User u : userRepository.findAllById(actorIds)) {
            out.put(u.getId(), u.getFullName());
        }
        return out;
    }

    /**
     * Per-actor "primary role" lookup for the CSV's actor_role column.
     * Reads the actor's CURRENT roles — historical role at the time of the
     * audit event isn't stored on AuditLog (a future column would let the
     * export reflect role-at-event; out of scope here).
     */
    private Map<UUID, String> lookupActorRoles(List<AuditLog> rows) {
        Set<UUID> actorIds = rows.stream()
                .map(AuditLog::getUserId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Map<UUID, String> out = new HashMap<>();
        if (actorIds.isEmpty()) return out;
        for (User u : userRepository.findAllById(actorIds)) {
            out.put(u.getId(), primaryRoleLabel(u));
        }
        return out;
    }

    private static String primaryRoleLabel(User u) {
        if (u.getRoles() == null || u.getRoles().isEmpty()) return "";
        for (UserRole r : ROLE_PRIORITY) {
            if (u.getRoles().contains(r)) return r.name();
        }
        return u.getRoles().iterator().next().name();
    }

    private void writeExportAudit(User caller, String action, String actorSearch,
                                  UserRole actorRole, String entityType,
                                  Instant from, Instant to) {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (action != null && !action.isBlank()) filters.put("action", action);
        if (actorSearch != null && !actorSearch.isBlank()) filters.put("actorSearch", actorSearch);
        if (actorRole != null) filters.put("actorRole", actorRole.name());
        if (entityType != null && !entityType.isBlank()) filters.put("entityType", entityType);
        if (from != null) filters.put("from", from.toString());
        if (to != null) filters.put("to", to.toString());

        AuditLog meta = AuditLog.builder()
                .entityType("AuditLog")
                .entityId(null)
                .action("AUDIT_LOG_EXPORTED")
                .userId(caller != null ? caller.getId() : null)
                .afterJson(serializeJson(filters))
                .build();
        try {
            auditLogRepository.save(meta);
        } catch (Exception e) {
            log.warn("Failed to write AUDIT_LOG_EXPORTED meta-audit (non-fatal): {}",
                    e.getMessage());
        }
    }

    private String serializeJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize export-audit snapshot: {}", e.getMessage());
            return String.valueOf(snapshot);
        }
    }

    /**
     * RFC 4180-ish CSV escaping. Wraps in double-quotes when the value
     * contains a comma, double-quote, newline, or carriage return; doubles
     * internal double-quotes.
     */
    private static String csvField(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        boolean needsQuoting = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                needsQuoting = true;
                break;
            }
        }
        if (!needsQuoting) return raw;
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') sb.append('"');
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    private AuditLogEntryResponse toResponse(AuditLog a, String actorName) {
        return AuditLogEntryResponse.builder()
                .id(a.getId())
                .timestamp(a.getTimestamp())
                .actorId(a.getUserId())
                .actorName(actorName)
                .action(a.getAction())
                .entityType(a.getEntityType())
                .entityId(a.getEntityId())
                .details(truncate(a.getAfterJson()))
                .build();
    }

    private String truncate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() <= DETAILS_MAX) return s;
        return s.substring(0, DETAILS_MAX) + "…";
    }
}
