'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import { ChevronLeft } from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';
import type {
  CandidateInterviewResponse,
  InterviewDecision,
} from '@/types';

const BANNER_BY_DECISION: Record<
  InterviewDecision,
  { tone: string; title: string; description: string }
> = {
  SELECTED: {
    tone: 'border-green-200 bg-green-50 text-green-900',
    title: "You've been selected",
    description: 'Watch your inbox for the offer letter.',
  },
  HOLD: {
    tone: 'border-amber-200 bg-amber-50 text-amber-900',
    title: 'Your application is being held for review',
    description: 'The team is comparing candidates and will reach back out.',
  },
  REJECTED: {
    tone: 'border-slate-200 bg-slate-50 text-slate-800',
    title: "We've decided not to proceed at this time",
    description: 'Thank you for the time you put into the process.',
  },
};

export default function InternInterviewDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const [interview, setInterview] = useState<CandidateInterviewResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<CandidateInterviewResponse>(`/api/v1/interviews/${id}`);
      setInterview(res.data);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Could not load this interview');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  if (loading) {
    return (
      <InternPageShell title="Interview">
        <div className="h-48 animate-pulse rounded-lg bg-slate-50" aria-hidden />
      </InternPageShell>
    );
  }
  if (err || !interview) {
    return (
      <InternPageShell title="Interview">
        <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          {err ?? 'Interview not found'}
        </p>
      </InternPageShell>
    );
  }

  const banner = interview.decision ? BANNER_BY_DECISION[interview.decision] : null;
  const completed = interview.status === 'COMPLETED';

  return (
    <InternPageShell
      title={completed
        ? `Interview completed on ${new Date(interview.scheduledAt).toLocaleDateString()}`
        : interview.jobPostingTitle ?? 'Interview'}
    >
      <Link
        href="/careers/intern/interviews"
        className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
      >
        <ChevronLeft className="h-4 w-4" strokeWidth={2} /> Interview Center
      </Link>

      {banner && (
        <section className={'mb-6 rounded-lg border p-5 ' + banner.tone}>
          <h2 className="text-base font-semibold">{banner.title}</h2>
          <p className="mt-1 text-sm">{banner.description}</p>
        </section>
      )}

      {interview.applicantVisibleNotes && (
        <section className="mb-6 rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
          <h3 className="text-sm font-semibold text-slate-900">Feedback from the team</h3>
          <p className="mt-2 whitespace-pre-wrap text-sm text-slate-700">
            {interview.applicantVisibleNotes}
          </p>
        </section>
      )}

      <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <h3 className="text-sm font-semibold text-slate-900">What happens next</h3>
        <p className="mt-2 text-sm text-slate-600">
          {completed && interview.decision === 'SELECTED' && (
            <>Your offer letter is being prepared. You'll be notified when it's ready to sign.</>
          )}
          {completed && interview.decision === 'HOLD' && (
            <>You'll hear back from the team within a few business days as they finalize decisions.</>
          )}
          {completed && interview.decision === 'REJECTED' && (
            <>Other openings are posted regularly — keep an eye on Job Postings if you'd like to apply again.</>
          )}
          {!completed && (
            <>This interview is {interview.status.toLowerCase()}. Check Interview Center for the latest details.</>
          )}
        </p>
      </section>
    </InternPageShell>
  );
}
