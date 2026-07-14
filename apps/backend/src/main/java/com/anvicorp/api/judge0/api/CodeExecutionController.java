package com.anvicorp.api.judge0.api;

import com.anvicorp.api.judge0.CodeExecutionResult;
import com.anvicorp.api.judge0.Judge0Service;
import com.anvicorp.api.judge0.LanguageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Coding-playground execution endpoints. Untrusted source code is forwarded
 * to {@link Judge0Service} — never executed in-process.
 *
 * <ul>
 *   <li>{@code POST /api/v1/playground/run} — submit + poll for a verdict.</li>
 *   <li>{@code GET  /api/v1/playground/languages} — language list (cached).</li>
 * </ul>
 *
 * <p>Both endpoints require authentication. The Judge0 quota is shared
 * across all callers, so a typed quota-exceeded result lands in
 * {@link CodeExecutionResult#statusId()} rather than a 5xx.</p>
 */
@RestController
@RequestMapping("/api/v1/playground")
@RequiredArgsConstructor
public class CodeExecutionController {

    private final Judge0Service judge0Service;

    @PostMapping("/run")
    @PreAuthorize("isAuthenticated()")
    public CodeExecutionResult run(@Valid @RequestBody RunCodeRequest req) {
        return judge0Service.executeAndAwait(
                req.sourceCode(),
                req.languageId(),
                req.stdin());
    }

    @GetMapping("/languages")
    @PreAuthorize("isAuthenticated()")
    public List<LanguageResponse> languages() {
        return judge0Service.listLanguages();
    }
}
