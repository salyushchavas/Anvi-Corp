package com.anvicorp.api.enums;

/**
 * Project catalog difficulty rating. Stored as {@code VARCHAR(20)} via
 * {@code @Enumerated(EnumType.STRING)} on Project.difficulty.
 */
public enum Difficulty {
    EASY,
    MEDIUM,
    HARD,
    EXPERT
}
