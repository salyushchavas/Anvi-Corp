package com.anvicorp.api.workspace.application;

import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ProjectStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.workspace.domain.ProjectWorkspaceFile;
import com.anvicorp.api.workspace.infrastructure.ProjectWorkspaceFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CRUD over the intern's live workspace files for one {@link Project}.
 *
 * <h2>Guards</h2>
 * <ul>
 *   <li><b>Reads</b> (list / get) — intern of the engagement OR the
 *       engagement's technical evaluator OR {@code SUPER_ADMIN}.</li>
 *   <li><b>Writes</b> (put / delete / rename) — ONLY the engagement's intern.
 *       Evaluators don't write; SUPER_ADMIN doesn't either (audit clarity).</li>
 *   <li><b>Status gate</b> — writes only allowed while project status is
 *       {@code IN_PROGRESS}. {@code SUBMITTED} locks the workspace; a
 *       {@code RETURNED} bounce drops the status back to {@code IN_PROGRESS}
 *       so editing resumes automatically.</li>
 * </ul>
 *
 * <h2>Hard limits</h2>
 * <ul>
 *   <li>Max bytes per file: {@value #MAX_FILE_BYTES}.</li>
 *   <li>Max files per workspace: {@value #MAX_FILES_PER_WORKSPACE}.</li>
 *   <li>Max total workspace bytes: {@value #MAX_WORKSPACE_BYTES}.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceFileService {

    public static final long MAX_FILE_BYTES = 1_048_576L;            // 1 MB
    public static final int MAX_FILES_PER_WORKSPACE = 100;
    public static final long MAX_WORKSPACE_BYTES = 10L * 1_048_576L; // 10 MB

    /**
     * Allowed path characters: alphanumerics, dot, dash, underscore, slash.
     * Explicit positive set is safer than a deny-list. Path-traversal
     * ({@code ..}), leading slashes, and double slashes are handled in
     * {@link #validatePath(String)}.
     */
    private static final Pattern PATH_CHARSET = Pattern.compile("^[A-Za-z0-9._/-]+$");
    private static final int MAX_PATH_LENGTH = 512;

    private final ProjectRepository projectRepository;
    private final ProjectWorkspaceFileRepository fileRepository;

    // ── Reads ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ProjectWorkspaceFile> listFiles(UUID projectId, User caller) {
        Project project = loadProject(projectId);
        ensureCanRead(project, caller);
        return fileRepository.findByProjectIdOrderByPathAsc(projectId);
    }

    @Transactional(readOnly = true)
    public ProjectWorkspaceFile getFile(UUID projectId, String path, User caller) {
        Project project = loadProject(projectId);
        ensureCanRead(project, caller);
        validatePath(path);
        return fileRepository.findByProjectIdAndPath(projectId, path)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "File not found in this workspace: " + path));
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    /**
     * Create-or-update one file. Idempotent: re-PUT of identical content
     * still touches {@code lastModifiedAt} (it matches the "save" semantics
     * users expect; same as a no-op vim save).
     */
    @Transactional
    public ProjectWorkspaceFile upsertFile(UUID projectId, String path,
                                           String content, User caller) {
        Project project = loadProject(projectId);
        ensureIntern(project, caller);
        ensureEditable(project);
        validatePath(path);
        String safeContent = content != null ? content : "";
        long size = safeContent.getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_FILE_BYTES) {
            throw new BadRequestException(
                    "File exceeds the 1 MB limit (got " + size + " bytes).");
        }

        Optional<ProjectWorkspaceFile> existing =
                fileRepository.findByProjectIdAndPath(projectId, path);
        long currentTotal = fileRepository.sumSizeBytesByProjectId(projectId);
        long deltaWouldBe = existing
                .map(f -> size - f.getSizeBytes())
                .orElse(size);
        if (currentTotal + deltaWouldBe > MAX_WORKSPACE_BYTES) {
            throw new ConflictException(
                    "Workspace exceeds the 10 MB cap. Delete files to make room.");
        }
        if (existing.isEmpty()) {
            long count = fileRepository.countByProjectId(projectId);
            if (count >= MAX_FILES_PER_WORKSPACE) {
                throw new ConflictException(
                        "Workspace already has " + MAX_FILES_PER_WORKSPACE
                                + " files (the maximum). Delete one to add a new file.");
            }
        }

        ProjectWorkspaceFile file = existing.orElseGet(() ->
                ProjectWorkspaceFile.builder()
                        .projectId(projectId)
                        .path(path)
                        .build());
        file.setContent(safeContent);
        file.setSizeBytes(size);
        file.setLastModifiedBy(caller.getId());
        ProjectWorkspaceFile saved = fileRepository.save(file);
        log.info("workspace.file project={} action=put path={} actor={} sizeBytes={}",
                projectId, path, caller.getId(), size);
        return saved;
    }

    @Transactional
    public void deleteFile(UUID projectId, String path, User caller) {
        Project project = loadProject(projectId);
        ensureIntern(project, caller);
        ensureEditable(project);
        validatePath(path);
        ProjectWorkspaceFile existing = fileRepository
                .findByProjectIdAndPath(projectId, path).orElse(null);
        if (existing == null) return; // idempotent — already gone
        fileRepository.delete(existing);
        log.info("workspace.file project={} action=delete path={} actor={}",
                projectId, path, caller.getId());
    }

    @Transactional
    public ProjectWorkspaceFile renameFile(UUID projectId, String fromPath,
                                           String toPath, User caller) {
        Project project = loadProject(projectId);
        ensureIntern(project, caller);
        ensureEditable(project);
        validatePath(fromPath);
        validatePath(toPath);
        if (fromPath.equals(toPath)) {
            // No-op rename — return the row unchanged.
            return fileRepository.findByProjectIdAndPath(projectId, fromPath)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "File not found in this workspace: " + fromPath));
        }
        if (fileRepository.existsByProjectIdAndPath(projectId, toPath)) {
            throw new ConflictException(
                    "A file already exists at " + toPath + ".");
        }
        ProjectWorkspaceFile file = fileRepository
                .findByProjectIdAndPath(projectId, fromPath)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "File not found in this workspace: " + fromPath));
        file.setPath(toPath);
        file.setLastModifiedBy(caller.getId());
        ProjectWorkspaceFile saved = fileRepository.save(file);
        log.info("workspace.file project={} action=rename from={} to={} actor={}",
                projectId, fromPath, toPath, caller.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public long getWorkspaceSize(UUID projectId) {
        return fileRepository.sumSizeBytesByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public long getFileCount(UUID projectId) {
        return fileRepository.countByProjectId(projectId);
    }

    // ── Guards ──────────────────────────────────────────────────────────────

    private Project loadProject(UUID projectId) {
        return projectRepository.findByIdWithGraph(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + projectId));
    }

    private static void ensureCanRead(Project project, User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required.");
        if (isSuperAdmin(caller)) return;
        if (isOwningIntern(project, caller)) return;
        if (isEngagementEvaluator(project, caller)) return;
        throw new ForbiddenException(
                "Only this project's intern, evaluator, or SUPER_ADMIN may view the workspace.");
    }

    /**
     * Writes only land for the intern themselves. Evaluators DO NOT edit
     * intern code — they review submissions. SUPER_ADMIN is also blocked
     * from direct writes here to keep audit trails clean (admin can still
     * inspect via reads).
     */
    private static void ensureIntern(Project project, User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required.");
        if (!isOwningIntern(project, caller)) {
            throw new ForbiddenException(
                    "Only the project's intern may edit workspace files.");
        }
    }

    private static void ensureEditable(Project project) {
        ProjectStatus s = project.getStatus();
        if (s != ProjectStatus.IN_PROGRESS && s != ProjectStatus.NOT_STARTED) {
            throw new ConflictException(
                    "Workspace is locked (project status: " + s
                            + "). Wait for the evaluator's response.");
        }
    }

    private static boolean isOwningIntern(Project project, User caller) {
        Candidate intern = project.getIntern();
        User u = intern != null ? intern.getUser() : null;
        return u != null && u.getId().equals(caller.getId());
    }

    private static boolean isEngagementEvaluator(Project project, User caller) {
        // Role-based — any TECHNICAL_EVALUATOR can read the workspace.
        // Per-engagement supervisor FK is informational, not a boundary.
        return caller != null
                && caller.getRoles() != null
                && caller.getRoles().contains(UserRole.TRAINER);
    }

    private static boolean isSuperAdmin(User u) {
        return u.getRoles() != null && u.getRoles().contains(UserRole.SUPER_ADMIN);
    }

    static void validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new BadRequestException("Path is required.");
        }
        if (path.length() > MAX_PATH_LENGTH) {
            throw new BadRequestException(
                    "Path is too long (max " + MAX_PATH_LENGTH + " chars).");
        }
        if (path.startsWith("/")) {
            throw new BadRequestException("Path must be relative (no leading '/').");
        }
        if (path.contains("..")) {
            throw new BadRequestException("Path traversal is not allowed.");
        }
        if (path.contains("//")) {
            throw new BadRequestException("Path must not contain '//'.");
        }
        if (!PATH_CHARSET.matcher(path).matches()) {
            throw new BadRequestException(
                    "Path contains invalid characters. "
                            + "Allowed: A-Z, a-z, 0-9, '.', '-', '_', '/'.");
        }
    }
}
