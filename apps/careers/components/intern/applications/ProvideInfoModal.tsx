'use client';

import { useState } from 'react';
import api from '@/lib/api';

interface Props {
  applicationId: string;
  fields: string[];
  onClose: () => void;
  onProvided: () => void;
}

export default function ProvideInfoModal({
  applicationId,
  fields,
  onClose,
  onProvided,
}: Props) {
  const [resumeFileId, setResumeFileId] = useState('');
  const [workAuthType, setWorkAuthType] = useState('');
  const [workAuthValidUntil, setWorkAuthValidUntil] = useState('');
  const [educationSchool, setEducationSchool] = useState('');
  const [educationDegree, setEducationDegree] = useState('');
  const [freeTextResponse, setFreeTextResponse] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const need = (k: string) => fields.includes(k);

  async function submit() {
    setErr(null);
    if (need('resume') && !resumeFileId.trim()) {
      setErr('Resume file id is required.');
      return;
    }
    if (need('other') && !freeTextResponse.trim()) {
      setErr('Additional details are required.');
      return;
    }
    setSubmitting(true);
    try {
      const body: Record<string, unknown> = {};
      if (need('resume') && resumeFileId.trim()) {
        body.resumeFileId = resumeFileId.trim();
      }
      if (need('workAuth')) {
        body.workAuthUpdate = {
          type: workAuthType || null,
          validUntil: workAuthValidUntil || null,
        };
      }
      if (need('education')) {
        body.educationUpdate = {
          school: educationSchool || null,
          degree: educationDegree || null,
        };
      }
      if (need('other')) {
        body.freeTextResponse = freeTextResponse.trim();
      }
      await api.post(
        `/api/v1/applications/${applicationId}/provide-info`,
        body,
      );
      onProvided();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(
        ax.response?.data?.error ??
          (e instanceof Error ? e.message : 'Failed to provide info'),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="max-h-[85vh] w-full max-w-lg overflow-y-auto rounded-lg bg-white p-6 shadow-xl">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold text-slate-900">
            Provide requested information
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md p-1.5 text-slate-500 hover:bg-slate-100"
            aria-label="Close"
          >
            ✕
          </button>
        </div>

        <div className="mt-4 space-y-4">
          {need('resume') && (
            <div>
              <label className="text-sm font-medium text-slate-800">
                Resume file ID <span className="text-red-600">*</span>
              </label>
              <input
                value={resumeFileId}
                onChange={(e) => setResumeFileId(e.target.value)}
                placeholder="UUID of an uploaded resume"
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
              />
              <p className="mt-1 text-[11px] text-slate-500">
                Upload a new resume from your profile first, then paste its
                ID here.
              </p>
            </div>
          )}
          {need('workAuth') && (
            <div className="grid grid-cols-2 gap-2">
              <div>
                <label className="text-sm font-medium text-slate-800">
                  Work auth type
                </label>
                <input
                  value={workAuthType}
                  onChange={(e) => setWorkAuthType(e.target.value)}
                  placeholder="OPT / CPT / GC / USC"
                  className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                />
              </div>
              <div>
                <label className="text-sm font-medium text-slate-800">
                  Valid until
                </label>
                <input
                  type="date"
                  value={workAuthValidUntil}
                  onChange={(e) => setWorkAuthValidUntil(e.target.value)}
                  className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                />
              </div>
            </div>
          )}
          {need('education') && (
            <div className="grid grid-cols-2 gap-2">
              <div>
                <label className="text-sm font-medium text-slate-800">
                  School
                </label>
                <input
                  value={educationSchool}
                  onChange={(e) => setEducationSchool(e.target.value)}
                  className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                />
              </div>
              <div>
                <label className="text-sm font-medium text-slate-800">
                  Degree
                </label>
                <input
                  value={educationDegree}
                  onChange={(e) => setEducationDegree(e.target.value)}
                  className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                />
              </div>
            </div>
          )}
          {need('other') && (
            <div>
              <label className="text-sm font-medium text-slate-800">
                Additional details <span className="text-red-600">*</span>
              </label>
              <textarea
                value={freeTextResponse}
                onChange={(e) => setFreeTextResponse(e.target.value)}
                rows={4}
                className="mt-1 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm"
              />
            </div>
          )}

          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
              {err}
            </p>
          )}
        </div>

        <div className="mt-6 flex justify-end gap-2">
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
            disabled={submitting}
            className="rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            {submitting ? 'Submitting…' : 'Submit information'}
          </button>
        </div>
      </div>
    </div>
  );
}
