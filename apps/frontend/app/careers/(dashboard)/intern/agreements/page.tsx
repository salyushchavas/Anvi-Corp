'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { AlertCircle, ChevronRight, FileText } from 'lucide-react';
import api from '@/lib/careers/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import { humanDate, stageToneClass } from '@/lib/careers/idms';

/**
 * Intern-side Agreements list. Shows every document the intern has been
 * sent, ever — active ones surface up top with an amber "your action
 * needed" chip; executed and revoked appear below in history.
 */
interface Row {
  id: string;
  title: string;
  status: string;
  stage: string;
  sentAt: string | null;
  returnedAt: string | null;
  finalizedAt: string | null;
  revokedAt: string | null;
  updatedAt: string | null;
  hasReturnComments: boolean;
}

export default function InternAgreementsPage() {
  return (
    <ProtectedRoute requiredRoles={['INTERN']}>
      <DashboardLayout title="Agreements">
        <PageContent />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function PageContent() {
  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<{ items: Row[] }>('/api/v1/intern/agreements');
      setRows(res.data.items ?? []);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  // Refetch on tab-visibility so a doc the ERM sends while the intern's
  // Agreements tab is open shows up without a manual refresh.
  useEffect(() => {
    function onVisibility() {
      if (document.visibilityState === 'visible') void load();
    }
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, [load]);

  const actionRows = rows.filter((r) => r.status === 'SENT_TO_INTERN' || r.status === 'RETURNED');
  const otherRows  = rows.filter((r) => !(r.status === 'SENT_TO_INTERN' || r.status === 'RETURNED'));

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Agreements</h1>
        <p className="mt-1 text-sm text-slate-600">
          Every document your ERM has shared with you — for review, signature,
          or your records.
        </p>
      </header>

      {err && (
        <div className="flex items-start gap-2 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <p>{err}</p>
        </div>
      )}

      {loading ? (
        <div className="h-40 animate-pulse rounded-lg bg-slate-100" />
      ) : rows.length === 0 ? (
        <div className="rounded-lg border border-dashed border-slate-300 bg-slate-50 p-10 text-center">
          <FileText className="mx-auto h-8 w-8 text-slate-400" />
          <h2 className="mt-3 text-base font-semibold text-slate-800">Nothing yet</h2>
          <p className="mt-1 text-sm text-slate-500">
            Documents your ERM sends will show up here.
          </p>
        </div>
      ) : (
        <>
          {actionRows.length > 0 && (
            <section>
              <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">
                Waiting on you
              </h2>
              <ul className="space-y-2">
                {actionRows.map((r) => <AgreementRow key={r.id} row={r} highlight />)}
              </ul>
            </section>
          )}
          {otherRows.length > 0 && (
            <section>
              <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">
                History
              </h2>
              <ul className="space-y-2">
                {otherRows.map((r) => <AgreementRow key={r.id} row={r} highlight={false} />)}
              </ul>
            </section>
          )}
        </>
      )}
    </div>
  );
}

function AgreementRow({ row, highlight }: { row: Row; highlight: boolean }) {
  return (
    <li>
      <Link
        href={`/careers/intern/agreements/${row.id}`}
        className={`flex items-center gap-3 rounded-lg border p-4 shadow-sm hover:shadow ${
          highlight
            ? 'border-amber-200 bg-amber-50/70'
            : 'border-slate-200 bg-white'
        }`}
      >
        <div className="flex-1">
          <p className="font-medium text-slate-900">{row.title}</p>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-slate-500">
            <span className={`rounded-full px-2 py-0.5 font-medium ${stageToneClass(externalStage(row.status))}`}>
              {externalStage(row.status)}
            </span>
            <span>·</span>
            <span>Updated {humanDate(row.updatedAt)}</span>
          </div>
        </div>
        <ChevronRight className="h-4 w-4 text-slate-400" />
      </Link>
    </li>
  );
}

/** Same status enum, but the intern's copy talks in outcomes. */
function externalStage(status: string): string {
  switch (status) {
    case 'DRAFT':            return 'Being prepared';
    case 'SENT_TO_INTERN':   return 'Awaiting your signature';
    case 'INTERN_SUBMITTED': return 'Signed — verifying';
    case 'RETURNED':         return 'Returned';
    case 'VERIFIED':         return 'Signed — verifying';
    case 'FINALIZED':        return 'Executed';
    case 'REVOKED':          return 'Revoked';
    case 'SUPERSEDED':       return 'Replaced';
    default: return status;
  }
}
