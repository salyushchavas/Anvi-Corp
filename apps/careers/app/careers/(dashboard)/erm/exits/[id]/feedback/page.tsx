'use client';

import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import { BRAND } from '@/lib/brand';

interface FeedbackView {
  id: string;
  exitRecordId: string;
  internUserId: string;
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
  // useParams (next/navigation) — Next 14.2 delivers params as a plain
  // object, not a Promise; React's use() rejects non-thenables with #438.
  const params = useParams<{ id: string }>();
  const id = params?.id ?? '';
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN', 'MANAGER']}>
      <DashboardLayout>
        <Body id={id} />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Body({ id }: { id: string }) {
  const [fb, setFb] = useState<FeedbackView | null>(null);
  const [submitted, setSubmitted] = useState<boolean | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    api
      .get<FeedbackView | ''>(`/api/v1/erm/exits/${id}/feedback`)
      .then((res) => {
        if (!res.data || typeof res.data === 'string') {
          setSubmitted(false);
        } else {
          setFb(res.data);
          setSubmitted(true);
        }
      })
      .catch((e: any) =>
        setErr(e?.response?.data?.error ?? 'Failed to load feedback'),
      );
  }, [id]);

  if (err) {
    return (
      <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
        {err}
      </p>
    );
  }
  if (submitted === null) {
    return <div className="h-40 animate-pulse rounded-lg bg-slate-100" />;
  }

  return (
    <>
      <PageHeader
        title="Exit feedback"
        subtitle={
          submitted
            ? 'Intern submitted feedback (read-only).'
            : 'No feedback submitted yet.'
        }
      />
      <div className="mb-4">
        <Link
          href={`/careers/erm/exits/${id}`}
          className="text-xs text-slate-500 hover:text-slate-700"
        >
          ← Exit detail
        </Link>
      </div>

      {!submitted || !fb ? (
        <div className="rounded-lg border border-amber-200 bg-amber-50 p-6 text-sm text-amber-800">
          The intern has not submitted exit feedback. The
          {' '}<code>EXIT_FEEDBACK_SUBMITTED</code> checklist item stays
          PENDING until they do.
        </div>
      ) : (
        <div className="space-y-4">
          <section className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Stat label="Overall" value={fb.overallRating} />
            <Stat label="Learning" value={fb.learningRating} />
            <Stat label="Mentorship" value={fb.mentorshipRating} />
            <Stat label="Environment" value={fb.workEnvironmentRating} />
          </section>

          <Card label="What went well">
            <p className="whitespace-pre-wrap text-sm text-slate-800">
              {fb.whatWentWell}
            </p>
          </Card>
          <Card label="What could improve">
            <p className="whitespace-pre-wrap text-sm text-slate-800">
              {fb.whatCouldImprove}
            </p>
          </Card>
          {fb.additionalComments && (
            <Card label="Additional comments">
              <p className="whitespace-pre-wrap text-sm text-slate-800">
                {fb.additionalComments}
              </p>
            </Card>
          )}

          <section className="rounded-lg border border-slate-200 bg-white p-4 text-xs text-slate-600">
            Would recommend {BRAND.name}:{' '}
            <strong
              className={
                fb.wouldRecommend ? 'text-green-700' : 'text-red-700'
              }
            >
              {fb.wouldRecommend ? 'Yes' : 'No'}
            </strong>
            <span className="ml-3">
              Submitted {new Date(fb.submittedAt).toLocaleString()}
            </span>
          </section>
        </div>
      )}
    </>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3 text-center">
      <div className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
        {label}
      </div>
      <div className="mt-1 text-2xl font-semibold text-slate-900">
        {value}
        <span className="text-sm text-slate-400">/5</span>
      </div>
    </div>
  );
}

function Card({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4">
      <h3 className="mb-2 text-sm font-semibold text-slate-900">{label}</h3>
      {children}
    </section>
  );
}
