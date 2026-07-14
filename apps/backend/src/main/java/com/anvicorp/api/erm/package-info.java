/**
 * ERM and cross-role architecture principles for Anvi Careers:
 *
 * <ol>
 *   <li>{@code users.lifecycle_status} + {@code intern_lifecycles.active_status}
 *       are the canonical state. All role dashboards derive their view from
 *       these. No role-specific status enums.</li>
 *
 *   <li>Action endpoints (POST {@code /api/v1/applications/{id}/shortlist},
 *       etc.) are role-agnostic in URL; {@code @PreAuthorize} gates the role.
 *       Dashboard endpoints ({@code GET /api/v1/erm/dashboard},
 *       {@code /trainer/dashboard}, etc.) are role-specific in URL and DTO
 *       shape.</li>
 *
 *   <li>Field-level RBAC at the DTO serialization layer. Internal notes,
 *       PII, internal scoring stripped per role.</li>
 *
 *   <li>One audit log, one notification dispatcher, one exception detector
 *       for all roles. Cross-cutting concerns are singletons.</li>
 *
 *   <li>{@code intern_lifecycles}({@code erm_id}, {@code trainer_id},
 *       {@code evaluator_id}, {@code manager_id}) is the join table for every
 *       "who can act" check.</li>
 *
 *   <li>{@link com.anvicorp.api.erm.ReasonCode} +
 *       {@link com.anvicorp.api.erm.CommunicationTemplate} centralize
 *       decision policy and messaging. Action endpoints accept
 *       {@code reasonCode}; messages render via
 *       {@link com.anvicorp.api.erm.CommunicationTemplateService}.</li>
 *
 *   <li>Dashboards poll their own dashboard endpoint at 30-60s. Future
 *       real-time can replace polling without changing data shape.</li>
 * </ol>
 */
package com.anvicorp.api.erm;
