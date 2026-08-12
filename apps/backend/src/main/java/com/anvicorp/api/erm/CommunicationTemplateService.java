package com.anvicorp.api.erm;

import com.anvicorp.api.config.BrandConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
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
    private final BrandConfig brand;

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
     *
     * <p><b>Wave-1 brand-var auto-injection.</b> The seeder migrated
     * literal {@code "Anvi Corp"} tokens to {@code {{brandName}}} /
     * {@code {{brandLegalEntity}}} / {@code {{supportEmail}}} /
     * {@code {{signoffBlock}}} placeholders — the templates are now
     * white-label-ready in the DB. Every render() call auto-augments
     * {@code vars} with these brand-derived values (resolved from
     * {@link BrandConfig}) BEFORE substitution, so listeners don't have
     * to remember to pass them and never accidentally leave a
     * placeholder unresolved. Caller-supplied values in {@code vars}
     * take precedence — an ERM-facing render can pass a per-brand
     * {@code brandName} override if needed. {@code signoffBlock}
     * personalizes to the ERM when {@code vars.ermName} is present
     * (preserves the offer-family behaviour) and falls back to the
     * brand-level line otherwise.</p>
     */
    public Optional<Rendered> render(String key, String channel, Map<String, Object> vars) {
        Map<String, Object> merged = mergeBrandDefaults(vars);
        return get(key, channel).map(t -> new Rendered(
                t.getSubjectTemplate() != null
                        ? substitute(t.getKey(), t.getSubjectTemplate(), merged)
                        : null,
                substitute(t.getKey(), t.getBodyTemplate(), merged)));
    }

    /** Attach BrandConfig-derived defaults for the four brand placeholders
     *  every seeded template can now reference. Caller-supplied entries
     *  win — a listener that wants to override {@code signoffBlock} with
     *  a custom line still can. Never mutates the caller's map. */
    private Map<String, Object> mergeBrandDefaults(Map<String, Object> vars) {
        Map<String, Object> merged = new HashMap<>();
        merged.put("brandName", brand.getName());
        merged.put("brandLegalEntity", brand.getLegalName());
        merged.put("supportEmail", brand.getSupportEmail());
        // signoffBlock resolves ONCE per render — read ermName from the
        // caller's vars if supplied and use it verbatim; otherwise fall
        // back to the brand-level line. Kept as a Java-level computation
        // (not a nested placeholder) so template authors can't accidentally
        // break the sign-off shape via a bad override.
        Object ermName = vars != null ? vars.get("ermName") : null;
        String ermStr = ermName != null ? ermName.toString() : null;
        merged.put("signoffBlock", brand.signoffBlock(ermStr));
        if (vars != null) merged.putAll(vars);
        return merged;
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
