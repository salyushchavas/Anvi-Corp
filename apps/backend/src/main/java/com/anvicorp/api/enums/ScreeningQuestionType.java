package com.anvicorp.api.enums;

/**
 * Question types supported by the lightweight screening engine.
 *
 *   SINGLE_CHOICE  — scorable; one correct {@code correctChoiceIndex}, awards
 *                    the question's {@code points} if the candidate picks it.
 *   FREE_TEXT      — informational only; never contributes to score or maxScore.
 */
public enum ScreeningQuestionType {
    SINGLE_CHOICE,
    FREE_TEXT
}
