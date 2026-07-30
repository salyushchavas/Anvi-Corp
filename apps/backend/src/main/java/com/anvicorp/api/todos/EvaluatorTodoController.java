package com.anvicorp.api.todos;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** EVALUATOR to-do panel + login pop-up gate feed. */
@RestController
@RequestMapping("/api/v1/evaluator/todos")
@RequiredArgsConstructor
public class EvaluatorTodoController {

    private final EvaluatorTodoService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public TodoPanelResponse get(@AuthenticationPrincipal User caller) {
        return service.build(caller);
    }
}
