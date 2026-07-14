/**
 * F4 — shared API-error formatter so every surface renders errors the
 * same way. The 191 existing call sites that read
 * `err.response?.data?.error` keep working unchanged (the backend
 * still emits that field); this helper is opt-in for surfaces that
 * want the friendly 5xx wording + a traceId reference.
 *
 * Pattern:
 *   import { formatApiError } from '@/lib/api-error';
 *   try { await api.post(...); }
 *   catch (e) { setToast(formatApiError(e).displayText); }
 */
import { AxiosError } from 'axios';

export interface FormattedApiError {
  /** HTTP status (0 if the request never reached the server). */
  status: number;
  /** Machine-readable code from the backend (e.g. CHECK_VIOLATION) if present. */
  code: string | null;
  /** Per-request trace id (X-Trace-Id) — show to the user on 5xx so they can
   *  give it to support, and grep the backend log with it. */
  traceId: string | null;
  /** Raw backend `message` / legacy `error` field, if any. */
  rawMessage: string | null;
  /** Final display string. For 4xx: the backend message. For 5xx: the
   *  friendly "something went wrong (ref: …)" wording. For network
   *  failures: "Couldn't reach the server — check your connection." */
  displayText: string;
}

/**
 * Normalize an arbitrary thrown value into the F4 ErrorResponse shape.
 * Safe to call with anything — Axios errors, plain Errors, strings, null.
 */
export function formatApiError(err: unknown): FormattedApiError {
  // Network / no-response case (server unreachable, CORS preflight failed,
  // etc.) — Axios populates the error without `response`.
  if (isAxiosLike(err) && !err.response) {
    return {
      status: 0,
      code: null,
      traceId: null,
      rawMessage: err.message ?? null,
      displayText: "Couldn't reach the server — check your connection and try again.",
    };
  }

  if (isAxiosLike(err) && err.response) {
    const status = err.response.status;
    const data = (err.response.data ?? {}) as {
      error?: string;
      message?: string;
      code?: string;
      traceId?: string;
    };
    const headerTraceId =
      (err.response.headers?.['x-trace-id'] as string | undefined) ?? undefined;
    const traceId = data.traceId ?? headerTraceId ?? null;
    const rawMessage = data.message ?? data.error ?? null;

    let displayText: string;
    if (status >= 500) {
      // F4 contract — friendly 5xx + traceId reference. Never leak the
      // raw `rawMessage` (which on a bare 500 may be "Internal server
      // error" or similar).
      displayText = rawMessage && rawMessage.length < 200
        ? rawMessage
        : `Something went wrong on our end — we've logged it. Please try again, or contact support${traceId ? ` with reference ${traceId}` : ''}.`;
    } else if (rawMessage) {
      displayText = rawMessage;
    } else if (status === 404) {
      displayText = 'Not found.';
    } else if (status === 403) {
      displayText = "You don't have permission to do that.";
    } else if (status === 401) {
      displayText = 'Your session expired — please sign in again.';
    } else {
      displayText = `Request failed (HTTP ${status}).`;
    }

    return {
      status,
      code: data.code ?? null,
      traceId,
      rawMessage,
      displayText,
    };
  }

  // Plain Error or unknown thrown value.
  if (err instanceof Error) {
    return {
      status: 0,
      code: null,
      traceId: null,
      rawMessage: err.message,
      displayText: err.message || 'An unexpected error occurred.',
    };
  }
  return {
    status: 0,
    code: null,
    traceId: null,
    rawMessage: null,
    displayText: 'An unexpected error occurred.',
  };
}

function isAxiosLike(
  e: unknown,
): e is AxiosError<{
  error?: string;
  message?: string;
  code?: string;
  traceId?: string;
}> {
  return (
    typeof e === 'object'
    && e !== null
    && 'isAxiosError' in e
    && (e as { isAxiosError?: boolean }).isAxiosError === true
  );
}
