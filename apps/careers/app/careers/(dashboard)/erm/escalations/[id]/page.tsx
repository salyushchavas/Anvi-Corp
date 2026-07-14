'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import SeverityBadge from '@/components/erm/escalations/SeverityBadge';
import StatusPill from '@/components/erm/escalations/StatusPill';
import ResolveExceptionModal from '@/components/erm/escalations/ResolveExceptionModal';
import AssignExceptionModal from '@/components/erm/escalations/AssignExceptionModal';
import {
  EXCEPTION_TYPE_LABEL,
  type ExceptionDetail,
} from '@/components/erm/escalations/types';

export default function EscalationDetailPage() {
  // useParams (next/navigation) — Next 14.2 delivers params as a plain
  // object, not a Promise; React's use() rejects non-thenables with #438.
  const params = useParams<{ id: string }>();
  const id = params?.id ?? '';
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <Body id={id} />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Body({ id }: { id: string }) {
  const [d, setD] = useState<ExceptionDetail | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [noteDraft, setNoteDraft] = useState('');
  const [savingNote, setSavingNote] = useState(false);
  const [modal, setModal] = useState<
    'resolve' | 'dismiss' | 'assign' | null
  >(null);

  const load = useCallback(async () => {
    try {
      const res = await api.get<ExceptionDetail>(
        `/api/v1/erm/escalations/${id}`,
      );
      setD(res.data);
      setErr(null);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Failed to load exception');
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  async function inProgress() {
    try {
      const res = await api.post<ExceptionDetail>(
        `/api/v1/erm/escalations/${id}/in-progress`,
      );
      setD(res.data);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Action failed');
    }
  }

  async function reopen() {
    try {
      const res = await api.post<ExceptionDetail>(
        `/api/v1/erm/escalations/${id}/reopen`,
      );
      setD(res.data);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Reopen failed');
    }
  }

  async function addNote() {
    if (noteDraft.trim().length < 5) return;
    setSavingNote(true);
    try {
      const res = await api.post<ExceptionDetail>(
        `/api/v1/erm/escalations/${id}/note`,
        { note: noteDraft.trim() },
      );
      setD(res.data);
      setNoteDraft('');
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Note add failed');
    } finally {
      setSavingNote(false);
    }
  }

  if (err && !d) {
    return (
      <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
        {err}
      </p>
    );
  }
  if (!d) return <div className="h-64 animate-pulse rounded-lg bg-slate-100" />;

  const active = ['OPEN', 'ASSIGNED', 'IN_PROGRESS'].includes(d.status);
  const canReopen = ['RESOLVED', 'DISMISSED', 'AUTO_RESOLVED'].includes(d.status);

  return (
    <>
      <PageHeader
        title={EXCEPTION_TYPE_LABEL[d.exceptionType] ?? d.exceptionType}
        subtitle={`Opened ${d.openedAt ? new Date(d.openedAt).toLocaleString() : '—'} · ${d.ageDays}d old`}
      />

      <div className="mb-4 flex flex-wrap items-center gap-3 rounded-lg border border-slate-200 bg-white px-4 py-3">
        <SeverityBadge severity={d.severity} />
        <StatusPill status={d.status} />
        {d.assignedToName && (
          <span className="text-xs text-slate-600">
            Assigned to <strong>{d.assignedToName}</strong>
          </span>
        )}
        <div className="ml-auto flex gap-2">
          <Link
            href="/careers/erm/escalations"
            className="rounded-md border border-slate-200 px-3 py-1 text-xs text-slate-700 hover:bg-slate-50"
          >
            ← Queue
          </Link>
          {active && (
            <>
              <button
                type="button"
                onClick={() => setModal('assign')}
                className="rounded-md border border-slate-300 px-3 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50"
              >
                Assign
              </button>
              {d.status !== 'IN_PROGRESS' && (
                <button
                  type="button"
                  onClick={() => void inProgress()}
                  className="rounded-md border border-brand-300 bg-brand-50 px-3 py-1 text-xs font-medium text-brand-700 hover:bg-brand-100"
                >
                  In progress
                </button>
              )}
              <button
                type="button"
                onClick={() => setModal('resolve')}
                className="rounded-md bg-green-600 px-3 py-1 text-xs font-semibold text-white hover:bg-green-700"
              >
                Resolve
              </button>
              <button
                type="button"
                onClick={() => setModal('dismiss')}
                className="rounded-md border border-slate-300 px-3 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50"
              >
                Dismiss
              </button>
            </>
          )}
          {canReopen && (
            <button
              type="button"
              onClick={() => void reopen()}
              className="rounded-md border border-amber-300 bg-amber-50 px-3 py-1 text-xs font-medium text-amber-700 hover:bg-amber-100"
            >
              Reopen (≤7d)
            </button>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <section className="lg:col-span-2 space-y-4">
          <div className="rounded-lg border border-slate-200 bg-white p-4">
            <h3 className="mb-2 text-sm font-semibold text-slate-900">
              Subject intern
            </h3>
            <div className="text-sm text-slate-900">
              {d.internLifecycleId ? (
                <Link
                  href={`/careers/erm/active-interns/${d.internLifecycleId}`}
                  className="font-medium hover:underline"
                >
                  {d.subjectName ?? '(unknown)'}
                </Link>
              ) : (
                <span className="font-medium">
                  {d.subjectName ?? '(unknown)'}
                </span>
              )}
              <span className="ml-2 text-xs text-slate-500">
                {d.subjectEmployeeId} · {d.subjectEmail}
              </span>
            </div>
            {d.subjectResourceType && (
              <p className="mt-2 text-[11px] text-slate-500">
                Affected resource: <code>{d.subjectResourceType}</code>{' '}
                {d.subjectResourceId && (
                  <code>({d.subjectResourceId.slice(0, 8)})</code>
                )}
              </p>
            )}
            {d.payloadJson && (
              <pre className="mt-2 overflow-x-auto rounded bg-slate-50 p-2 text-[11px] text-slate-700">
                {d.payloadJson}
              </pre>
            )}
          </div>

          {d.resolutionNote && (
            <div className="rounded-lg border border-green-200 bg-green-50 p-4">
              <p className="mb-1 text-xs font-semibold text-green-800">
                Resolution
                {d.resolutionReasonCode && (
                  <span className="ml-2 rounded-full bg-green-100 px-2 py-0.5 text-[10px] font-semibold text-green-700">
                    {d.resolutionReasonCode}
                  </span>
                )}
              </p>
              <p className="whitespace-pre-wrap text-sm text-green-900">
                {d.resolutionNote}
              </p>
            </div>
          )}

          <div className="rounded-lg border border-slate-200 bg-white p-4">
            <h3 className="mb-2 text-sm font-semibold text-slate-900">
              Add note
              <span className="ml-2 text-[10px] font-normal text-red-600">
                ERM-only
              </span>
            </h3>
            <textarea
              rows={2}
              value={noteDraft}
              onChange={(e) => setNoteDraft(e.target.value)}
              placeholder="At least 5 characters"
              className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
            />
            <div className="mt-2 flex justify-end">
              <button
                type="button"
                disabled={savingNote || noteDraft.trim().length < 5}
                onClick={() => void addNote()}
                className="rounded-md border border-slate-300 bg-white px-3 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
              >
                {savingNote ? 'Saving…' : 'Add note'}
              </button>
            </div>
          </div>
        </section>

        <section className="rounded-lg border border-slate-200 bg-white p-4">
          <h3 className="mb-3 text-sm font-semibold text-slate-900">
            Event log
          </h3>
          {d.history.length === 0 ? (
            <p className="text-xs text-slate-500">No events yet.</p>
          ) : (
            <ul className="space-y-3">
              {d.history.map((h) => (
                <li
                  key={h.id}
                  className="border-l-2 border-slate-200 pl-3 text-xs text-slate-700"
                >
                  <div className="text-sm font-medium text-slate-900">
                    {h.eventType}
                    {h.reasonCode && (
                      <span className="ml-2 rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-600">
                        {h.reasonCode}
                      </span>
                    )}
                  </div>
                  <div className="text-[11px] text-slate-500">
                    {h.actorName ?? 'system'} ·{' '}
                    {new Date(h.createdAt).toLocaleString()}
                    {h.previousStatus && h.newStatus && (
                      <span>
                        {' '}
                        · {h.previousStatus} → {h.newStatus}
                      </span>
                    )}
                  </div>
                  {h.note && (
                    <p className="mt-1 whitespace-pre-wrap text-[12px] text-slate-700">
                      {h.note}
                    </p>
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      {modal === 'resolve' && (
        <ResolveExceptionModal
          exceptionId={id}
          mode="resolve"
          onClose={() => setModal(null)}
          onDone={(u) => {
            setD(u);
            setModal(null);
          }}
        />
      )}
      {modal === 'dismiss' && (
        <ResolveExceptionModal
          exceptionId={id}
          mode="dismiss"
          onClose={() => setModal(null)}
          onDone={(u) => {
            setD(u);
            setModal(null);
          }}
        />
      )}
      {modal === 'assign' && (
        <AssignExceptionModal
          exceptionId={id}
          defaultAssigneeUserId={d.assignedToId}
          onClose={() => setModal(null)}
          onDone={(u) => {
            setD(u);
            setModal(null);
          }}
        />
      )}
    </>
  );
}
