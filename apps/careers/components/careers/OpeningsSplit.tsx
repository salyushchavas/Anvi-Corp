'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import api from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import AppliedJobPostingCard from '@/components/careers/AppliedJobPostingCard';
import JobPostingsTable from '@/components/jobs/JobPostingsTable';
import type { JobPostingResponse, Page } from '@/types';

interface Props {
  /**
   * Server-rendered seed: openings fetched without auth at SSR time. We render
   * these straight away for the public/unauthenticated path; authenticated
   * candidates trigger a client refetch to layer in {@code applied} state.
   */
  initialPostings: JobPostingResponse[];
}

/**
 * Splits the openings list into "Your applications" + "Available internships"
 * for an authenticated candidate. For anyone else (anonymous viewers, staff,
 * other roles) we render the single combined list — preserving the public
 * SSR view exactly as it was.
 */
export default function OpeningsSplit({ initialPostings }: Props) {
  const { user, isLoading } = useAuth();
  const isCandidate = !!(user?.roles?.includes('INTERN') || user?.roles?.includes('INTERN'));

  const [postings, setPostings] = useState<JobPostingResponse[]>(initialPostings);
  const [refetching, setRefetching] = useState(false);
  // Phase 1.3: the authed re-fetch can 403 with code=EMAIL_UNVERIFIED. When it
  // does, we render the verify-prompt instead of the list rather than swallow
  // the error silently — the candidate needs to be told why the list is gated.
  const [emailUnverified, setEmailUnverified] = useState(false);

  // For candidates only: re-fetch with auth so the response includes the
  // applied/applicationStatus fields. The SSR call was unauthenticated so
  // those fields are guaranteed to be empty in initialPostings even if the
  // candidate has open applications.
  useEffect(() => {
    if (isLoading || !isCandidate) return;
    let cancelled = false;
    setRefetching(true);
    setEmailUnverified(false);
    (async () => {
      try {
        const res = await api.get<Page<JobPostingResponse>>(
          '/api/v1/job-postings?page=0&size=50',
        );
        if (!cancelled) setPostings(res.data?.content ?? initialPostings);
      } catch (err: any) {
        if (
          !cancelled
          && err?.response?.status === 403
          && err?.response?.data?.code === 'EMAIL_UNVERIFIED'
        ) {
          setEmailUnverified(true);
        }
        // For other failures keep the SSR seed — the user still sees a working
        // list, just without the applied/available split. Honest degradation.
      } finally {
        if (!cancelled) setRefetching(false);
      }
    })();
    return () => {
      cancelled = true;
    };
    // initialPostings is the SSR fallback only — fetching it once on mount is
    // sufficient; we don't want to re-trigger on parent re-renders.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isCandidate, isLoading]);

  if (isCandidate && emailUnverified) {
    return <VerifyEmailPrompt email={user?.email} />;
  }

  // Public / non-candidate path — single table view.
  if (!isCandidate) {
    if (postings.length === 0) {
      return <EmptyState />;
    }
    return (
      <JobPostingsTable
        postings={postings}
        onApplied={(jobPostingId, applicationId) => {
          setPostings((prev) =>
            prev.map((row) =>
              row.id === jobPostingId
                ? { ...row, applied: true, applicationId, applicationStatus: 'APPLIED' }
                : row,
            ),
          );
        }}
      />
    );
  }

  // Candidate path — split by applied flag.
  const applied = postings.filter((p) => p.applied);
  const available = postings.filter((p) => !p.applied);

  return (
    <div className="space-y-10">
      <section>
        <h2 className="mb-3 text-lg font-semibold text-slate-900">Your applications</h2>
        {applied.length === 0 ? (
          <p className="rounded-lg border border-dashed border-slate-300 bg-white p-6 text-sm text-slate-500">
            You haven&apos;t applied to anything yet — pick one from below to get started.
          </p>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {applied.map((p) => (
              <AppliedJobPostingCard key={p.id} posting={p} />
            ))}
          </div>
        )}
      </section>

      <section>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-slate-900">Available internships</h2>
          {refetching && (
            <span className="text-xs text-slate-500">Refreshing…</span>
          )}
        </div>
        {available.length === 0 ? (
          <div className="rounded-lg border border-dashed border-slate-300 bg-white p-6 text-sm text-slate-500">
            {applied.length > 0 ? (
              <>
                You&apos;ve applied to every open posting. Check back when new ones go live, or{' '}
                <Link
                  href="/careers/intern/applications"
                  className="font-medium text-primary-700 hover:underline"
                >
                  review your applications
                </Link>
                .
              </>
            ) : (
              'No open internships right now. Check back soon.'
            )}
          </div>
        ) : (
          <JobPostingsTable
            postings={available}
            onApplied={(jobPostingId, applicationId) => {
              setPostings((prev) =>
                prev.map((row) =>
                  row.id === jobPostingId
                    ? { ...row, applied: true, applicationId, applicationStatus: 'APPLIED' }
                    : row,
                ),
              );
            }}
          />
        )}
      </section>
    </div>
  );
}

function EmptyState() {
  return (
    <div className="rounded-lg border border-dashed border-slate-300 bg-white p-12 text-center">
      <p className="text-base font-medium text-slate-700">
        No open positions right now.
      </p>
      <p className="mt-1 text-sm text-slate-500">Check back soon.</p>
    </div>
  );
}

/**
 * Rendered when the openings endpoint returns 403 + code=EMAIL_UNVERIFIED.
 * Links straight to the verify-email page with the candidate's email
 * prefilled and a returnTo back to the openings list.
 */
function VerifyEmailPrompt({ email }: { email?: string }) {
  const params = new URLSearchParams();
  if (email) params.set('email', email);
  params.set('returnTo', '/careers/openings');
  return (
    <div className="rounded-lg border border-amber-200 bg-amber-50 p-8 text-center">
      <h2 className="mb-2 text-lg font-semibold text-amber-900">
        Verify your email to unlock internships
      </h2>
      <p className="mb-5 text-sm text-amber-800">
        We sent a 6-digit code to {email ?? 'your inbox'}. Enter it on the next
        screen to receive your Skyzen Applicant ID and start applying.
      </p>
      <Link
        href={`/careers/verify-email?${params.toString()}`}
        className="inline-flex items-center justify-center rounded-full bg-gradient-to-r from-accent to-accent-dark px-5 py-2 font-semibold text-white shadow-glow-accent hover:shadow-glow-accent-lg"
      >
        Verify email
      </Link>
    </div>
  );
}
