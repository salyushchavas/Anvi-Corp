'use client';

import { useCallback, useEffect, useState } from 'react';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';

type Tab = 'templates' | 'reasons' | 'workload';

const TABS: { key: Tab; label: string }[] = [
  { key: 'templates', label: 'Communication templates' },
  { key: 'reasons', label: 'Reason codes' },
  { key: 'workload', label: 'ERM workload' },
];

interface TemplateRow {
  key: string;
  channel: string;
  subjectTemplate: string;
  bodyTemplate: string;
  variablesCsv: string | null;
  active: boolean | null;
  updatedAt: string | null;
  updatedById: string | null;
  category: string;
}

interface ReasonCodeOption {
  code: string;
  label: string;
  requiresFreeText: boolean;
}

interface ReasonCodeGroup {
  category: string;
  options: ReasonCodeOption[];
}

interface WorkloadRow {
  ermUserId: string;
  ermName: string | null;
  ermEmail: string | null;
  activeInterns: number;
  applicationsOwned: number;
  offersCreated: number;
  openExceptions: number;
}

export default function SettingsPage() {
  const [tab, setTab] = useState<Tab>('templates');
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <PageHeader
          title="Settings"
          subtitle="Edit communication templates, view reason-code taxonomy, monitor ERM workload."
        />
        <div className="mb-3 flex flex-wrap items-center gap-2">
          {TABS.map((t) => (
            <button
              key={t.key}
              type="button"
              onClick={() => setTab(t.key)}
              className={
                'rounded-full border px-3 py-1 text-xs font-medium ' +
                (tab === t.key
                  ? 'border-brand-700 bg-brand-700 text-white'
                  : 'border-slate-200 text-slate-700 hover:bg-slate-50')
              }
            >
              {t.label}
            </button>
          ))}
        </div>
        {tab === 'templates' && <TemplatesTab />}
        {tab === 'reasons' && <ReasonsTab />}
        {tab === 'workload' && <WorkloadTab />}
      </DashboardLayout>
    </ProtectedRoute>
  );
}

// ── Templates ──────────────────────────────────────────────────────────

function TemplatesTab() {
  const [rows, setRows] = useState<TemplateRow[]>([]);
  const [selected, setSelected] = useState<TemplateRow | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await api.get<TemplateRow[]>('/api/v1/erm/settings/templates');
      setRows(res.data);
      setErr(null);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Failed to load templates');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // Group by category
  const groups: Record<string, TemplateRow[]> = {};
  for (const r of rows) {
    (groups[r.category] = groups[r.category] ?? []).push(r);
  }

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <div className="space-y-3">
        {err && (
          <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
            {err}
          </p>
        )}
        {Object.entries(groups).map(([cat, items]) => (
          <section
            key={cat}
            className="rounded-lg border border-slate-200 bg-white"
          >
            <h3 className="border-b border-slate-200 px-3 py-2 text-xs font-semibold uppercase text-slate-500">
              {cat}
            </h3>
            <ul className="divide-y divide-slate-100">
              {items.map((t) => (
                <li
                  key={t.key}
                  onClick={() => setSelected(t)}
                  className={
                    'cursor-pointer px-3 py-2 text-sm hover:bg-slate-50 ' +
                    (selected?.key === t.key ? 'bg-brand-50' : '')
                  }
                >
                  <div className="flex items-baseline justify-between">
                    <span className="font-medium text-slate-900">{t.key}</span>
                    {!t.active && (
                      <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] text-slate-600">
                        inactive
                      </span>
                    )}
                  </div>
                  <p className="truncate text-xs text-slate-500">
                    {t.subjectTemplate}
                  </p>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
      <div>
        {selected ? (
          <TemplateEditor
            template={selected}
            onSaved={(updated) => {
              setRows((prev) =>
                prev.map((r) => (r.key === updated.key ? updated : r)),
              );
              setSelected(updated);
            }}
          />
        ) : (
          <div className="rounded-lg border border-slate-200 bg-white p-10 text-center text-sm text-slate-500">
            Select a template on the left to edit.
          </div>
        )}
      </div>
    </div>
  );
}

function TemplateEditor({
  template,
  onSaved,
}: {
  template: TemplateRow;
  onSaved: (t: TemplateRow) => void;
}) {
  const [subject, setSubject] = useState(template.subjectTemplate);
  const [body, setBody] = useState(template.bodyTemplate);
  const [active, setActive] = useState(!!template.active);
  const [preview, setPreview] = useState<{ subject: string; body: string } | null>(null);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    setSubject(template.subjectTemplate);
    setBody(template.bodyTemplate);
    setActive(!!template.active);
    setPreview(null);
    setErr(null);
  }, [template.key]);

  const vars = (template.variablesCsv ?? '').split(',').map((s) => s.trim()).filter(Boolean);

  async function save() {
    setSaving(true);
    setErr(null);
    try {
      const res = await api.put<TemplateRow>(
        `/api/v1/erm/settings/templates/${template.key}`,
        { subjectTemplate: subject, bodyTemplate: body, active },
      );
      onSaved(res.data);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  async function restoreDefault() {
    if (!confirm(`Restore '${template.key}' to seeded default? Edits will be lost.`)) return;
    setSaving(true);
    setErr(null);
    try {
      const res = await api.post<TemplateRow>(
        `/api/v1/erm/settings/templates/${template.key}/restore-default`,
      );
      onSaved(res.data);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Restore failed');
    } finally {
      setSaving(false);
    }
  }

  async function runPreview() {
    setErr(null);
    try {
      const res = await api.post<{ subject: string; body: string }>(
        `/api/v1/erm/settings/templates/${template.key}/preview`,
        {},
      );
      setPreview(res.data);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Preview failed');
    }
  }

  function insertVar(v: string) {
    setBody((b) => b + '{{' + v + '}}');
  }

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4">
      <div className="mb-2 flex items-baseline justify-between">
        <h3 className="text-sm font-semibold text-slate-900">{template.key}</h3>
        <label className="flex items-center gap-2 text-xs text-slate-600">
          <input
            type="checkbox"
            checked={active}
            onChange={(e) => setActive(e.target.checked)}
          />
          Active
        </label>
      </div>
      <label className="block text-xs font-medium text-slate-700">
        Subject template
        <input
          value={subject}
          onChange={(e) => setSubject(e.target.value)}
          className="mt-1 block w-full rounded-md border border-slate-200 px-2 py-1.5 font-mono text-sm"
        />
      </label>
      <label className="mt-2 block text-xs font-medium text-slate-700">
        Body template
        <textarea
          rows={10}
          value={body}
          onChange={(e) => setBody(e.target.value)}
          className="mt-1 block w-full rounded-md border border-slate-200 px-2 py-1.5 font-mono text-xs"
        />
      </label>
      {vars.length > 0 && (
        <div className="mt-2">
          <p className="mb-1 text-[10px] uppercase text-slate-500">Variables (click to insert)</p>
          <div className="flex flex-wrap gap-1">
            {vars.map((v) => (
              <button
                key={v}
                type="button"
                onClick={() => insertVar(v)}
                className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] text-slate-700 hover:bg-slate-200"
              >
                {`{{${v}}}`}
              </button>
            ))}
          </div>
        </div>
      )}
      {err && (
        <p className="mt-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
          {err}
        </p>
      )}
      <div className="mt-3 flex gap-2">
        <button
          type="button"
          onClick={() => void save()}
          disabled={saving}
          className="rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white disabled:opacity-50"
        >
          {saving ? 'Saving…' : 'Save'}
        </button>
        <button
          type="button"
          onClick={() => void runPreview()}
          className="rounded-md border border-slate-300 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
        >
          Preview
        </button>
        <button
          type="button"
          onClick={() => void restoreDefault()}
          className="ml-auto rounded-md border border-amber-300 bg-amber-50 px-3 py-1.5 text-xs font-medium text-amber-700 hover:bg-amber-100"
        >
          Restore default
        </button>
      </div>
      {preview && (
        <div className="mt-4 rounded-md border border-slate-200 bg-slate-50 p-3">
          <p className="text-[10px] font-semibold uppercase text-slate-500">Preview</p>
          <p className="mt-1 text-sm font-medium text-slate-900">{preview.subject}</p>
          <pre className="mt-2 whitespace-pre-wrap text-xs text-slate-700">
            {preview.body}
          </pre>
        </div>
      )}
    </section>
  );
}

// ── Reason codes ───────────────────────────────────────────────────────

function ReasonsTab() {
  const [groups, setGroups] = useState<ReasonCodeGroup[]>([]);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    api
      .get<ReasonCodeGroup[]>('/api/v1/erm/settings/reason-codes')
      .then((res) => setGroups(res.data))
      .catch((e: any) =>
        setErr(e?.response?.data?.error ?? 'Failed to load reason codes'),
      );
  }, []);

  if (err) {
    return (
      <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
        {err}
      </p>
    );
  }
  return (
    <div className="space-y-3">
      {groups.map((g) => (
        <details
          key={g.category}
          className="rounded-lg border border-slate-200 bg-white"
        >
          <summary className="cursor-pointer px-3 py-2 text-sm font-semibold text-slate-900">
            {g.category}{' '}
            <span className="text-[11px] font-normal text-slate-500">
              ({g.options.length})
            </span>
          </summary>
          <ul className="divide-y divide-slate-100">
            {g.options.map((o) => (
              <li
                key={o.code}
                className="flex items-center justify-between px-3 py-1.5 text-xs"
              >
                <span>
                  <code className="text-slate-900">{o.code}</code>
                  <span className="ml-2 text-slate-600">{o.label}</span>
                </span>
                {o.requiresFreeText && (
                  <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] text-amber-700">
                    requires text
                  </span>
                )}
              </li>
            ))}
          </ul>
        </details>
      ))}
    </div>
  );
}

// ── Workload ───────────────────────────────────────────────────────────

function WorkloadTab() {
  const [rows, setRows] = useState<WorkloadRow[]>([]);
  const [singleMe, setSingleMe] = useState<WorkloadRow | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  useEffect(() => {
    api
      .get<WorkloadRow[]>('/api/v1/erm/settings/workload')
      .then((res) => setRows(res.data))
      .catch((e: any) => {
        if (e?.response?.status === 403) {
          setForbidden(true);
          api
            .get<WorkloadRow>('/api/v1/erm/settings/workload/me')
            .then((r) => setSingleMe(r.data))
            .catch((e2: any) =>
              setErr(e2?.response?.data?.error ?? 'Failed to load workload'),
            );
        } else {
          setErr(e?.response?.data?.error ?? 'Failed to load workload');
        }
      });
  }, []);

  if (err) {
    return (
      <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
        {err}
      </p>
    );
  }

  const display = forbidden ? (singleMe ? [singleMe] : []) : rows;
  const maxInterns = Math.max(1, ...display.map((r) => r.activeInterns));
  if (display.length === 0) {
    return (
      <p className="rounded-lg border border-slate-200 bg-white p-10 text-center text-sm text-slate-500">
        No ERM users found.
      </p>
    );
  }
  return (
    <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
      <table className="min-w-full text-sm">
        <thead className="bg-slate-50 text-left text-[10px] font-semibold uppercase text-slate-500">
          <tr>
            <th className="px-3 py-2">ERM</th>
            <th className="px-3 py-2">Active interns</th>
            <th className="px-3 py-2">Apps owned</th>
            <th className="px-3 py-2">Offers sent</th>
            <th className="px-3 py-2">Open exceptions</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {display.map((r) => (
            <tr key={r.ermUserId}>
              <td className="px-3 py-2">
                <span className="block text-sm text-slate-900">
                  {r.ermName ?? '(unknown)'}
                </span>
                <span className="block text-[11px] text-slate-500">
                  {r.ermEmail}
                </span>
              </td>
              <td className="px-3 py-2">
                <div className="flex items-center gap-2">
                  <span className="tabular-nums">{r.activeInterns}</span>
                  <span className="h-1.5 w-24 rounded-full bg-slate-100">
                    <span
                      className="block h-1.5 rounded-full bg-brand-600"
                      style={{
                        width:
                          (r.activeInterns / maxInterns) * 100 + '%',
                      }}
                    />
                  </span>
                </div>
              </td>
              <td className="px-3 py-2">{r.applicationsOwned}</td>
              <td className="px-3 py-2">{r.offersCreated}</td>
              <td className="px-3 py-2">{r.openExceptions}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {forbidden && (
        <p className="px-3 py-2 text-[11px] text-slate-500">
          You're seeing only your own row. SUPER_ADMIN sees all ERMs.
        </p>
      )}
    </div>
  );
}
