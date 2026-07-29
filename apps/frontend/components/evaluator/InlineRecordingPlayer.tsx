'use client';

import { useCallback, useState } from 'react';
import { Download, Video } from 'lucide-react';
import api from '@/lib/careers/api';

/**
 * Lightweight per-project recording playback for embed inside an
 * expandable project row on the evaluator's Active Evaluee detail hub.
 *
 * <p>Reuses the existing per-evaluation download endpoint —
 * {@code GET /api/v1/evaluation-recordings/{evaluationId}/download-url}
 * (RBAC includes EVALUATOR). Presigned URL is fetched lazily on the
 * user's Play click so we never mint an unused signature (30-min TTL)
 * for a row the evaluator never opens.</p>
 *
 * <p>Renders nothing when {@code evaluationId} is null. Renders a
 * neutral "no recording attached" hint when the endpoint responds 404
 * (evaluation exists but no recording was uploaded).</p>
 */
interface Props {
  evaluationId: string | null | undefined;
}

interface DownloadUrlResponse {
  downloadUrl: string;
  expiresAt: string;
}

type State =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; url: string }
  | { kind: 'error'; message: string }
  | { kind: 'missing' };

export default function InlineRecordingPlayer({ evaluationId }: Props) {
  const [state, setState] = useState<State>({ kind: 'idle' });

  const play = useCallback(async () => {
    if (!evaluationId) return;
    setState({ kind: 'loading' });
    try {
      const res = await api.get<DownloadUrlResponse>(
        `/api/v1/evaluation-recordings/${evaluationId}/download-url`,
      );
      setState({ kind: 'ready', url: res.data.downloadUrl });
    } catch (e) {
      const ax = e as { response?: { status?: number; data?: { error?: string } }; message?: string };
      if (ax.response?.status === 404) {
        setState({ kind: 'missing' });
        return;
      }
      setState({
        kind: 'error',
        message: ax.response?.data?.error ?? ax.message ?? 'Failed to load playback URL',
      });
    }
  }, [evaluationId]);

  if (!evaluationId) return null;

  return (
    <div className="rounded-md border border-slate-200 bg-white p-3">
      <div className="flex items-center justify-between gap-2">
        <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
          Session recording
        </p>
        <div className="flex items-center gap-1.5">
          {state.kind === 'idle' && (
            <button type="button" onClick={play}
              className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-2.5 py-1 text-xs font-semibold text-white hover:bg-brand-800">
              <Video className="h-3 w-3" /> Play
            </button>
          )}
          {state.kind === 'loading' && (
            <span className="text-[11px] text-slate-500">Loading…</span>
          )}
          {state.kind === 'ready' && (
            <a href={state.url} download
              className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50">
              <Download className="h-3 w-3" /> Download
            </a>
          )}
        </div>
      </div>
      {state.kind === 'ready' && (
        <video
          controls
          src={state.url}
          className="mt-2 w-full max-h-96 rounded-md bg-black"
        />
      )}
      {state.kind === 'missing' && (
        <p className="mt-1 text-[11px] text-slate-500">
          No recording was uploaded for this evaluation.
        </p>
      )}
      {state.kind === 'error' && (
        <p className="mt-1 rounded-md border border-red-200 bg-red-50 p-1.5 text-[11px] text-red-800">
          {state.message}
        </p>
      )}
    </div>
  );
}
