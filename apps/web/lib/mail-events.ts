// A8 real-time stream. EventSource can't send an Authorization header, so this is
// a fetch()-based SSE reader: it streams response.body, decodes chunks, and parses
// SSE frames by hand (handling framed + split chunks). On 401 it refreshes once
// (single-flight, shared with mail-api) and reconnects; on any drop it reconnects
// with capped exponential backoff; the AbortSignal tears everything down on
// logout/unmount. The token rides the Bearer header — NEVER the URL.

import { MAIL_API_BASE, getToken, mailRefresh } from "./mail-api";

export interface MailSseEvent {
  type: string; // "NEW_MAIL"
  folder: string; // A7-resolved system folder, e.g. "INBOX" | "ARCHIVE" | "TRASH"
  customFolderId: string | null; // non-null → filed into an A6 custom folder
  messageId: string;
}

interface StreamOptions {
  onEvent: (event: MailSseEvent) => void;
  /** Called on each successful (re)connect — the consumer resyncs counts here. */
  onOpen?: () => void;
  signal: AbortSignal;
}

const EVENTS_URL = `${MAIL_API_BASE}/api/mail/events`;
const MAX_BACKOFF_MS = 30_000;

/** Open the stream and keep it open across drops until `signal` aborts. Never throws. */
export async function openMailEventStream({ onEvent, onOpen, signal }: StreamOptions): Promise<void> {
  let attempt = 0;
  while (!signal.aborted) {
    try {
      const stayedOpen = await connectOnce(onEvent, onOpen, signal);
      attempt = stayedOpen ? 0 : attempt + 1; // reset backoff after a healthy connection
    } catch {
      if (signal.aborted) return;
      attempt += 1;
    }
    if (signal.aborted) return;
    const delay = Math.min(MAX_BACKOFF_MS, 1000 * 2 ** Math.min(attempt, 5)); // 1s → 30s
    await sleep(delay, signal);
  }
}

/** One connection lifecycle. Resolves when the stream ends; returns whether it stayed open a while. */
async function connectOnce(
  onEvent: (e: MailSseEvent) => void,
  onOpen: (() => void) | undefined,
  signal: AbortSignal,
): Promise<boolean> {
  let res = await fetch(EVENTS_URL, { headers: authHeaders(getToken()), signal, cache: "no-store" });
  if (res.status === 401) {
    const fresh = await mailRefresh();
    if (!fresh) throw new Error("session expired");
    res = await fetch(EVENTS_URL, { headers: authHeaders(fresh), signal, cache: "no-store" });
  }
  if (!res.ok || !res.body) throw new Error(`stream failed (${res.status})`);

  onOpen?.();
  const startedAt = Date.now();
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      // Extract every complete frame (delimited by a blank line); leave the partial.
      let boundary: RegExpExecArray | null;
      // eslint-disable-next-line no-cond-assign
      while ((boundary = /\r?\n\r?\n/.exec(buffer))) {
        const frame = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary[0].length);
        const data = parseFrameData(frame);
        if (data !== null) {
          try {
            onEvent(JSON.parse(data) as MailSseEvent);
          } catch {
            /* ignore a malformed data payload */
          }
        }
      }
    }
  } finally {
    // cancel() returns a promise; swallow its rejection so an abort can't surface an
    // unhandled rejection. The reader/stream is torn down either way.
    void reader.cancel().catch(() => {});
  }
  return Date.now() - startedAt > 5000;
}

function authHeaders(token: string | null): HeadersInit {
  const h: Record<string, string> = { Accept: "text/event-stream" };
  if (token) h.Authorization = `Bearer ${token}`;
  return h;
}

/**
 * Parse one SSE frame → its concatenated `data:` payload, or null for a
 * comment-only/heartbeat frame (": ping") or a frame with no data lines.
 */
function parseFrameData(frame: string): string | null {
  const dataLines: string[] = [];
  for (const raw of frame.split("\n")) {
    const line = raw.replace(/\r$/, "");
    if (line === "" || line.startsWith(":")) continue; // blank or comment (heartbeat)
    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1); // strip one optional leading space
    if (field === "data") dataLines.push(value);
    // `event:`/`id:`/`retry:` fields are ignored — the payload's own `type` drives logic.
  }
  return dataLines.length ? dataLines.join("\n") : null;
}

/** Abortable delay used for reconnect backoff. */
function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) return resolve();
    const t = setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(t);
      resolve();
    };
    signal.addEventListener("abort", onAbort, { once: true });
  });
}
