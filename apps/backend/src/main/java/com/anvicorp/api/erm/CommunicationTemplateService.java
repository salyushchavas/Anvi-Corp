package com.anvicorp.api.erm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 0 — Mustache-style template render + ERM CRUD seam. Per-phase
 * listeners still send hard-coded copy until they migrate to call
 * {@link #render(String, String, Map)}; this service is the destination.
 *
 * <p>The renderer accepts {@code {{var}}} placeholders. Missing vars
 * throw {@link IllegalArgumentException} so a misconfigured template
 * surfaces during ERM editing rather than silently shipping blank text.
 * Null lookup returns {@link Optional#empty()} — caller decides whether
 * to fall back to a hard-coded string.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommunicationTemplateService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*\\}\\}");

    private final CommunicationTemplateRepository repository;

    public Optional<CommunicationTemplate> get(String key, String channel) {
        if (key == null || channel == null) return Optional.empty();
        try {
            return repository.findByKeyAndChannel(key, channel);
        } catch (Exception e) {
            log.warn("[CommunicationTemplate] lookup failed (non-fatal) for {}/{}: {}",
                    key, channel, e.getMessage());
            return Optional.empty();
        }
    }

    /** Convenience for callers that don't care which channel. */
    public Optional<CommunicationTemplate> getEmail(String key) {
        return get(key, "EMAIL");
    }

    public List<CommunicationTemplate> list() {
        return repository.findByActiveTrueOrderByKeyAsc();
    }

    @Transactional
    public CommunicationTemplate save(CommunicationTemplate t, UUID updatedBy) {
        if (t == null) throw new IllegalArgumentException("template is required");
        t.setUpdatedById(updatedBy);
        return repository.save(t);
    }

    /**
     * Render the template under {@code (key, channel)} against {@code vars}.
     * Returns {@link Optional#empty()} when the template is absent so the
     * caller can fall back to hard-coded copy; throws
     * {@link IllegalArgumentException} when the template exists but a
     * placeholder is missing from {@code vars}.
     */
    public Optional<Rendered> render(String key, String channel, Map<String, Object> vars) {
        return get(key, channel).map(t -> new Rendered(
                t.getSubjectTemplate() != null
                        ? substitute(t.getKey(), t.getSubjectTemplate(), vars)
                        : null,
                substitute(t.getKey(), t.getBodyTemplate(), vars)));
    }

    /** Pre-rendered subject + body pair. {@code subject} is null for IN_APP. */
    public record Rendered(String subject, String body) {}

    private String substitute(String key, String template, Map<String, Object> vars) {
        if (template == null || template.isEmpty()) return template;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String var = m.group(1);
            Object value = vars != null ? vars.get(var) : null;
            if (value == null) {
                throw new IllegalArgumentException(
                        "Template '" + key + "' missing variable '" + var + "'");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }
}
