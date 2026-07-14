package com.anvicorp.api.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.ScreeningQuestion;
import com.anvicorp.api.enums.ScreeningQuestionType;
import com.anvicorp.api.repository.ScreeningQuestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 2.1 — seeds the default screening questionnaire if the table is empty.
 * Idempotent: once any question exists, subsequent boots are a no-op. Wrapped
 * in try/catch so a seed failure logs WARN and never crashes startup (PRODUCT.md
 * non-essential-seed rule).
 *
 * Mix:
 *   - 3 SINGLE_CHOICE questions (scorable)
 *   - 2 FREE_TEXT questions (informational, ungraded)
 *
 * Per-posting custom questions are deferred — this gives every screening the
 * same baseline pool until that work lands.
 */
@Component
@Order(3)
@RequiredArgsConstructor
@Slf4j
public class ScreeningQuestionsSeeder implements CommandLineRunner {

    private final ScreeningQuestionRepository questionRepository;
    private final ObjectMapper objectMapper;
    private final com.anvicorp.api.config.BrandConfig brand;

    @Override
    public void run(String... args) {
        try {
            if (questionRepository.count() > 0) {
                log.info("Screening questions already present — skipping seed.");
                return;
            }
            questionRepository.saveAll(List.of(
                    singleChoice(1,
                            "Which language is primarily used for type-safe React development?",
                            List.of("JavaScript", "TypeScript", "Python", "Ruby"),
                            1, 10),
                    singleChoice(2,
                            "Which HTTP method is idempotent and used to update a resource?",
                            List.of("POST", "PUT", "PATCH", "CONNECT"),
                            1, 10),
                    singleChoice(3,
                            "What does SQL stand for?",
                            List.of(
                                    "Sequential Query Language",
                                    "Structured Query Language",
                                    "Simple Quoted Language",
                                    "Standard Quality Lookup"),
                            1, 10),
                    freeText(4,
                            "Briefly describe a recent project you're proud of and your role on it."),
                    freeText(5,
                            "Why are you interested in this internship at " + brand.getName() + "?")));
            log.info("Seeded {} default screening questions.", 5);
        } catch (Exception e) {
            log.warn("Screening question seed failed (non-fatal): {}", e.getMessage(), e);
        }
    }

    private ScreeningQuestion singleChoice(int order, String prompt,
                                           List<String> choices, int correctIndex, int points) {
        return ScreeningQuestion.builder()
                .orderIndex(order)
                .type(ScreeningQuestionType.SINGLE_CHOICE)
                .prompt(prompt)
                .choicesJson(toJson(choices))
                .correctChoiceIndex(correctIndex)
                .points(points)
                .build();
    }

    private ScreeningQuestion freeText(int order, String prompt) {
        return ScreeningQuestion.builder()
                .orderIndex(order)
                .type(ScreeningQuestionType.FREE_TEXT)
                .prompt(prompt)
                .points(0)
                .build();
    }

    private String toJson(List<String> choices) {
        try {
            return objectMapper.writeValueAsString(choices);
        } catch (JsonProcessingException e) {
            // Never happens for a List<String>; fall back to a stable serialization
            // rather than crash the seed.
            return choices.toString();
        }
    }
}
