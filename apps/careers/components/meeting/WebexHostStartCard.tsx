'use client';

import { useCallback, useEffect, useState } from 'react';
import { ExternalLink, Loader2, RefreshCw, Video } from 'lucide-react';
import api from '@/lib/api';

/**
 * Scheduler-facing "Start Meeting (Host)" card. The button always points
 * at a freshly-fetched Zoom {@code start_url} — the stored copy carries
 * a {@code zak} JWT that expires roughly 2h after creation, so a stale
 * stored URL drops the trainer to Zoom's sign-in page instead of
 * auto-starting them as host.
 *
 * <p>Freshness guarantees (in priority order):</p>
 * <ol>
 *   <li><b>Mount fetch</b> — every render of the card kicks
 *       {@code GET /api/v1/meetings/{id}/host-start}, which re-fetches
 *       the meeting via {@link com.skyzen.careers.integration.zoom
 *       .ZoomService#getMeeting(long)} and serves a brand-new
 *       {@code zak}-bearing {@code start_url} (no caching;
 *       {@code Cache-Control: no-store}).</li>
 *   <li><b>Focus refetch</b> — when the tab regains focus (trainer
 *       came back after lunch / opened the meeting tab again), we
 *       refetch immediately so the next click is fresh.</li>
 *   <li><b>30-minute interval</b> — for tabs that stay foreground
 *       all day, a background refresh keeps the URL well inside the
 *       ~2h zak window.</li>
 *   <li><b>Manual Refresh button</b> — explicit operator action; same
 *       handler as the others.</li>
 * </ol>
 *
 * <p>The {@code startUrl} prop is accepted for API back-compat but is
 * NOT used as the button target — it's only mentioned in the
 * error-state copy so the trainer knows a stored (possibly stale) copy
 * exists if the fresh fetch fails.</p>
 *
 * <p>Intern/participant surfaces should NOT use this component — they
 * get a plain join button via their own DTOs (no zoomStartUrl).</p>
 */
export default function WebexHostStartCard({
  providerMeetingId,
  startUrl,
}: {
  providerMeetingId: string | null | undefined;
  startUrl?: string | null | undefined;
}) {
  // Intentionally initialize to null — we MUST not link the button to
  // the stored startUrl because its zak may be expired. The button shows
  // a loading state until the mount fetch returns a fresh value.
  const [freshStartUrl, setFreshStartUrl] = useState<string | null>(null);
  const [fetchedAt, setFetchedAt] = useState<Date | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const fetchFresh = useCallback(async () => {
    if (!providerMeetingId) return;
    setBusy(true);
    setErr(null);
    try {
      const res = await api.get<{
        startUrl: string | null;
        available: boolean;
      }>(`/api/v1/meetings/${encodeURIComponent(providerMeetingId)}/host-start`);
      if (res.data?.startUrl) {
        setFreshStartUrl(res.data.startUrl);
      } else {
        setErr('Host link not available — provider returned no start_url.');
      }
      setFetchedAt(new Date());
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to fetch start URL');
    } finally {
      setBusy(false);
    }
  }, [providerMeetingId]);

  // Fetch fresh on mount.
  useEffect(() => {
    void fetchFresh();
  }, [fetchFresh]);

  // Re-fetch when the tab regains focus — the trainer came back to a
  // long-idle meeting tab and is about to click; we want the next click
  // to use a current zak.
  useEffect(() => {
    function onFocus() { void fetchFresh(); }
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [fetchFresh]);

  // Background refresh every 30 minutes for tabs that stay foreground
  // all day. Well inside the ~2h zak expiry, so the button is always
  // ready when the trainer clicks. Skips while the tab is hidden.
  useEffect(() => {
    const id = window.setInterval(() => {
      if (typeof document !== 'undefined'
          && document.visibilityState === 'visible') {
        void fetchFresh();
      }
    }, 30 * 60 * 1000);
    return () => window.clearInterval(id);
  }, [fetchFresh]);

  if (!providerMeetingId) return null;

  const hasFresh = freshStartUrl != null;

  return (
    <div className="space-y-2">
      {hasFresh ? (
        <a
          href={freshStartUrl}
          target="_blank"
          rel="noreferrer noopener"
          className="inline-flex items-center gap-2 rounded-md bg-gradient-to-br from-accent to-accent-dark px-4 py-2 text-sm font-semibold text-white shadow-sm hover:from-accent-dark hover:to-accent-dark focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-dark"
        >
          <Video className="h-4 w-4" />
          Start Meeting (Host)
          <ExternalLink className="h-3 w-3" />
        </a>
      ) : (
        <span
          aria-disabled
          className="inline-flex cursor-not-allowed items-center gap-2 rounded-md bg-slate-200 px-4 py-2 text-sm font-semibold text-slate-500 opacity-80"
        >
          {busy ? (
            <>
              <Loader2 className="h-4 w-4 animate-spin" />
              Fetching host link&hellip;
            </>
          ) : (
            <>
              <Video className="h-4 w-4" />
              Host link unavailable
            </>
          )}
        </span>
      )}

      <div className="rounded-md border border-slate-200 bg-slate-50 p-2.5 text-[11px] text-slate-600">
        <div className="flex items-center justify-between gap-2">
          <span className="leading-snug">
            One-click host link &mdash; fetched fresh on open + tab focus,
            auto-refreshes every 30 min. (Zoom&rsquo;s host token expires
            roughly 2 hours after meeting creation.)
          </span>
          <button
            type="button"
            onClick={fetchFresh}
            disabled={busy}
            title="Re-fetch a fresh host start URL"
            className="inline-flex shrink-0 items-center gap-1 rounded-md border border-slate-300 bg-white px-2 py-1 text-[11px] font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
          >
            {busy ? (
              <Loader2 className="h-3 w-3 animate-spin" />
            ) : (
              <RefreshCw className="h-3 w-3" />
            )}
            {busy ? 'Refreshing' : 'Refresh'}
          </button>
        </div>
        {fetchedAt && !err && (
          <p className="mt-1 text-[10px] text-slate-500">
            Last refresh {fetchedAt.toLocaleTimeString()}
          </p>
        )}
        {err && (
          <p className="mt-1 rounded-md border border-amber-200 bg-amber-50 p-1.5 text-[11px] text-amber-800">
            Couldn&rsquo;t fetch host link: {err}
            {startUrl ? ' A stored copy exists but its host token may be expired — click Refresh to retry.'
              : ' Click Refresh to retry.'}
          </p>
        )}
      </div>
    </div>
  );
}
