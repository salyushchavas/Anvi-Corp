'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { CheckCircle2, Video } from 'lucide-react';
import api from '@/lib/careers/api';
import RecordingUploader from '@/components/dashboard/RecordingUploader';

/**
 * ERM standalone interview-recording upload — no interview row, no
 * scorecard flow. Reuses the shipped direct-to-S3 presign pattern via
 * a small standalone endpoint that stores the Document with
 * ownerUserId = intern.userId so the unified gallery can group it
 * under that intern's Interview folder.
 */
interface ActiveEvalueeOption {
  lifecycleId: string;
  internUserId: string;
  internName: string | null;
  employeeId: string | null;
}

export default function ErmUploadInterviewRecordingPage() {
  const [interns, setInterns] = useState<ActiveEvalueeOption[]>([]);
  const [selected, setSelected] = useState<string>('');
  const [uploaded, setUploaded] = useState<string | null>(null);
  const [loadErr, setLoadErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await api.get<ActiveEvalueeOption[]>(
        '/api/v1/recording-gallery/active-interns');
      setInterns(res.data);
      setLoadErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setLoadErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load interns');
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const uploader = selected ? (
    <RecordingUploader
      key={selected /* remount on intern change so state resets */}
      presignEndpoint={'/api/v1/erm/upload-interview-recording/presign'}
      presignExtras={{ internLifecycleId: selected }}
      onReady={(docId, name) => setUploaded(`${name} · ${docId}`)}
      onClear={() => setUploaded(null)}
      helperText="Uploads bypass the backend and go directly to secure storage. Video/* only."
    />
  ) : null;

  return (
    <div className="mx-auto max-w-2xl space-y-4 p-6">
      <div>
        <p className="text-xs text-slate-500">
          <Link href="/careers/erm" className="hover:text-slate-700">← ERM home</Link>
        </p>
        <h1 className="mt-1 text-xl font-semibold text-slate-900">Upload Interview Recording</h1>
        <p className="text-xs text-slate-500">
          Pick the intern and drop the video. Visible to ERM + Manager
          immediately — interview recordings don't require the
          Manager approval gate that evaluation recordings do.
        </p>
      </div>

      {loadErr && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {loadErr}
        </p>
      )}

      <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <label className="block">
          <span className="text-xs font-semibold text-slate-700">Intern *</span>
          <select
            value={selected}
            onChange={(e) => { setSelected(e.target.value); setUploaded(null); }}
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

        <div>
          <span className="text-xs font-semibold text-slate-700 inline-flex items-center gap-1">
            <Video className="h-3.5 w-3.5" /> Video *
          </span>
          {selected ? (
            <div className="mt-1">{uploader}</div>
          ) : (
            <p className="mt-2 rounded-md border border-dashed border-slate-300 bg-slate-50 p-4 text-center text-xs text-slate-500">
              Pick an intern first.
            </p>
          )}
        </div>

        {uploaded && (
          <p className="rounded-md border border-emerald-200 bg-emerald-50 p-2 text-xs text-emerald-800">
            <CheckCircle2 className="mr-1 inline h-3.5 w-3.5" />
            Uploaded — visible in the Recording Gallery under this intern's Interview folder.
          </p>
        )}
      </section>
    </div>
  );
}
