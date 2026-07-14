package com.anvicorp.api.enums;

/**
 * Type of periodic intern evaluation.
 *
 * <pre>
 *   MIDPOINT      Standard mid-engagement check.
 *   FINAL         End-of-engagement summative.
 *   I983_12MO     STEM OPT 12-month self+supervisor evaluation (required by USCIS).
 *   I983_FINAL    STEM OPT final evaluation at end of OPT.
 *   CHECKPOINT    Ad-hoc supervisor check between the standard windows.
 * </pre>
 */
public enum EvaluationType {
    MIDPOINT,
    FINAL,
    I983_12MO,
    I983_FINAL,
    CHECKPOINT
}
