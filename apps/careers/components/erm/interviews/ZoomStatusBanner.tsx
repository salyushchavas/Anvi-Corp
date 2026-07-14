'use client';

/**
 * ERM-facing Zoom outcome banner for the interview detail page. Replaces
 * the previous "missing link with no explanation" UX — if a Zoom call
 * failed or wasn't attempted, the ERM sees exactly why, plus a one-click
 * Regenerate action when the integration is configured.
 */

import { useState } from 'react';
import { AlertTriangle, CheckCircle2, Link as LinkIcon, RefreshCw, ServerOff } from 'lucide-react';
import api from '@/lib/api';
import type { InterviewDetail, ZoomStatus } from './types';

interface Props {
  interview: InterviewDetail;
  onUpdated: (next: InterviewDetail) => void;
}

const TONE_BY_STATUS: Record<
  ZoomStatus,
  { tone: string; icon: React.ReactNode; title: string; body: string }
> = {
  OK: {
    tone: 'border-green-200 bg-green-50 text-green-900',
    icon: <CheckCircle2 className="h-4 w-4" />,
    title: 'Zoom meeting created',
    body: 'The join link below is auto-generated. Reschedule will update the same meeting.',
  },
  MANUAL_LINK: {
    tone: 'border-slate-200 bg-slate-50 text-slate-800',
    icon: <LinkIcon className="h-4 w-4" />,
    title: 'Manual Zoom link in use',
    body: 'This interview uses an ERM-supplied link; the Zoom API was not called.',
  },
  NOT_CONFIGURED: {
    tone: 'border-amber-200 bg-amber-50 text-amber-900',
    icon: <ServerOff className="h-4 w-4" />,
    title: 'Zoom is not configured',
    body:
      'No Zoom credentials are set on the server. Scheduling still works; once an admin sets '
      + 'ZOOM_ACCOUNT_ID / ZOOM_CLIENT_ID / ZOOM_CLIENT_SECRET, Regenerate to attach a link.',
  },
  DISABLED: {
    tone: 'border-amber-200 bg-amber-50 text-amber-900',
    icon: <ServerOff className="h-4 w-4" />,
    title: 'Zoom is force-disabled',
    body: 'ZOOM_ENABLED=false on the server. Unset the kill-switch to use the configured credentials.',
  },
  CREATE_FAILED: {
    tone: 'border-red-200 bg-red-50 text-red-900',
    icon: <AlertTriangle className="h-4 w-4" />,
    title: "Zoom link couldn't be created",
    body: 'The interview is saved. Use Regenerate to retry once the underlying issue is fixed.',
  },
  UPDATE_FAILED: {
    tone: 'border-red-200 bg-red-50 text-red-900',
    icon: <AlertTriangle className="h-4 w-4" />,
    title: 'Zoom meeting update failed on reschedule',
    body:
      'The stored Zoom meeting may now disagree with the new time. Use Regenerate to '
      + 'recreate the meeting at the current time + zone.',
  },
  UNKNOWN: {
    tone: 'border-slate-200 bg-slate-50 text-slate-700',
    icon: <ServerOff className="h-4 w-4" />,
    title: 'Zoom status unknown',
    body: 'No recent Zoom outcome to report for this interview.',
  },
};

export default function ZoomStatusBanner({ interview, onUpdated }: Props) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const status = interview.zoomStatus ?? 'UNKNOWN';

  // Suppress the banner when the meeting was created cleanly. The host
  // start card directly below already says "Start Meeting (Host)" with
  // the URL — the "Zoom meeting created" banner above it is redundant
  // and just adds chrome. Failure / missing-config states still render
  // because those are the only times ERM has something to act on.
  if (status === 'OK') return null;

  const cfg = TONE_BY_STATUS[status];

  // Regenerate is meaningful only while the interview is open AND the
  // backend integration could plausibly succeed. NOT_CONFIGURED/DISABLED
  // would just 409 — keep the button hidden until the operator fixes the
  // server-side config.
  const canRegenerate =
    interview.status === 'SCHEDULED'
    && (status === 'CREATE_FAILED' || status === 'UPDATE_FAILED');

  async function regenerate() {
    setBusy(true);
    setErr(null);
    try {
      const res = await api.post<InterviewDetail>(
        `/api/v1/erm/interviews/${interview.id}/zoom/regenerate`,
      );
      onUpdated(res.data);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(
        ax.response?.data?.error
          ?? (e instanceof Error ? e.message : 'Regenerate failed'),
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={'mt-3 rounded-md border p-3 text-xs ' + cfg.tone}>
      <div className="flex items-start gap-2">
        <span className="mt-0.5">{cfg.icon}</span>
        <div className="flex-1">
          <p className="font-semibold">{cfg.title}</p>
          <p className="mt-0.5">{cfg.body}</p>
          {interview.zoomErrorMessage && (
            <p className="mt-1 break-all font-mono text-[11px] opacity-80">
              {interview.zoomErrorMessage}
            </p>
          )}
          {err && (
            <p className="mt-1 rounded-sm bg-white/60 p-1.5 font-mono text-[11px]">
              {err}
            </p>
          )}
        </div>
        {canRegenerate && (
          <button
            type="button"
            onClick={regenerate}
            disabled={busy}
            className="inline-flex items-center gap-1 rounded-md border border-current px-2 py-1 text-xs font-semibold hover:bg-white/60 disabled:opacity-60"
          >
            <RefreshCw className={'h-3 w-3 ' + (busy ? 'animate-spin' : '')} />
            {busy ? 'Regenerating…' : 'Regenerate'}
          </button>
        )}
      </div>
    </div>
  );
}
