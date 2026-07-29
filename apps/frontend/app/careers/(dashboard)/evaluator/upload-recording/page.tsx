'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { CheckCircle2, Video } from 'lucide-react';
import api from '@/lib/careers/api';
import RecordingUploader from '@/components/dashboard/RecordingUploader';

/**
 * Evaluator standalone evaluation-recording upload — dedicated form
 * (INTERN + MONTH + SCOPE + PROJECT NAME + video). The prepare step
 * finds-or-creates the target {@link InternEvaluation} and
 * auto-retrieves the project title from the projects table; on
 * successful upload the recording flows through the shipped approval
 * gate (PENDING_APPROVAL → manager review → APPROVED /
 * REVISION_REQUESTED) exactly like the compose-page upload.
 */
interface ActiveEvalueeOption {
  lifecycleId: string;
  internUserId: string;
  internName: string | null;
  employeeId: string | null;
}
interface PrepareResponse {
  evaluationId: string;
  projectName: string;
  projectNameAutoRetrieved: boolean;
  scope: string;
  monthYear: string;
}

const SCOPE_OPTIONS: { value: 'P1' | 'P2' | 'P1_P2'; label: string }[] = [
  { value: 'P1',    label: 'Project 1' },
  { value: 'P2',    label: 'Project 2' },
  { value: 'P1_P2', label: 'P1 + P2 together' },
];

export default function EvaluatorUploadRecordingPage() {
  const [interns, setInterns] = useState<ActiveEvalueeOption[]>([]);
  const [lifecycleId, setLifecycleId] = useState<string>('');
  const [monthYear, setMonthYear] = useState<string>(defaultMonth());
  const [scope, setScope] = useState<'P1' | 'P2' | 'P1_P2'>('P1');
  const [projectName, setProjectName] = useState<string>('');
  const [prepared, setPrepared] = useState<PrepareResponse | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [uploaded, setUploaded] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await api.get<ActiveEvalueeOption[]>(
        '/api/v1/recording-gallery/active-interns');
      setInterns(res.data);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load interns');
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  // Reset prepared eval whenever the caller changes a target field.
  useEffect(() => { setPrepared(null); setUploaded(null); },
    [lifecycleId, monthYear, scope, projectName]);

  async function prepare() {
    if (!lifecycleId) { setErr('Pick an intern.'); return; }
    setErr(null);
    setPreparing(true);
    try {
      const res = await api.post<PrepareResponse>(
        '/api/v1/evaluator/upload-recording/prepare',
        {
          internLifecycleId: lifecycleId,
          monthYear,
          scope,
          projectNameOverride: projectName.trim() || null,
        });
      setPrepared(res.data);
      if (res.data.projectNameAutoRetrieved && !projectName) {
        setProjectName(res.data.projectName);
      }
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to prepare upload');
    } finally {
      setPreparing(false);
    }
  }

  const uploader = prepared ? (
    <RecordingUploader
      key={prepared.evaluationId}
      presignEndpoint={
        `/api/v1/evaluator/evaluations/${prepared.evaluationId}/recording/presign-upload`}
      onReady={(docId) => {
        // Persist the recording ref on the evaluation (fires the
        // shipped approval-gate flow: PENDING_APPROVAL + manager
        // notification). scope carries here so the gallery folder
        // renders correctly for combined P1+P2 uploads.
        void api.post(
          `/api/v1/evaluator/evaluations/${prepared.evaluationId}/recording`,
          { recordingDocumentId: docId, scope: prepared.scope },
        ).then(() => setUploaded('Uploaded — awaiting manager approval.'))
          .catch((e) => {
            const ax = e as { response?: { data?: { error?: string } }; message?: string };
            setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to save recording');
          });
      }}
      helperText="Uploads bypass the backend and go directly to secure storage. Video/* only. Every upload enters the Manager Recording Approvals queue."
    />
  ) : null;

  return (
    <div className="mx-auto max-w-2xl space-y-4 p-6">
      <div>
        <p className="text-xs text-slate-500">
          <Link href="/careers/evaluator" className="hover:text-slate-700">← Evaluator home</Link>
        </p>
        <h1 className="mt-1 text-xl font-semibold text-slate-900">Upload Recording</h1>
        <p className="text-xs text-slate-500">
          Every upload enters the Manager Recording Approvals queue —
          your playback + gallery visibility unlock once the manager
          approves.
        </p>
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <label className="block">
          <span className="text-xs font-semibold text-slate-700">Intern *</span>
          <select
            value={lifecycleId}
            onChange={(e) => setLifecycleId(e.target.value)}
            className="mt-1 block w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
          >
            <option value="">— pick an intern —</option>
            {interns.map((i) => (
              <option key={i.lifecycleId} value={i.lifecycleId}>
                {i.internName ?? '(unknown)'} {i.employeeId && ` · ${i.employeeId}`}
              </option>
            ))}
          </select>
        </label>

        <label className="block">
          <span className="text-xs font-semibold text-slate-700">Month *</span>
          <input
            type="month"
            value={monthYear}
            onChange={(e) => setMonthYear(e.target.value)}
            className="mt-1 block w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
          />
        </label>

        <label className="block">
          <span className="text-xs font-semibold text-slate-700">Project *</span>
          <select
            value={scope}
            onChange={(e) => setScope(e.target.value as 'P1' | 'P2' | 'P1_P2')}
            className="mt-1 block w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
          >
            {SCOPE_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        </label>

        <label className="block">
          <span className="text-xs font-semibold text-slate-700">
            Project name *
            {prepared?.projectNameAutoRetrieved && (
              <span className="ml-2 rounded bg-emerald-100 px-1.5 py-0.5 text-[10px] font-semibold text-emerald-800">
                auto-retrieved
              </span>
            )}
          </span>
          <input
            type="text"
            value={projectName}
            onChange={(e) => setProjectName(e.target.value)}
            placeholder={scope === 'P1_P2'
              ? 'Required — combined P1+P2 has no single project row'
              : 'Auto-fills from projects table when known'}
            className="mt-1 block w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
          />
        </label>

        {!prepared && (
          <button
            type="button"
            onClick={prepare}
            disabled={preparing || !lifecycleId}
            className="rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            {preparing ? 'Preparing…' : 'Continue to upload'}
          </button>
        )}

        {prepared && (
          <div>
            <span className="text-xs font-semibold text-slate-700 inline-flex items-center gap-1">
              <Video className="h-3.5 w-3.5" /> Video *
            </span>
            <div className="mt-1">{uploader}</div>
            <button
              type="button"
              onClick={() => setPrepared(null)}
              className="mt-2 text-[11px] text-brand-700 hover:underline"
            >
              Change intern / month / project
            </button>
          </div>
        )}

        {uploaded && (
          <p className="rounded-md border border-emerald-200 bg-emerald-50 p-2 text-xs text-emerald-800">
            <CheckCircle2 className="mr-1 inline h-3.5 w-3.5" />
            {uploaded}
          </p>
        )}
      </section>
    </div>
  );
}

function defaultMonth(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}
