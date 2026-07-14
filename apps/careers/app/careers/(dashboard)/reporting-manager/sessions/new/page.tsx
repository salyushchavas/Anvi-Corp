'use client';

import { FormEvent, Suspense, useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { CalendarClock } from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/api';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import ProtectedRoute from '@/components/ProtectedRoute';
import type {
  ProjectAwaitingQa,
  QaSession,
  ReportingManagerDashboard,
  Uuid,
} from '@/types';

export default function NewQaSessionPage() {
  return (
    <ProtectedRoute requiredRoles={['REPORTING_MANAGER', 'SUPER_ADMIN']}>
      <DashboardLayout title="Schedule Q&A">
        <Suspense fallback={<div className="text-sm text-gray-500">Loading…</div>}>
          <Body />
        </Suspense>
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Body() {
  const router = useRouter();
  const params = useSearchParams();
  const presetProjectId = params?.get('projectId') ?? '';

  const [projects, setProjects] = useState<ProjectAwaitingQa[]>([]);
  const [projectId, setProjectId] = useState<Uuid | ''>(presetProjectId);
  const [date, setDate] = useState('');
  const [time, setTime] = useState('');
  const [meetingLink, setMeetingLink] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const res = await api.get<ReportingManagerDashboard>(
          '/api/v1/reporting-manager/dashboard',
        );
        setProjects(res.data.projectsAwaitingQa ?? []);
        if (!presetProjectId && res.data.projectsAwaitingQa.length > 0) {
          setProjectId(res.data.projectsAwaitingQa[0].projectId);
        }
      } catch (err: any) {
        setError(err?.response?.data?.error ?? "Couldn't load eligible projects.");
      }
    })();
  }, [presetProjectId]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!projectId || !date || !time) {
      setError('Project, date and time are required.');
      return;
    }
    const scheduledAt = new Date(`${date}T${time}`).toISOString();
    setSubmitting(true);
    try {
      const res = await api.post<QaSession>('/api/v1/qa-sessions', {
        projectId,
        scheduledAt,
        meetingLink: meetingLink.trim() || undefined,
      });
      toast.success('Q&A scheduled.');
      router.push(`/careers/reporting-manager/sessions/${res.data.id}`);
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't schedule the session.");
      setSubmitting(false);
    }
  }

  return (
    <section className="mx-auto max-w-xl space-y-4">
      <header>
        <button
          type="button"
          onClick={() => router.back()}
          className="mb-1 text-xs text-gray-500 hover:text-gray-700"
        >
          ← Back
        </button>
        <h1 className="text-xl font-semibold text-gray-900">
          Schedule a Q&amp;A session
        </h1>
        <p className="mt-1 text-sm text-gray-600">
          Pick a tech-approved project, set a date / time, and (optionally) drop
          in a meeting link. The intern is notified.
        </p>
      </header>

      <form
        onSubmit={submit}
        className="space-y-3 rounded-lg border border-gray-200 bg-white p-5"
      >
        <Field label="Project" required>
          <select
            value={projectId}
            onChange={(e) => setProjectId(e.target.value as Uuid)}
            required
            className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          >
            {projects.length === 0 ? (
              <option value="">No tech-approved projects available</option>
            ) : (
              projects.map((p) => (
                <option key={p.projectId} value={p.projectId}>
                  {p.projectTitle}
                  {p.internName ? ` — ${p.internName}` : ''}
                </option>
              ))
            )}
          </select>
        </Field>

        <div className="grid grid-cols-2 gap-3">
          <Field label="Date" required>
            <input
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              required
              className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </Field>
          <Field label="Time" required>
            <input
              type="time"
              value={time}
              onChange={(e) => setTime(e.target.value)}
              required
              className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </Field>
        </div>

        <Field label="Meeting link (optional)">
          <input
            type="url"
            value={meetingLink}
            onChange={(e) => setMeetingLink(e.target.value)}
            placeholder="https://meet.google.com/…"
            className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          />
        </Field>

        {error && <p className="text-sm text-red-700">{error}</p>}

        <div className="flex justify-end gap-2 pt-2">
          <button
            type="button"
            onClick={() => router.back()}
            disabled={submitting}
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-60"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={submitting || projects.length === 0}
            className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-3 py-1.5 text-sm font-medium text-white hover:bg-brand-800 disabled:opacity-60"
          >
            <CalendarClock className="h-3.5 w-3.5" strokeWidth={2} />
            {submitting ? 'Scheduling…' : 'Schedule session'}
          </button>
        </div>
      </form>
    </section>
  );
}

function Field({
  label,
  required,
  children,
}: {
  label: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-gray-700">
        {label}
        {required && <span className="text-red-500"> *</span>}
      </label>
      {children}
    </div>
  );
}
