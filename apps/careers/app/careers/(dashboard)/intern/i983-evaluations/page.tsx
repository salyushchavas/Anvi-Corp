'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { AlertCircle, CheckCircle2, FileText } from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';
import type { InternI983Row } from '@/components/evaluator/types';

export default function InternI983ListPage() {
  const [rows, setRows] = useState<InternI983Row[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<InternI983Row[]>('/api/v1/intern/i983-evaluations');
      setRows(res.data ?? []);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  return (
    <InternPageShell
      title="Your I-983 evaluations"
      subtitle="Federal STEM OPT compliance — your evaluator's milestone reviews."
    >
      {err && (
        <p className="mb-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}
      {loading && rows.length === 0 ? (
        <div className="h-32 animate-pulse rounded-lg bg-slate-100" />
      ) : rows.length === 0 ? (
        <p className="rounded-md border border-dashed border-slate-300 bg-white p-10 text-center text-sm text-slate-500">
          No I-983 evaluations yet. Your Evaluator will schedule one as you
          approach the 12-month or final milestone.
        </p>
      ) : (
        <ul className="space-y-2">
          {rows.map((r) => <Row key={r.evaluationId} row={r} />)}
        </ul>
      )}
    </InternPageShell>
  );
}

function Row({ row }: { row: InternI983Row }) {
  const needsSign = row.acknowledgedAt == null;
  const isAmended = row.status === 'AMENDED';
  return (
    <li>
      <Link
        href={`/careers/intern/i983-evaluations/${row.evaluationId}`}
        className="block rounded-lg border border-amber-200 bg-amber-50/20 p-4 hover:border-amber-400 hover:shadow-sm"
      >
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <p className="inline-flex items-center gap-1.5 text-sm font-semibold text-slate-900">
              <FileText className="h-3.5 w-3.5 text-amber-800" />
              I-983 {row.evaluationType.replaceAll('_', ' ')}
              {row.version > 1 && (
                <span className="ml-1 rounded-full bg-amber-100 px-1.5 py-0.5 text-[10px] font-semibold text-amber-800">
                  v{row.version}
                </span>
              )}
            </p>
            <p className="text-[11px] text-slate-500">
              {row.evaluatorName ?? 'Unknown evaluator'}
              {row.publishedAt && ` · published ${new Date(row.publishedAt).toLocaleDateString()}`}
            </p>
          </div>
          {needsSign ? (
            <span className="inline-flex items-center gap-0.5 rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-semibold text-amber-800">
              <AlertCircle className="h-3 w-3" />
              {isAmended ? 'Re-sign required' : 'Signature required'}
            </span>
          ) : (
            <span className="inline-flex items-center gap-0.5 rounded-full bg-green-100 px-2 py-0.5 text-[11px] font-semibold text-green-800">
              <CheckCircle2 className="h-3 w-3" />
              Signed
            </span>
          )}
        </div>
      </Link>
    </li>
  );
}
