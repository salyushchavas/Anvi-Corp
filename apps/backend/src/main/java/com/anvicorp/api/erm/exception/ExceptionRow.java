package com.anvicorp.api.erm.exception;

import java.util.UUID;

/**
 * One detected exception row. Used in the top-urgent list on the ERM
 * Home dashboard; Phase 6 may persist these into a dedicated table.
 */
public record ExceptionRow(
        ExceptionType type,
        ExceptionSeverity severity,
        UUID internId,
        String internName,
        int daysOverdue,
        String actionUrl,
        UUID subjectResourceId
) {}
