package com.anvicorp.api.project.referenceqa;

import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.ProjectRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Read / write trainer-authored reference Q&amp;A pairs on a Project.
 * Storage: JSON in {@code Project.referenceQaJson} (nullable TEXT).
 * Reuses the {@code resourceLinksJson} JSON-in-TEXT precedent so no
 * new table or relation is needed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReferenceQaService {

    private static final int MAX_PAIRS = 50;
    private static final TypeReference<List<ReferenceQaDtos.ReferenceQaPair>> LIST_TYPE =
            new TypeReference<>() {};

    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    // ── READ ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ReferenceQaDtos.ReferenceQaResponse read(UUID projectId, User caller) {
        requireCanRead(caller);
        Project project = mustGet(projectId);
        List<ReferenceQaDtos.ReferenceQaPair> pairs = parse(project.getReferenceQaJson());
        return new ReferenceQaDtos.ReferenceQaResponse(
                project.getId(), project.getUpdatedAt(), pairs);
    }

    // ── WRITE ─────────────────────────────────────────────────────────────

    @Transactional
    public ReferenceQaDtos.ReferenceQaResponse write(
            UUID projectId, ReferenceQaDtos.ReferenceQaWriteRequest req, User caller) {
        requireCanWrite(caller);
        if (req == null) throw new BadRequestException("body required");
        List<ReferenceQaDtos.ReferenceQaPair> incoming = req.pairs() == null
                ? List.of() : req.pairs();
        if (incoming.size() > MAX_PAIRS) {
            throw new BadRequestException(
                    "Cannot attach more than " + MAX_PAIRS + " reference Q&A pairs.");
        }
        List<ReferenceQaDtos.ReferenceQaPair> cleaned = new ArrayList<>(incoming.size());
        int idx = 0;
        for (ReferenceQaDtos.ReferenceQaPair p : incoming) {
            if (p == null) continue;
            String q = p.question() == null ? "" : p.question().trim();
            String a = p.answer() == null ? "" : p.answer().trim();
            if (q.isEmpty() && a.isEmpty()) continue;
            if (q.isEmpty() || a.isEmpty()) {
                throw new BadRequestException(
                        "Each pair must have both a question and an answer (empty pair at index "
                                + idx + ").");
            }
            cleaned.add(new ReferenceQaDtos.ReferenceQaPair(q, a, idx));
            idx++;
        }
        Project project = mustGet(projectId);
        try {
            project.setReferenceQaJson(cleaned.isEmpty()
                    ? null
                    : objectMapper.writeValueAsString(cleaned));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize reference Q&A", e);
        }
        Project saved = projectRepository.save(project);
        log.info("[ReferenceQa] project={} caller={} pairs={} (was: {})",
                projectId, caller.getId(), cleaned.size(),
                project.getReferenceQaJson() == null ? 0 : "prior");
        return new ReferenceQaDtos.ReferenceQaResponse(
                saved.getId(), saved.getUpdatedAt(), cleaned);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Project mustGet(UUID id) {
        return projectRepository.findById(id).orElseThrow(() ->
                new ResourceNotFoundException("Project not found: " + id));
    }

    private List<ReferenceQaDtos.ReferenceQaPair> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<ReferenceQaDtos.ReferenceQaPair> parsed =
                    objectMapper.readValue(json, LIST_TYPE);
            return parsed != null ? parsed : List.of();
        } catch (Exception e) {
            log.warn("[ReferenceQa] failed to parse reference_qa_json (non-fatal): {}",
                    e.getMessage());
            return List.of();
        }
    }

    private static void requireCanRead(User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        if (caller.getRoles() == null) throw new ForbiddenException("No role");
        if (caller.getRoles().contains(UserRole.SUPER_ADMIN)) return;
        if (caller.getRoles().contains(UserRole.TRAINER)) return;
        if (caller.getRoles().contains(UserRole.EVALUATOR)) return;
        throw new ForbiddenException("Not authorised to view reference Q&A");
    }

    private static void requireCanWrite(User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        if (caller.getRoles() == null) throw new ForbiddenException("No role");
        if (caller.getRoles().contains(UserRole.SUPER_ADMIN)) return;
        if (caller.getRoles().contains(UserRole.TRAINER)) return;
        throw new ForbiddenException("Only TRAINER or SUPER_ADMIN may edit reference Q&A");
    }
}
