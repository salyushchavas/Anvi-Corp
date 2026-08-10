'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import api from '@/lib/careers/api';
import type { InterviewDetail } from './types';

interface Props {
  interview: InterviewDetail;
  open: boolean;
  onClose: () => void;
  onApplied: () => void;
}

interface PresignResponse {
  uploadUrl: string;
  documentId: string;
  storageKey: string;
  expiresAt: string;
}

type UploadState =
  | { kind: 'idle' }
  | { kind: 'uploading'; fileName: string; percent: number; abort: () => void }
  | { kind: 'ready'; fileName: string; documentId: string }
  | { kind: 'error'; fileName: string; message: string };

/**
 * ERM Phase: Manager hire-approval gate. The ERM no longer sets the
 * hire decision (SELECTED/HOLD/REJECTED) here — that moved to the
 * Manager's Hire Approvals queue. This modal submits the scorecard +
 * an optional recommendation + an optional interview recording that
 * the Manager can preview on their hire-approval screen.
 *
 * <p>Recording upload is DIRECT to S3 via a presigned PUT — the browser
 * never routes video bytes through the backend (which caps at 10 MB).
 * Flow: on file pick we ask the backend to mint a presigned URL and a
 * Document row, XHR-PUT the bytes to that URL with a progress bar, then
 * pass the returned {@code documentId} back as {@code recordingDocumentId}
 * when the scorecard is submitted.</p>
 */
export default function CompleteInterviewModal({
  interview,
  open,
  onClose,
  onApplied,
}: Props) {
  const [technicalScore, setTechnicalScore] = useState<number | ''>('');
  const [communicationScore, setCommunicationScore] = useState<number | ''>('');
  const [culturalFitScore, setCulturalFitScore] = useState<number | ''>('');
  const [recommendation, setRecommendation] = useState('');
  const [applicantVisibleNotes, setApplicantVisibleNotes] = useState('');
  const [internalNotes, setInternalNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [upload, setUpload] = useState<UploadState>({ kind: 'idle' });
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!open) return;
    setTechnicalScore('');
    setCommunicationScore('');
    setCulturalFitScore('');
    setRecommendation('');
    setApplicantVisibleNotes('');
    setInternalNotes('');
    setErr(null);
    setUpload((prev) => {
      if (prev.kind === 'uploading') prev.abort();
      return { kind: 'idle' };
    });
    if (fileInputRef.current) fileInputRef.current.value = '';
  }, [open]);

  // applicantVisibleNotes is optional now — no field-level blocker.
  // Kept useMemo shape so future required-field additions have a place
  // to land without re-wiring canSubmit.
  const missingFields = useMemo<string[]>(() => [], []);
  const uploadInFlight = upload.kind === 'uploading';
  const canSubmit = missingFields.length === 0 && !submitting && !uploadInFlight;

  if (!open) return null;

  async function onPickRecording(file: File) {
    if (!file.type.startsWith('video/')) {
      setUpload({
        kind: 'error',
        fileName: file.name,
        message: 'Please select a video file (mp4 / mov / webm).',
      });
      return;
    }
    let presign: PresignResponse;
    try {
      const res = await api.post<PresignResponse>(
        `/api/v1/erm/interviews/${interview.id}/recording/presign-upload`,
        {
          fileName: file.name,
          contentType: file.type,
          fileSize: file.size,
        },
      );
      presign = res.data;
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setUpload({
        kind: 'error',
        fileName: file.name,
        message:
          ax.response?.data?.error ??
          (e instanceof Error ? e.message : 'Could not start upload'),
      });
      return;
    }

    // Direct-to-S3 PUT via XHR so we get upload progress events. Fetch
    // does not expose upload progress in the browser today.
    const xhr = new XMLHttpRequest();
    const abort = () => xhr.abort();
    setUpload({ kind: 'uploading', fileName: file.name, percent: 0, abort });

    xhr.open('PUT', presign.uploadUrl);
    xhr.setRequestHeader('Content-Type', file.type);
    xhr.upload.onprogress = (ev) => {
      if (!ev.lengthComputable) return;
      const percent = Math.min(99, Math.round((ev.loaded / ev.total) * 100));
      setUpload({ kind: 'uploading', fileName: file.name, percent, abort });
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        setUpload({
          kind: 'ready',
          fileName: file.name,
          documentId: presign.documentId,
        });
      } else {
        setUpload({
          kind: 'error',
          fileName: file.name,
          message: `S3 rejected the upload (HTTP ${xhr.status}). Try again.`,
        });
      }
    };
    xhr.onerror = () => {
      setUpload({
        kind: 'error',
        fileName: file.name,
        message: 'Network error while uploading. Try again.',
      });
    };
    xhr.onabort = () => {
      setUpload({ kind: 'idle' });
    };
    xhr.send(file);
  }

  function clearRecording() {
    if (upload.kind === 'uploading') upload.abort();
    setUpload({ kind: 'idle' });
    if (fileInputRef.current) fileInputRef.current.value = '';
  }

  async function submit() {
    setErr(null);
    // applicantVisibleNotes is optional at scorecard-submit time
    // (F8 revision) — no minimum-length gate here.
    setSubmitting(true);
    try {
      await api.post(`/api/v1/erm/interviews/${interview.id}/complete`, {
        technicalScore: technicalScore === '' ? null : technicalScore,
        communicationScore: communicationScore === '' ? null : communicationScore,
        culturalFitScore: culturalFitScore === '' ? null : culturalFitScore,
        overallRecommendation: recommendation || null,
        applicantVisibleNotes: applicantVisibleNotes.trim(),
        internalNotes: internalNotes.trim() || null,
        recordingDocumentId: upload.kind === 'ready' ? upload.documentId : null,
      });
      onApplied();
      onClose();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(
        ax.response?.data?.error ??
          (e instanceof Error ? e.message : 'Submission failed'),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="flex max-h-[90vh] w-full max-w-2xl flex-col overflow-hidden rounded-lg bg-white shadow-xl">
        <div className="min-h-0 flex-1 overflow-y-auto p-6">
          <h2 className="text-lg font-semibold text-slate-900">
            Submit interview scorecard
          </h2>
          <p className="mt-1 text-xs text-slate-600">
            Your scores and notes go to the Manager for the final hire decision.
          </p>

          <div className="mt-4 space-y-4">
            <div className="grid grid-cols-3 gap-2">
              <ScoreInput label="Technical" value={technicalScore} onChange={setTechnicalScore} />
              <ScoreInput label="Communication" value={communicationScore} onChange={setCommunicationScore} />
              <ScoreInput label="Domain Knowledge" value={culturalFitScore} onChange={setCulturalFitScore} />
            </div>

            <div>
              <label className="text-sm font-medium text-slate-800">
                Interview recording{' '}
                <span className="text-xs text-slate-500">(optional)</span>
              </label>
              <RecordingUploader
                state={upload}
                onPick={onPickRecording}
                onClear={clearRecording}
                inputRef={fileInputRef}
              />
              <p className="mt-1 text-[11px] text-slate-500">
                Uploads directly to secure storage — up to 2 GiB. The Manager
                previews it on the hire-approval screen.
              </p>
            </div>

            <div>
              <label className="text-sm font-medium text-slate-800">
                Overall recommendation
              </label>
              <select
                value={recommendation}
                onChange={(e) => setRecommendation(e.target.value)}
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
              >
                <option value="">No recommendation</option>
                <option value="HIRE">Hire</option>
                <option value="REJECT">Reject</option>
                <option value="HOLD">Hold</option>
              </select>
              <p className="mt-1 text-[11px] text-slate-500">
                Advisory only — the Manager makes the binding decision.
              </p>
            </div>

            <div>
              <label className="text-sm font-medium text-slate-800">
                Applicant-visible notes{' '}
                <span className="text-xs text-slate-500">(optional)</span>
              </label>
              <textarea
                value={applicantVisibleNotes}
                onChange={(e) => setApplicantVisibleNotes(e.target.value)}
                rows={4}
                className="mt-1 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm"
              />
              <div className="mt-1 text-right text-[11px] text-slate-500">
                {applicantVisibleNotes.trim().length} characters
              </div>
              <p className="mt-1 text-[11px] text-slate-500">
                Sent to the applicant when the Manager finalizes the hire decision.
                Leave blank to send with no visible message.
              </p>
            </div>

            <div>
              <label className="text-sm font-medium text-slate-800">
                Internal notes
              </label>
              <textarea
                value={internalNotes}
                onChange={(e) => setInternalNotes(e.target.value)}
                rows={3}
                maxLength={5000}
                className="mt-1 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm"
              />
            </div>

            {err && (
              <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
                {err}
              </p>
            )}
          </div>
        </div>

        <div className="flex flex-shrink-0 items-center justify-between gap-3 border-t border-slate-200 bg-white px-6 py-3">
          <p className="text-[11px] text-slate-500">
            {uploadInFlight
              ? `Uploading recording (${upload.percent}%) — submit disabled.`
              : missingFields.length > 0
              ? `Required: ${missingFields.join(' · ')}`
              : 'Ready to submit. Manager will action the hire decision.'}
          </p>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-md border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={submit}
              disabled={!canSubmit}
              title={
                uploadInFlight
                  ? 'Recording upload in progress'
                  : missingFields.length > 0
                  ? `Fill required fields: ${missingFields.join(', ')}`
                  : undefined
              }
              className="rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {submitting ? 'Saving…' : 'Submit scorecard'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function ScoreInput({
  label,
  value,
  onChange,
}: {
  label: string;
  value: number | '';
  onChange: (v: number | '') => void;
}) {
  return (
    <div>
      <label className="text-xs font-medium text-slate-700">{label} (1-10)</label>
      <input
        type="number"
        min={1}
        max={10}
        value={value}
        onChange={(e) =>
          onChange(e.target.value === '' ? '' : Number(e.target.value))
        }
        className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
      />
    </div>
  );
}

function RecordingUploader({
  state,
  onPick,
  onClear,
  inputRef,
}: {
  state: UploadState;
  onPick: (file: File) => void;
  onClear: () => void;
  inputRef: React.MutableRefObject<HTMLInputElement | null>;
}) {
  return (
    <div className="mt-1">
      <input
        ref={inputRef}
        type="file"
        accept="video/*"
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) onPick(file);
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
            <span className="truncate font-mono text-slate-700">
              {state.fileName}
            </span>
            <button
              type="button"
              onClick={onClear}
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
            onClick={onClear}
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
              onClick={onClear}
              className="rounded border border-slate-200 bg-white px-2 py-0.5 text-[11px] font-medium text-slate-700 hover:bg-slate-50"
            >
              Clear
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
