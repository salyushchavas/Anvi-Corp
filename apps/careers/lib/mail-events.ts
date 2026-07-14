// Real-time mail event stream (S7c). Uses fetch + ReadableStream rather than
// EventSource, because EventSource cannot send the mail Bearer header. Hand-rolls
// the SSE frame parse, refreshes the token on 401 and reconnects, backs off
// exponentially on failure, and tears down cleanly via an AbortController.

import { mailApiBaseURL, refreshMailToken } from './mail-api';
import { getMailToken } from './mail-auth-storage';

export type MailEvent = { type: 'READY' } | { type: 'NEW_MAIL'; folder: string };

interface StreamHandlers {
  onEvent: (e: MailEvent) => void;
  /** Called on every (re)connect — use it to resync folder counts. */
  onOpen?: () => void;
}

/** Opens the stream and returns a teardown function. */
export function openMailEventStream(handlers: StreamHandlers): () => void {
  const controller = new AbortController();
  let closed = false;
  let attempt = 0;

  async function connectOnce(): Promise<void> {
    const token = getMailToken();
    if (!token) throw fatal('no mail token');
    const res = await fetch(`${mailApiBaseURL}/api/mail/events`, {
      headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
      signal: controller.signal,
      cache: 'no-store',
    });
    if (res.status === 401) {
      const fresh = await refreshMailToken();
      if (!fresh) throw fatal('unauthorized');
      // Reconnect with the fresh token AFTER the loop's backoff. We deliberately
      // do NOT reset `attempt` here: a normal token expiry costs one short delay,
      // but a token that keeps coming back 401 backs off instead of hammering.
      return;
    }
    if (!res.ok || !res.body) throw new Error(`mail stream ${res.status}`);

    attempt = 0;
    handlers.onOpen?.();
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let sep: number;
      while ((sep = buffer.indexOf('\n\n')) !== -1) {
        dispatch(buffer.slice(0, sep), handlers.onEvent);
        buffer = buffer.slice(sep + 2);
      }
    }
  }

  void (async () => {
    while (!closed) {
      try {
        await connectOnce();
      } catch (err) {
        if (closed || controller.signal.aborted) return;
        if ((err as { fatal?: boolean })?.fatal) return; // no token / unauthorized → stop
        // otherwise reconnect after backoff
      }
      if (closed) return;
      attempt += 1;
      await sleep(Math.min(30000, 1000 * 2 ** Math.min(attempt, 5)), controller.signal);
    }
  })();

  return () => {
    closed = true;
    controller.abort();
  };
}

function dispatch(frame: string, onEvent: (e: MailEvent) => void): void {
  const data = frame
    .split('\n')
    .filter((l) => l.startsWith('data:'))
    .map((l) => l.slice(5).trim())
    .join('\n');
  if (!data) return; // comment line / heartbeat
  try {
    onEvent(JSON.parse(data) as MailEvent);
  } catch {
    // ignore a malformed frame
  }
}

function fatal(msg: string): Error {
  return Object.assign(new Error(msg), { fatal: true });
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const t = setTimeout(resolve, ms);
    signal.addEventListener(
      'abort',
      () => {
        clearTimeout(t);
        resolve();
      },
      { once: true },
    );
  });
}

// ── Browser notifications ──────────────────────────────────────────────

export function ensureNotificationPermission(): void {
  if (typeof Notification === 'undefined') return;
  if (Notification.permission === 'default') {
    try {
      void Notification.requestPermission();
    } catch {
      // ignore — notifications are best-effort
    }
  }
}

/** Notify only for INBOX arrivals and only while the tab is hidden. */
export function notifyNewMail(folder: string): void {
  if (folder !== 'INBOX') return;
  if (typeof document === 'undefined' || !document.hidden) return;
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return;
  try {
    new Notification('New mail', { body: 'You have a new message in your inbox.' });
  } catch {
    // ignore
  }
}
