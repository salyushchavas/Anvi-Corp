'use client';

/**
 * IDMS Phase 2 — the "Offers / IDMS" cockpit.
 *
 * Transforms the legacy offer-list page into the living-document hub:
 * a single queue merging (a) interns awaiting a first document and
 * (b) every in-flight document instance with its lifecycle stage chip.
 * Row actions route into the fill / verify / history subpages.
 *
 * The legacy /careers/erm/offers/new + /offers/[id]/edit routes are no
 * longer linked from anywhere; historical offer data remains readable
 * via direct URL from other surfaces (Application detail, Intern
 * profile) so nothing already-rendered goes dark.
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  AlertCircle,
  ChevronRight,
  FilePlus,
  History as HistoryIcon,
  Loader2,
  RefreshCw,
  Search,
  Send,
  X,
} from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/careers/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import {
  humanDate,
  stageToneClass,
  type PickableTemplate,
  type QueueRow,
} from '@/lib/careers/idms';

interface QueueResponse { items: QueueRow[]; total: number }

const TABS: { key: string; label: string; match: (r: QueueRow) => boolean }[] = [
  { key: 'ALL',        label: 'All',                  match: () => true },
  { key: 'AWAITING',   label: 'Awaiting offer',       match: (r) => r.statusRaw === 'AWAITING_OFFER' },
  { key: 'SENT',       label: 'Sent',                 match: (r) => r.statusRaw === 'SENT_TO_INTERN' },
  { key: 'VERIFYING',  label: 'Signed — verifying',   match: (r) => r.statusRaw === 'INTERN_SUBMITTED' },
  { key: 'RETURNED',   label: 'Returned',             match: (r) => r.statusRaw === 'RETURNED' },
  { key: 'EXECUTED',   label: 'Executed',             match: (r) => r.statusRaw === 'FINALIZED' },
  { key: 'REVOKED',    label: 'Revoked',              match: (r) => r.statusRaw === 'REVOKED' },
];

export default function ErmIdmsCockpitPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout title="Offers / IDMS">
        <PageContent />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function PageContent() {
  const router = useRouter();
  const [rows, setRows] = useState<QueueRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [tab, setTab] = useState<string>(() => {
    if (typeof window === 'undefined') return 'ALL';
    const t = new URLSearchParams(window.location.search).get('tab');
    if (t === 'awaiting') return 'AWAITING';
    if (t === 'verifying') return 'VERIFYING';
    return 'ALL';
  });
  const [search, setSearch] = useState('');
  const [pickerOpen, setPickerOpen] = useState<QueueRow | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<QueueResponse>('/api/v1/erm/idms/queue');
      setRows(res.data.items ?? []);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load queue');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    const tabFilter = TABS.find((t) => t.key === tab)?.match ?? (() => true);
    return rows.filter((r) => {
      if (!tabFilter(r)) return false;
      if (!q) return true;
      const hay = [r.internName, r.internEmail, r.templateTitle]
        .filter(Boolean).join(' ').toLowerCase();
      return hay.includes(q);
    });
  }, [rows, tab, search]);

  const counts = useMemo(() => {
    const map: Record<string, number> = {};
    for (const r of rows) {
      for (const t of TABS) if (t.match(r)) map[t.key] = (map[t.key] ?? 0) + 1;
    }
    return map;
  }, [rows]);

  function openRow(row: QueueRow) {
    if (row.instanceId) {
      // DRAFT rows go back to the fill page (that's where their editing
      // state lives — routing to /detail here was the exact reason ERM
      // couldn't resume editing after leaving mid-fill). Everything past
      // DRAFT goes to the detail page (verify / revoke / history).
      const path = row.statusRaw === 'DRAFT'
        ? `/careers/erm/offers/idms/${row.instanceId}/fill`
        : `/careers/erm/offers/idms/${row.instanceId}`;
      router.push(path);
    } else {
      // Awaiting-offer row → template picker to start a new instance.
      setPickerOpen(row);
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Offers / IDMS"
        subtitle="Every intern's document status in one place — from awaiting first offer to executed and beyond."
      />

      <div className="flex flex-wrap items-center gap-3">
        <div className="relative flex-1 min-w-[240px]">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search name, email, document…"
            className="w-full rounded-md border border-slate-200 bg-white pl-9 pr-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
          />
        </div>
        <button
          type="button"
          onClick={() => void load()}
          className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 px-3 py-2 text-xs font-medium text-slate-600 hover:bg-slate-50"
        >
          <RefreshCw className="h-3.5 w-3.5" />
          Refresh
        </button>
      </div>

      <div className="flex flex-wrap gap-1">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            onClick={() => setTab(t.key)}
            className={`inline-flex items-center gap-2 rounded-full px-3 py-1.5 text-xs font-medium transition ${
              tab === t.key
                ? 'bg-brand-700 text-white'
                : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
            }`}
          >
            {t.label}
            {counts[t.key] > 0 && (
              <span className={`rounded-full px-1.5 text-[10px] ${
                tab === t.key ? 'bg-white/25' : 'bg-white/70 text-slate-800'
              }`}>{counts[t.key]}</span>
            )}
          </button>
        ))}
      </div>

      {err && (
        <div className="flex items-start gap-2 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <p>{err}</p>
        </div>
      )}

      {loading ? (
        <div className="h-64 animate-pulse rounded-lg bg-slate-100" />
      ) : filtered.length === 0 ? (
        <EmptyState hasAny={rows.length > 0} />
      ) : (
        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-4 py-3 text-left font-medium">Intern</th>
                <th className="px-4 py-3 text-left font-medium">Document</th>
                <th className="px-4 py-3 text-left font-medium">Stage</th>
                <th className="px-4 py-3 text-left font-medium">Last activity</th>
                <th className="px-4 py-3 text-right font-medium"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200">
              {filtered.map((r, i) => (
                <tr
                  key={r.instanceId ?? `awaiting-${r.applicationId}-${i}`}
                  onClick={() => openRow(r)}
                  className="cursor-pointer hover:bg-slate-50"
                >
                  <td className="px-4 py-3">
                    <div className="font-medium text-slate-900">{r.internName ?? '—'}</div>
                    <div className="text-xs text-slate-500">{r.internEmail ?? ''}</div>
                  </td>
                  <td className="px-4 py-3">
                    {r.templateTitle ?? <span className="italic text-slate-400">— none yet —</span>}
                  </td>
                  <td className="px-4 py-3">
                    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${stageToneClass(r.stage)}`}>
                      {r.stage}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-xs text-slate-500">
                    {humanDate(r.lastActivityAt)}
                  </td>
                  <td className="px-4 py-3 text-right">
                    {r.statusRaw === 'AWAITING_OFFER' ? (
                      <button
                        type="button"
                        onClick={(e) => { e.stopPropagation(); setPickerOpen(r); }}
                        className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-2.5 py-1.5 text-xs font-semibold text-white hover:bg-brand-800"
                      >
                        <Send className="h-3.5 w-3.5" />
                        Send document
                      </button>
                    ) : (
                      <ChevronRight className="ml-auto h-4 w-4 text-slate-400" />
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {pickerOpen && (
        <TemplatePickerModal
          row={pickerOpen}
          onClose={() => setPickerOpen(null)}
          onCreated={(id) => {
            setPickerOpen(null);
            router.push(`/careers/erm/offers/idms/${id}/fill`);
          }}
        />
      )}
    </div>
  );
}

function EmptyState({ hasAny }: { hasAny: boolean }) {
  return (
    <div className="rounded-lg border border-dashed border-slate-300 bg-slate-50 p-10 text-center">
      <FilePlus className="mx-auto h-8 w-8 text-slate-400" />
      <h2 className="mt-3 text-base font-semibold text-slate-800">
        {hasAny ? 'No documents match your filters' : 'Nothing to work on right now'}
      </h2>
      <p className="mt-1 text-sm text-slate-500">
        {hasAny
          ? 'Try a different tab or clear the search.'
          : "When someone clears their interview, they’ll show up here."}
      </p>
    </div>
  );
}

// ── Template picker modal ────────────────────────────────────────────

function TemplatePickerModal({
  row, onClose, onCreated,
}: {
  row: QueueRow;
  onClose: () => void;
  onCreated: (instanceId: string) => void;
}) {
  const [templates, setTemplates] = useState<PickableTemplate[]>([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [pickId, setPickId] = useState<string | null>(null);
  const [supersedeAsk, setSupersedeAsk] = useState<{
    prior: { id: string; title: string };
    templateId: string;
  } | null>(null);
  const [prior, setPrior] = useState<{ id: string; title: string; status: string }[]>([]);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        const [tplRes, historyRes] = await Promise.all([
          api.get<{ items: PickableTemplate[] }>('/api/v1/erm/idms/templates'),
          row.internLifecycleId
            ? api.get<{ items: { id: string; title: string; status: string }[] }>(
                `/api/v1/erm/idms/lifecycle/${row.internLifecycleId}/history`,
              )
            : Promise.resolve({ data: { items: [] } as { items: { id: string; title: string; status: string }[] } }),
        ]);
        setTemplates(tplRes.data.items ?? []);
        setPrior(historyRes.data.items ?? []);
      } catch (e) {
        const ax = e as { response?: { data?: { error?: string } } };
        setErr(ax.response?.data?.error ?? 'Could not load templates');
      } finally {
        setLoading(false);
      }
    })();
  }, [row.internLifecycleId]);

  async function proceed(templateId: string, supersedesInstanceId: string | null) {
    // Two payload shapes accepted by the server: existing-lifecycle path
    // stays byte-identical; awaiting-offer rows send applicationId and
    // let the server find-or-create the lifecycle inline.
    const payload = row.internLifecycleId
      ? { templateId, internLifecycleId: row.internLifecycleId, supersedesInstanceId }
      : { templateId, applicationId: row.applicationId, supersedesInstanceId };
    setCreating(true);
    setErr(null);
    try {
      const res = await api.post<{ id: string }>('/api/v1/erm/idms', payload);
      onCreated(res.data.id);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(ax.response?.data?.error ?? 'Could not create document');
      setCreating(false);
    }
  }

  function onSend() {
    if (!pickId) return;
    // Supersede ask — one clean question if a prior executed doc exists.
    const executed = prior.find((p) => p.status === 'FINALIZED');
    if (executed) {
      setSupersedeAsk({ prior: { id: executed.id, title: executed.title }, templateId: pickId });
      return;
    }
    void proceed(pickId, null);
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="w-full max-w-lg rounded-lg bg-white p-6 shadow-xl">
        <header className="flex items-start justify-between gap-4">
          <div>
            <h2 className="text-base font-semibold text-slate-900">Send a document</h2>
            <p className="mt-0.5 text-xs text-slate-500">
              For {row.internName ?? row.internEmail ?? 'this person'}. Pick a template to start.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-slate-400 hover:text-slate-700"
            aria-label="Close"
          >
            <X className="h-4 w-4" />
          </button>
        </header>

        {loading ? (
          <div className="mt-6 h-24 animate-pulse rounded-md bg-slate-100" />
        ) : templates.length === 0 ? (
          <p className="mt-6 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
            No published templates yet. Ask an admin to publish one from
            Document Templates.
          </p>
        ) : (
          <ul className="mt-5 space-y-2 max-h-[50vh] overflow-y-auto">
            {templates.map((t) => (
              <li key={t.id}>
                <label
                  className={`flex items-start gap-3 rounded-md border p-3 cursor-pointer ${
                    pickId === t.id ? 'border-brand-500 bg-brand-50/40' : 'border-slate-200 hover:bg-slate-50'
                  }`}
                >
                  <input
                    type="radio"
                    name="tpl"
                    className="mt-0.5"
                    checked={pickId === t.id}
                    onChange={() => setPickId(t.id)}
                  />
                  <div className="flex-1">
                    <p className="text-sm font-medium text-slate-900">{t.title}</p>
                    {t.description && (
                      <p className="text-xs text-slate-500">{t.description}</p>
                    )}
                    <p className="mt-0.5 text-[11px] text-slate-400">
                      {t.fieldCount} field{t.fieldCount === 1 ? '' : 's'} · <code className="font-mono">{t.key}</code>
                    </p>
                  </div>
                </label>
              </li>
            ))}
          </ul>
        )}

        {err && (
          <p className="mt-4 rounded-md border border-red-200 bg-red-50 p-3 text-xs text-red-800">
            {err}
          </p>
        )}

        <div className="mt-6 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={onSend}
            disabled={!pickId || creating}
            className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            {creating ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Send className="h-3.5 w-3.5" />}
            Start document
          </button>
        </div>

        {supersedeAsk && (
          <SupersedeAskDialog
            priorTitle={supersedeAsk.prior.title}
            onCancel={() => setSupersedeAsk(null)}
            onReplace={() => {
              const s = supersedeAsk;
              setSupersedeAsk(null);
              void proceed(s.templateId, s.prior.id);
            }}
            onCoexist={() => {
              const s = supersedeAsk;
              setSupersedeAsk(null);
              void proceed(s.templateId, null);
            }}
          />
        )}
      </div>
    </div>
  );
}

function SupersedeAskDialog({
  priorTitle, onReplace, onCoexist, onCancel,
}: {
  priorTitle: string;
  onReplace: () => void;
  onCoexist: () => void;
  onCancel: () => void;
}) {
  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-slate-900/70 p-4">
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-2xl">
        <h3 className="flex items-center gap-2 text-base font-semibold text-slate-900">
          <HistoryIcon className="h-4 w-4 text-slate-500" />
          Replace the existing document?
        </h3>
        <p className="mt-2 text-sm text-slate-600">
          This person already has a completed <span className="font-medium">“{priorTitle}”</span>.
          Should this new document replace it, or should both live in their history?
        </p>
        <div className="mt-6 flex flex-wrap justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          >
            Not now
          </button>
          <button
            type="button"
            onClick={onCoexist}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          >
            Keep both
          </button>
          <button
            type="button"
            onClick={onReplace}
            className="rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800"
          >
            Replace when executed
          </button>
        </div>
      </div>
    </div>
  );
}
