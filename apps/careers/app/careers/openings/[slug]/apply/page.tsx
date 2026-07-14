'use client';

import { FormEvent, useEffect, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import FileUpload from '@/components/FileUpload';
import AdaptiveCareersLayout from '@/components/careers/AdaptiveCareersLayout';
import { useAuth } from '@/lib/auth-context';
import type { JobPostingResponse, ResumeResponse } from '@/types';

export default function ApplyPage() {
  return (
    <ProtectedRoute requiredRoles={['INTERN']}>
      <AdaptiveCareersLayout title="Apply">
        <ApplyFlow />
      </AdaptiveCareersLayout>
    </ProtectedRoute>
  );
}

function ApplyFlow() {
  const router = useRouter();
  const params = useParams<{ slug: string }>();
  const slug = params?.slug;
  const { user } = useAuth();

  const [posting, setPosting] = useState<JobPostingResponse | null>(null);
  const [resumes, setResumes] = useState<ResumeResponse[]>([]);
  const [selectedResumeId, setSelectedResumeId] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [showUploader, setShowUploader] = useState(false);
  // Phase 1.3 / GAP A4: when the apply endpoint returns 403 + EMAIL_UNVERIFIED,
  // OR when we already know up-front (user.emailVerified === false), swap the
  // whole form for the verify-prompt rather than show a raw error toast.
  const [emailUnverified, setEmailUnverified] = useState(false);

  // GAP A4 — short-circuit pre-submit when we already know the user is
  // unverified. ProtectedRoute already gated unauthenticated visitors to the
  // login page, so user is non-null by the time this runs.
  const preflightEmailUnverified = user?.emailVerified === false;

  useEffect(() => {
    if (!slug) return;
    let cancelled = false;

    async function load() {
      setLoading(true);
      setLoadError(null);
      try {
        const [postingRes, resumesRes] = await Promise.all([
          api.get<JobPostingResponse>(`/api/v1/job-postings/${encodeURIComponent(slug)}`),
          api.get<ResumeResponse[]>('/api/v1/resumes/me'),
        ]);
        if (cancelled) return;
        setPosting(postingRes.data);
        setResumes(resumesRes.data ?? []);
        const defaultResume = (resumesRes.data ?? []).find((r) => r.isDefault);
        setSelectedResumeId(defaultResume?.id ?? resumesRes.data?.[0]?.id ?? '');
      } catch (err: any) {
        if (cancelled) return;
        const msg = err?.response?.data?.error ?? 'Failed to load application form';
        setLoadError(msg);
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    void load();
    return () => {
      cancelled = true;
    };
  }, [slug]);

  async function handleResumeUpload(file: File) {
    const form = new FormData();
    form.append('file', file);
    const res = await api.post<ResumeResponse>('/api/v1/resumes', form);
    const created = res.data;
    setResumes((prev) => {
      const next = [...prev, created];
      return next;
    });
    setSelectedResumeId(created.id);
    setShowUploader(false);
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!posting || !selectedResumeId) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      await api.post('/api/v1/applications', {
        jobPostingId: posting.id,
        resumeId: selectedResumeId,
      });
      router.replace('/careers/intern/applications?just_applied=1');
    } catch (err: any) {
      const status = err?.response?.status;
      const code = err?.response?.data?.code;
      if (status === 403 && code === 'EMAIL_UNVERIFIED') {
        setEmailUnverified(true);
      } else if (status === 409) {
        setSubmitError(
          "You've already applied to this position. View it on your applications page."
        );
      } else {
        const msg = err?.response?.data?.error ?? 'Submission failed. Please try again.';
        setSubmitError(msg);
      }
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) {
    return (
      <div className="flex min-h-[40vh] items-center justify-center">
        <div className="h-10 w-10 animate-spin rounded-full border-4 border-accent border-t-transparent" />
      </div>
    );
  }

  if (loadError) {
    return (
      <div className="mx-auto max-w-2xl rounded-lg border border-red-200 bg-red-50 p-6 text-sm text-red-700">
        <p className="mb-2 font-medium">Couldn&apos;t load the application form.</p>
        <p className="mb-4">{loadError}</p>
        <button
          type="button"
          onClick={() => router.refresh()}
          className="rounded border border-red-300 px-3 py-1.5 font-medium hover:bg-red-100"
        >
          Retry
        </button>
      </div>
    );
  }

  if (!posting) {
    return (
      <div className="mx-auto max-w-2xl rounded-lg border border-slate-200 bg-white p-6">
        <p className="mb-4 text-sm text-slate-700">This position is no longer open.</p>
        <Link
          href="/careers/openings"
          className="rounded bg-accent px-3 py-1.5 text-sm font-medium text-white hover:bg-accent-dark"
        >
          Back to all openings
        </Link>
      </div>
    );
  }

  if (emailUnverified || preflightEmailUnverified) {
    const returnTo = `/careers/openings/${posting.slug}/apply`;
    return (
      <div className="mx-auto max-w-2xl rounded-lg border border-amber-200 bg-amber-50 p-8 text-center">
        <h2 className="mb-2 text-lg font-semibold text-amber-900">
          Verify your email to apply
        </h2>
        <p className="mb-5 text-sm text-amber-800">
          We need to confirm your email before you can submit an application
          and receive your Skyzen Applicant ID.
        </p>
        <Link
          href={`/careers/verify-email?returnTo=${encodeURIComponent(returnTo)}`}
          className="inline-flex items-center justify-center rounded-full bg-gradient-to-r from-accent to-accent-dark px-5 py-2 font-semibold text-white shadow-glow-accent hover:shadow-glow-accent-lg"
        >
          Verify email
        </Link>
      </div>
    );
  }

  const hasResumes = resumes.length > 0;
  const canSubmit = Boolean(selectedResumeId) && !submitting;

  return (
    <div className="mx-auto max-w-2xl">
      <div className="mb-3">
        <Link
          href={`/careers/openings/${posting.slug}`}
          className="text-sm font-medium text-primary-700 hover:text-primary-800 hover:underline"
        >
          &larr; Back to position
        </Link>
      </div>

      <header className="mb-6">
        <p className="text-sm text-slate-500">Apply for</p>
        <h1 className="text-2xl font-semibold text-slate-900">{posting.title}</h1>
        <p className="mt-1 text-sm text-slate-500">
          {posting.entityName ? `${posting.entityName} · ` : ''}
          {posting.location}
        </p>
      </header>

      {submitError && (
        <div className="mb-6 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {submitError}
          {submitError.includes('already applied') && (
            <>
              {' '}
              <Link href="/careers/intern/applications" className="font-medium underline">
                View applications
              </Link>
            </>
          )}
        </div>
      )}

      <form onSubmit={onSubmit} className="space-y-6">
        {!hasResumes ? (
          <section className="rounded-lg border border-slate-200 bg-white p-6">
            <h2 className="mb-1 text-base font-semibold text-slate-900">
              Upload your resume to apply
            </h2>
            <p className="mb-4 text-sm text-slate-600">
              PDF or Word document, max 10 MB.
            </p>
            <FileUpload onFileSelected={handleResumeUpload} />
          </section>
        ) : (
          <section className="rounded-lg border border-slate-200 bg-white p-6">
            <label
              htmlFor="resumeSelect"
              className="mb-2 block text-sm font-medium text-slate-700"
            >
              Choose a resume
            </label>
            <select
              id="resumeSelect"
              value={selectedResumeId}
              onChange={(e) => setSelectedResumeId(e.target.value)}
              className="mb-4 w-full rounded border border-slate-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            >
              {resumes.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.fileName}
                  {r.isDefault ? ' (default)' : ''}
                </option>
              ))}
            </select>

            <button
              type="button"
              onClick={() => setShowUploader((v) => !v)}
              className="text-sm font-medium text-primary-700 hover:text-primary-800 hover:underline"
            >
              {showUploader ? 'Hide upload' : 'Upload a different resume'}
            </button>
            {showUploader && (
              <div className="mt-3">
                <FileUpload onFileSelected={handleResumeUpload} label="Upload a new resume" />
              </div>
            )}
          </section>
        )}

        <button
          type="submit"
          disabled={!canSubmit}
          className="w-full rounded-full bg-gradient-to-r from-accent to-accent-dark px-4 py-3 text-sm font-semibold text-white shadow-glow-accent transition hover:shadow-glow-accent-lg disabled:opacity-50 disabled:shadow-none"
        >
          {submitting ? 'Submitting…' : 'Submit Application'}
        </button>
      </form>
    </div>
  );
}
