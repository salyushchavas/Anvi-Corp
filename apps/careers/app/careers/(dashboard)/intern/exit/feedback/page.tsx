'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { ChevronLeft } from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';
import ExitFeedbackForm from '@/components/exit/ExitFeedbackForm';

interface FeedbackResponse {
  id: string;
  overallRating: number;
  learningRating: number;
  mentorshipRating: number;
  workEnvironmentRating: number;
  whatWentWell: string;
  whatCouldImprove: string;
  wouldRecommend: boolean;
  additionalComments: string | null;
  submittedAt: string;
}

export default function ExitFeedbackPage() {
  const [existing, setExisting] = useState<FeedbackResponse | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<FeedbackResponse>('/api/v1/exit/feedback/mine');
      setExisting(res.data);
    } catch {
      // 404 expected when feedback not yet submitted
      setExisting(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  return (
    <InternPageShell
      title="Exit Feedback"
      subtitle="Your thoughts help us improve future internships."
    >
      <Link
        href="/careers/intern/exit/summary"
        className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
      >
        <ChevronLeft className="h-4 w-4" /> Back to summary
      </Link>

      {loading && (
        <div className="h-32 animate-pulse rounded-lg bg-slate-50" aria-hidden />
      )}

      {!loading && existing && (
        <div className="rounded-lg border border-green-200 bg-green-50 p-5 text-sm text-green-900">
          <p className="font-semibold">
            Feedback submitted on {new Date(existing.submittedAt).toLocaleDateString()}
          </p>
          <p className="mt-2 text-green-800">
            Thank you. Feedback is one-time; contact Help if you need to amend.
          </p>
          <dl className="mt-4 grid grid-cols-2 gap-3">
            {[
              ['Overall', existing.overallRating],
              ['Learning', existing.learningRating],
              ['Mentorship', existing.mentorshipRating],
              ['Work environment', existing.workEnvironmentRating],
            ].map(([label, value]) => (
              <div key={String(label)} className="rounded-md bg-white p-3">
                <dt className="text-[11px] uppercase text-slate-500">{label}</dt>
                <dd className="text-sm font-semibold text-slate-900">{value} / 5</dd>
              </div>
            ))}
          </dl>
          <div className="mt-4 space-y-3">
            <div>
              <p className="text-[11px] uppercase text-slate-500">What went well</p>
              <p className="mt-1 whitespace-pre-wrap text-sm text-slate-800">
                {existing.whatWentWell}
              </p>
            </div>
            <div>
              <p className="text-[11px] uppercase text-slate-500">What could improve</p>
              <p className="mt-1 whitespace-pre-wrap text-sm text-slate-800">
                {existing.whatCouldImprove}
              </p>
            </div>
            {existing.additionalComments && (
              <div>
                <p className="text-[11px] uppercase text-slate-500">Additional comments</p>
                <p className="mt-1 whitespace-pre-wrap text-sm text-slate-800">
                  {existing.additionalComments}
                </p>
              </div>
            )}
          </div>
        </div>
      )}

      {!loading && !existing && <ExitFeedbackForm onSubmitted={() => void load()} />}
    </InternPageShell>
  );
}
