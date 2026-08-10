'use client';

import Script from 'next/script';
import { useEffect, useRef } from 'react';

/**
 * Cloudflare Turnstile widget — the frontend half of the bot-signup
 * mitigation. Server-side verification lives in
 * {@code apps/backend/src/main/java/com/anvicorp/api/auth/TurnstileVerifier.java}.
 *
 * <p>Renders the Turnstile challenge widget and reports the resulting
 * token to the parent via {@code onToken}. The parent includes the
 * token in the register / apply POST body as {@code captchaToken};
 * the backend verifies it against Cloudflare's siteverify API before
 * creating any User row.</p>
 *
 * <p>Loads the Turnstile SDK via {@code next/script} once per page —
 * subsequent widget mounts are cheap. Guards against SSR (the SDK
 * only exists in the browser).</p>
 *
 * <p><b>Env-controlled visibility.</b> When
 * {@code NEXT_PUBLIC_TURNSTILE_SITE_KEY} is unset (local dev, CI,
 * preview branches) the widget renders nothing and immediately reports
 * an empty token. The backend Turnstile verifier is defaulted OFF in
 * the same environment so the register endpoint still works. Once ops
 * populates both {@code NEXT_PUBLIC_TURNSTILE_SITE_KEY} (frontend) and
 * {@code TURNSTILE_SECRET} + {@code TURNSTILE_ENABLED=true} (backend),
 * the challenge activates.</p>
 */
interface TurnstileWidgetProps {
  /** Fired every time Turnstile issues a new token (successful pass or
   *  after a reset). The token is single-use per submit — the parent
   *  should include it in the next form submission then wait for the
   *  next {@code onToken} before enabling submit again if the response
   *  came back as an invalid-token (Turnstile issues one-shot tokens
   *  to defeat replay). */
  onToken: (token: string) => void;
  /** Widget theme — matches the surrounding form colour. */
  theme?: 'light' | 'dark' | 'auto';
}

interface TurnstileGlobal {
  render: (
    container: HTMLElement,
    opts: {
      sitekey: string;
      callback: (token: string) => void;
      'error-callback'?: () => void;
      'expired-callback'?: () => void;
      theme?: 'light' | 'dark' | 'auto';
    },
  ) => string;
  remove: (widgetId: string) => void;
  reset: (widgetId?: string) => void;
}

declare global {
  interface Window {
    turnstile?: TurnstileGlobal;
    __turnstileOnLoadCallback?: () => void;
  }
}

const SITE_KEY = process.env.NEXT_PUBLIC_TURNSTILE_SITE_KEY;

export default function TurnstileWidget({ onToken, theme = 'auto' }: TurnstileWidgetProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const widgetIdRef = useRef<string | null>(null);
  const onTokenRef = useRef(onToken);

  // Keep the latest onToken in a ref so Turnstile's callback doesn't
  // capture a stale closure — the widget renders once and lives until
  // unmount, while the parent's state (and thus the callback identity)
  // changes on every keystroke.
  useEffect(() => { onTokenRef.current = onToken; }, [onToken]);

  useEffect(() => {
    // Dev / preview path — no site key configured, widget stays hidden
    // and the parent gets an immediate empty token. The backend
    // Turnstile verifier defaults OFF in the same environment so
    // register still works end-to-end.
    if (!SITE_KEY) {
      onTokenRef.current('');
      return;
    }
    let cancelled = false;
    const tryRender = () => {
      if (cancelled) return;
      const el = containerRef.current;
      const ts = typeof window === 'undefined' ? undefined : window.turnstile;
      if (!el || !ts) {
        // SDK still loading — Script's onLoad below re-triggers this.
        return;
      }
      if (widgetIdRef.current) return; // already rendered
      widgetIdRef.current = ts.render(el, {
        sitekey: SITE_KEY,
        theme,
        callback: (token) => onTokenRef.current(token),
        'error-callback': () => onTokenRef.current(''),
        'expired-callback': () => onTokenRef.current(''),
      });
    };
    // First render attempt — if the SDK is already on the page (e.g.
    // navigation between two forms that both mount this widget), fires
    // immediately.
    tryRender();
    // Register a global onLoad hook so the Script's onReady also
    // triggers a render attempt without racing.
    window.__turnstileOnLoadCallback = tryRender;
    return () => {
      cancelled = true;
      const ts = typeof window === 'undefined' ? undefined : window.turnstile;
      if (widgetIdRef.current && ts) {
        try { ts.remove(widgetIdRef.current); } catch { /* widget already gone — no-op */ }
      }
      widgetIdRef.current = null;
    };
  }, [theme]);

  if (!SITE_KEY) {
    // Nothing to render locally — keep the layout consistent by
    // reserving no space (parent decides whether to show a helper hint).
    return null;
  }

  return (
    <>
      <Script
        src="https://challenges.cloudflare.com/turnstile/v0/api.js?onload=__turnstileOnLoadCallback"
        strategy="lazyOnload"
        onReady={() => window.__turnstileOnLoadCallback?.()}
      />
      <div ref={containerRef} className="flex justify-center" />
    </>
  );
}
