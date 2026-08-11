/**
 * Shared field-error extractor for our axios mutations.
 *
 * <p>Every backend @Valid failure surfaces at
 * {@code response.data.details.fields} as a {@code Record<field, message>}
 * (see the Spring exception handler). Without this helper, every call
 * site independently reads {@code response.data.error} — a single string
 * that at best names one field and often just says "Validation failed",
 * hiding the actual constraint message.</p>
 *
 * <p>Format: {@code "Field: message • OtherField: message"}. First-letter
 * uppercased on the field name so the resulting toast reads naturally.
 * Falls through to {@code data.message → data.error → fallback} when no
 * per-field details are present (non-validation error, network failure,
 * etc.) so callers can pipe every error path through this one function.</p>
 *
 * <p>Regression source: the "Validation Failed" bug flagged on 6+ admin
 * + ERM + intern mutation surfaces. First fixed inline on the new-
 * template create page (careers/admin/document-templates/new/page.tsx);
 * extracted here so every surface reads the same way and can't drift.</p>
 */

export interface AxiosLikeError {
  response?: {
    data?: {
      error?: string;
      message?: string;
      details?: { fields?: Record<string, string> };
    };
  };
  message?: string;
}

/**
 * Extract a human-readable error message from an axios failure.
 *
 * @param e     the caught error (unknown-typed, we narrow inline)
 * @param fallback message used when nothing structured is available
 *   (defaults to "Request failed"). Callers typically pass a domain-
 *   specific string like "Could not save profile".
 */
export function parseFieldErrors(e: unknown, fallback = 'Request failed'): string {
  const ax = e as AxiosLikeError;
  const fields = ax?.response?.data?.details?.fields;
  if (fields && Object.keys(fields).length > 0) {
    return Object.entries(fields)
      .map(([f, m]) => `${f.charAt(0).toUpperCase() + f.slice(1)}: ${m}`)
      .join(' • ');
  }
  return ax?.response?.data?.message
    ?? ax?.response?.data?.error
    ?? ax?.message
    ?? fallback;
}
