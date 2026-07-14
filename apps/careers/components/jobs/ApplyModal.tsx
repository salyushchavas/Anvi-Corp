'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Upload, X } from 'lucide-react';
import api from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import type { JobPostingResponse, ResumeResponse } from '@/types';

interface Props {
  posting: JobPostingResponse;
  onClose: () => void;
  onApplied: () => void;
}

const MAX_SOI_CHARS = 500;

export default function ApplyModal({ posting, onClose, onApplied }: Props) {
  const { user } = useAuth();
  const [resumes, setResumes] = useState<ResumeResponse[]>([]);
  const [resumeId, setResumeId] = useState<string>('');
  const [statement, setStatement] = useState('');
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement | null>(null);

  const loadResumes = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<ResumeResponse[]>('/api/v1/resumes/me');
      setResumes(res.data ?? []);
      const def = (res.data ?? []).find((r) => r.isDefault);
      setResumeId(def?.id ?? res.data?.[0]?.id ?? '');
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Could not load your resumes');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadResumes();
  }, [loadResumes]);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  async function handleUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    const form = new FormData();
    form.append('file', file);
    try {
      const res = await api.post<ResumeResponse>('/api/v1/resumes', form);
      setResumes((prev) => [...prev, res.data]);
      setResumeId(res.data.id);
    } catch (ex) {
      setErr(ex instanceof Error ? ex.message : 'Resume upload failed');
    } finally {
      if (fileRef.current) fileRef.current.value = '';
    }
  }

  async function submit() {
    if (!resumeId) {
      setErr('Please upload or select a resume');
      return;
    }
    setSubmitting(true);
    setErr(null);
    try {
      await api.post('/api/v1/applications', {
        jobPostingId: posting.id,
        resumeId,
        statementOfInterest: statement.trim() || null,
      });
      onApplied();
    } catch (e) {
      const ax = e as { response?: { status?: number; data?: { error?: string; code?: string } } };
      const status = ax.response?.status;
      if (status === 409) {
        setErr('You have already applied to this job.');
      } else if (status === 403) {
        setErr('Verify your email before applying.');
      } else {
        setErr(ax.response?.data?.error ?? 'Submission failed. Please retry.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="apply-modal-title"
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="w-full max-w-2xl overflow-hidden rounded-lg bg-white shadow-xl">
        <header className="flex items-start justify-between gap-4 border-b border-slate-200 px-6 py-4">
          <div>
            <h2 id="apply-modal-title" className="text-lg font-semibold text-slate-900">
              {posting.title}
            </h2>
            <p className="mt-0.5 text-xs uppercase tracking-wide text-slate-500">
              {posting.employmentType === 'INTERNSHIP' ? 'Internship' : 'Full-time'}
              {posting.location ? ` · ${posting.location}` : ''}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="rounded-md p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
          >
            <X className="h-5 w-5" strokeWidth={2} />
          </button>
        </header>

        <div className="space-y-5 px-6 py-5">
          <section>
            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">
              Profile snapshot
            </h3>
            <div className="rounded-md border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-700">
              <div>{user?.fullName ?? user?.email}</div>
              <div className="text-xs text-slate-500">{user?.email}</div>
            </div>
          </section>

          <section>
            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">
              Resume <span className="text-red-600">*</span>
            </h3>
            {loading ? (
              <div className="h-9 animate-pulse rounded-md bg-slate-100" aria-hidden />
            ) : resumes.length > 0 ? (
              <select
                value={resumeId}
                onChange={(e) => setResumeId(e.target.value)}
                className="w-full rounded-md border border-slate-200 bg-white px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
              >
                {resumes.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.fileName} {r.isDefault ? '(default)' : ''}
                  </option>
                ))}
              </select>
            ) : (
              <p className="text-sm text-slate-500">No resumes uploaded yet.</p>
            )}
            <div className="mt-2">
              <label className="inline-flex cursor-pointer items-center gap-2 text-sm font-medium text-brand-700 hover:text-brand-800">
                <Upload className="h-4 w-4" strokeWidth={2} />
                Upload new
                <input
                  ref={fileRef}
                  type="file"
                  accept=".pdf,.doc,.docx"
                  className="hidden"
                  onChange={handleUpload}
                />
              </label>
            </div>
          </section>

          <section>
            <div className="mb-2 flex items-baseline justify-between">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                Statement of Interest <span className="font-normal normal-case text-slate-400">(optional)</span>
              </h3>
              <span className={
                'text-xs ' + (statement.length > MAX_SOI_CHARS ? 'text-red-600' : 'text-slate-400')
              }>
                {statement.length}/{MAX_SOI_CHARS}
              </span>
            </div>
            <textarea
              value={statement}
              onChange={(e) => setStatement(e.target.value)}
              maxLength={MAX_SOI_CHARS}
              rows={4}
              placeholder="Tell us why you're a good fit for this role…"
              className="w-full resize-y rounded-md border border-slate-200 bg-white px-3 py-2 text-sm placeholder:text-slate-400 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
            />
          </section>

          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800">
              {err}
            </p>
          )}
        </div>

        <footer className="flex justify-end gap-2 border-t border-slate-200 bg-slate-50 px-6 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-100"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={submitting || !resumeId}
            className="rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-brand-800 disabled:opacity-60"
          >
            {submitting ? 'Submitting…' : 'Submit application'}
          </button>
        </footer>
      </div>
    </div>
  );
}
