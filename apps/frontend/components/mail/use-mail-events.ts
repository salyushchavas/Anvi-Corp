"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { openMailEventStream, type MailSseEvent } from "@/lib/mail-events";

type NotifyPermission = NotificationPermission | "unsupported";

/**
 * A8 real-time consumer. Opens the single fetch-based SSE stream while `enabled`,
 * forwards each NEW_MAIL to `onEvent` (the shell bumps the affected folder's badge
 * in its EXISTING state), calls `onReconnect` on every (re)connect to resync counts,
 * and fires a desktop notification ONLY for a true INBOX arrival while the tab is
 * hidden. Handlers are read through refs so re-renders don't reconnect the stream.
 * Teardown aborts the stream on logout/unmount — no notifications after logout.
 */
export function useMailEvents(opts: {
  enabled: boolean;
  onEvent: (e: MailSseEvent) => void;
  onReconnect: () => void;
  onNotificationClick?: (messageId: string) => void;
}): { notifyPermission: NotifyPermission; requestNotifyPermission: () => void } {
  const onEventRef = useRef(opts.onEvent);
  const onReconnectRef = useRef(opts.onReconnect);
  const onClickRef = useRef(opts.onNotificationClick);
  onEventRef.current = opts.onEvent;
  onReconnectRef.current = opts.onReconnect;
  onClickRef.current = opts.onNotificationClick;

  const [notifyPermission, setNotifyPermission] = useState<NotifyPermission>(() =>
    typeof window !== "undefined" && "Notification" in window ? Notification.permission : "unsupported",
  );

  const requestNotifyPermission = useCallback(() => {
    if (typeof window === "undefined" || !("Notification" in window)) return;
    Notification.requestPermission()
      .then((p) => setNotifyPermission(p))
      .catch(() => {
        /* some browsers reject a non-gesture request — the bell affordance retries */
      });
  }, []);

  // Ask once on the first authed mount when the user hasn't decided yet (respect
  // granted/denied thereafter — never nag).
  useEffect(() => {
    if (!opts.enabled) return;
    if (typeof window === "undefined" || !("Notification" in window)) return;
    if (Notification.permission === "default") requestNotifyPermission();
    else setNotifyPermission(Notification.permission);
  }, [opts.enabled, requestNotifyPermission]);

  // One stream per authed session; aborted on logout/unmount.
  useEffect(() => {
    if (!opts.enabled) return;
    const controller = new AbortController();
    void openMailEventStream({
      signal: controller.signal,
      onOpen: () => onReconnectRef.current(),
      onEvent: (e) => {
        onEventRef.current(e);
        maybeNotify(e, onClickRef.current);
      },
    });
    return () => controller.abort();
  }, [opts.enabled]);

  return { notifyPermission, requestNotifyPermission };
}

function maybeNotify(e: MailSseEvent, onClick?: (messageId: string) => void) {
  if (e.type !== "NEW_MAIL") return;
  // A rule that filed the mail into a custom folder / Archive / Trash bumps the badge
  // SILENTLY — only a true INBOX arrival pops a desktop notification.
  if (e.customFolderId || e.folder !== "INBOX") return;
  if (typeof window === "undefined" || !("Notification" in window)) return;
  if (Notification.permission !== "granted") return;
  // Suppress when the tab is already visible AND focused (the user is looking at it).
  if (typeof document !== "undefined" && document.visibilityState === "visible" && document.hasFocus()) return;
  try {
    // The event carries no message content (nothing decrypted leaves the server over
    // SSE), so the notification is intentionally generic.
    const n = new Notification("New message", {
      body: "You have new mail in your inbox.",
      tag: e.messageId,
    });
    n.onclick = () => {
      window.focus();
      onClick?.(e.messageId);
      n.close();
    };
  } catch {
    /* Notification construction can throw on some platforms — ignore */
  }
}
