package com.anvicorp.api.intern;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Single source of truth for "give me THE trainer / evaluator for this
 * intern." Anvi Corp runs a single org-wide trainer
 * ({@code DEFAULT_TRAINER_EMAIL}) and a single org-wide evaluator
 * ({@code DEFAULT_EVALUATOR_EMAIL}); per-intern
 * {@code intern_lifecycles.trainer_id} / {@code evaluator_id} are
 * optional overrides that ERM rarely needs to set.
 *
 * <p>Every user-facing read of those fields must go through this
 * resolver — never the raw FK — so a null link cannot surface as an
 * empty "Your team" slot, a skipped evaluator notify, or an
 * uncaught NPE downstream. Returns null only when the env var itself
 * is unset / unresolvable (operator must configure
 * {@code DEFAULT_TRAINER_EMAIL} + {@code DEFAULT_EVALUATOR_EMAIL} on
 * the deploy).</p>
 *
 * <p>Pure read helper — never mutates the lifecycle row. Use
 * {@link ReportingStructureAutoLinker} when you DO want to stamp the
 * resolved id onto the row.</p>
 *
 * <p>Companion to {@link com.anvicorp.api.trainer.TrainerScopeGuard}
 * and {@link com.anvicorp.api.evaluator.EvaluatorScopeGuard} — those
 * encode the same single-X model at the access-check layer; this
 * encodes it at the lookup layer.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrgTeamResolver {

    private final UserRepository userRepository;

    @Value("${app.default-trainer-email:}")
    private String defaultTrainerEmail;

    @Value("${app.default-evaluator-email:}")
    private String defaultEvaluatorEmail;

    /**
     * Per-intern FK if set, else the configured org trainer's user id,
     * else null (env var unset / unresolvable).
     */
    public UUID resolveTrainerId(InternLifecycle lc) {
        if (lc != null && lc.getTrainerId() != null) return lc.getTrainerId();
        return resolveByEmail(defaultTrainerEmail);
    }

    /**
     * Per-intern FK if set, else the configured org evaluator's user
     * id, else null (env var unset / unresolvable).
     */
    public UUID resolveEvaluatorId(InternLifecycle lc) {
        if (lc != null && lc.getEvaluatorId() != null) return lc.getEvaluatorId();
        return resolveByEmail(defaultEvaluatorEmail);
    }

    /** Entity variant of {@link #resolveTrainerId} for callers that
     *  need the User object directly (name / email / roles). */
    public Optional<User> resolveTrainer(InternLifecycle lc) {
        UUID id = resolveTrainerId(lc);
        return id == null ? Optional.empty() : userRepository.findById(id);
    }

    /** Entity variant of {@link #resolveEvaluatorId}. */
    public Optional<User> resolveEvaluator(InternLifecycle lc) {
        UUID id = resolveEvaluatorId(lc);
        return id == null ? Optional.empty() : userRepository.findById(id);
    }

    private UUID resolveByEmail(String email) {
        if (email == null || email.isBlank()) return null;
        return userRepository.findByEmail(email.trim())
                .map(User::getId)
                .orElse(null);
    }
}
