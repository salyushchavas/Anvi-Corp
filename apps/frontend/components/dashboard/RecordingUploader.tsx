'use client';

import { useRef, useState } from 'react';
import api from '@/lib/careers/api';

/**
 * Shared recording-upload widget. Extracted from the ERM interview
 * scorecard modal so the same visual + state machine (idle → uploading
 * with progress bar → ready / error) can be reused by any flow that
 * uploads a video direct-to-S3 via a presigned PUT.
 *
 * <p>The caller supplies the presign endpoint URL (each flow has its
 * own); the widget POSTs to it, XHR-PUTs the bytes to the returned
 * {@code uploadUrl}, and invokes {@code onReady(documentId, fileName)}
 * when S3 acks. Errors surface inline; the caller keeps track of the
 * final {@code documentId} so it can be handed to whatever save
 * endpoint the parent flow uses.</p>
 */
export interface PresignResponse {
  uploadUrl: string;
  documentId: string;
  storageKey: string;
  expiresAt: string;
}

export type UploadState =
  | { kind: 'idle' }
  | { kind: 'uploading'; fileName: string; percent: number; abort: () => void }
  | { kind: 'ready'; fileName: string; documentId: string }
  | { kind: 'error'; fileName: string; message: string };

interface Props {
  /** Backend endpoint that returns a {@link PresignResponse}. */
  presignEndpoint: string;
  /** Fires when S3 successfully accepts the upload. */
  onReady?: (documentId: string, fileName: string) => void;
  /** Fires when the widget returns to idle (cleared / cancelled). */
  onClear?: () => void;
  /** Optional starting state — e.g. when re-mounting a form with a
   *  previously uploaded recording already saved. */
  initial?: UploadState;
  /** MIME filter for the file picker (default {@code video/*}). */
  accept?: string;
  /** Helper line shown beneath the widget. */
  helperText?: string;
}

export default function RecordingUploader({
  presignEndpoint,
  onReady,
  onClear,
  initial,
  accept = 'video/*',
  helperText,
}: Props) {
  const [state, setState] = useState<UploadState>(initial ?? { kind: 'idle' });
  const inputRef = useRef<HTMLInputElement | null>(null);

  async function onPick(file: File) {
    if (accept.startsWith('video/') && !file.type.startsWith('video/')) {
      setState({
        kind: 'error',
        fileName: file.name,
        message: 'Please select a video file (mp4 / mov / webm).',
      });
      return;
    }
    let presign: PresignResponse;
    try {
      const res = await api.post<PresignResponse>(presignEndpoint, {
        fileName: file.name,
        contentType: file.type,
        fileSize: file.size,
      });
      presign = res.data;
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setState({
        kind: 'error',
        fileName: file.name,
        message:
          ax.response?.data?.error ??
          (e instanceof Error ? e.message : 'Could not start upload'),
      });
      return;
    }

    // Direct-to-S3 PUT via XHR — fetch() doesn't expose upload progress.
    const xhr = new XMLHttpRequest();
    const abort = () => xhr.abort();
    setState({ kind: 'uploading', fileName: file.name, percent: 0, abort });

    xhr.open('PUT', presign.uploadUrl);
    xhr.setRequestHeader('Content-Type', file.type);
    xhr.upload.onprogress = (ev) => {
      if (!ev.lengthComputable) return;
      const percent = Math.min(99, Math.round((ev.loaded / ev.total) * 100));
      setState({ kind: 'uploading', fileName: file.name, percent, abort });
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        setState({ kind: 'ready', fileName: file.name, documentId: presign.documentId });
        onReady?.(presign.documentId, file.name);
      } else {
        setState({
          kind: 'error',
          fileName: file.name,
          message: `S3 rejected the upload (HTTP ${xhr.status}). Try again.`,
        });
      }
    };
    xhr.onerror = () => {
      setState({
        kind: 'error',
        fileName: file.name,
        message: 'Network error while uploading. Try again.',
      });
    };
    xhr.onabort = () => {
      setState({ kind: 'idle' });
      onClear?.();
    };
    xhr.send(file);
  }

  function clear() {
    if (state.kind === 'uploading') state.abort();
    setState({ kind: 'idle' });
    if (inputRef.current) inputRef.current.value = '';
    onClear?.();
  }

  return (
    <div className="mt-1">
      <input
        ref={inputRef}
        type="file"
        accept={accept}
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) void onPick(file);
        }}
      />
      {state.kind === 'idle' && (
        <button
          type="button"
          onClick={() => inputRef.current?.click()}
          className="w-full rounded-md border border-dashed border-slate-300 bg-slate-50 px-3 py-3 text-sm font-medium text-slate-700 hover:bg-slate-100"
        >
          Choose video file…
        </button>
      )}
      {state.kind === 'uploading' && (
        <div className="rounded-md border border-slate-200 bg-white p-3">
          <div className="flex items-center justify-between gap-2 text-xs">
            <span className="truncate font-mono text-slate-700">{state.fileName}</span>
            <button
              type="button"
              onClick={clear}
              className="shrink-0 rounded border border-slate-200 px-2 py-0.5 text-[11px] font-medium text-slate-600 hover:bg-slate-50"
            >
              Cancel
            </button>
          </div>
          <div className="mt-2 h-2 w-full overflow-hidden rounded-full bg-slate-100">
            <div
              className="h-full bg-brand-600 transition-[width] duration-200"
              style={{ width: `${state.percent}%` }}
            />
          </div>
          <p className="mt-1 text-[11px] text-slate-500">
            Uploading directly to S3 — {state.percent}%
          </p>
        </div>
      )}
      {state.kind === 'ready' && (
        <div className="flex items-center justify-between gap-2 rounded-md border border-green-200 bg-green-50 px-3 py-2 text-xs">
          <div className="min-w-0">
            <p className="font-semibold text-green-900">Uploaded ✓</p>
            <p className="truncate font-mono text-green-800">{state.fileName}</p>
          </div>
          <button
            type="button"
            onClick={clear}
            className="shrink-0 rounded border border-green-300 bg-white px-2 py-0.5 text-[11px] font-medium text-green-800 hover:bg-green-100"
          >
            Replace
          </button>
        </div>
      )}
      {state.kind === 'error' && (
        <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-900">
          <p className="font-semibold">Upload failed</p>
          <p className="mt-0.5">{state.message}</p>
          <div className="mt-2 flex gap-2">
            <button
              type="button"
              onClick={() => inputRef.current?.click()}
              className="rounded border border-red-300 bg-white px-2 py-0.5 text-[11px] font-medium text-red-800 hover:bg-red-100"
            >
              Retry
            </button>
            <button
              type="button"
              onClick={clear}
              className="rounded border border-slate-200 bg-white px-2 py-0.5 text-[11px] font-medium text-slate-700 hover:bg-slate-50"
            >
              Clear
            </button>
          </div>
        </div>
      )}
      {helperText && (
        <p className="mt-1 text-[11px] text-slate-500">{helperText}</p>
      )}
    </div>
  );
}
