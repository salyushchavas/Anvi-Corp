package com.anvicorp.api.exception;

import lombok.Getter;

import java.util.List;

/**
 * ERM Phase 4 — gate exception. Onboarding packet assignment requires
 * intern_lifecycles.trainer_id + evaluator_id + manager_id all populated.
 * Maps to {@code 409 CONFLICT} with body
 * {@code { error, code: REPORTING_STRUCTURE_INCOMPLETE, missing: [...] }}.
 */
@Getter
public class ReportingStructureIncompleteException extends RuntimeException {

    private final List<String> missing;

    public ReportingStructureIncompleteException(List<String> missing) {
        super("Assign Trainer, Evaluator, and Manager before assigning onboarding.");
        this.missing = missing != null ? missing : List.of();
    }
}
