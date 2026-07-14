'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { Bell, ChevronLeft, Info, Save, Sliders } from 'lucide-react';
import api from '@/lib/api';

interface EvaluatorSettings {
  defaultDurationMinutes: number | null;
  reminderFrequency: 'DAILY' | 'WEEKLY' | 'NEVER' | null;
  notifyAcknowledged: boolean | null;
  notifyDsoWindow: boolean | null;
  prefsRemindersEmail: boolean | null;
  prefsEngagementUpdatesEmail: boolean | null;
  fullName: string | null;
  email: string | null;
  zoomEmail: string | null;
}

type Tab = 'preferences' | 'notifications' | 'about';

export default function EvaluatorSettingsPage() {
  const [tab, setTab] = useState<Tab>('preferences');
  const [data, setData] = useState<EvaluatorSettings | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<Date | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<EvaluatorSettings>('/api/v1/evaluator/settings');
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  async function save(partial: Partial<EvaluatorSettings>) {
    setSaving(true);
    setErr(null);
    try {
      const res = await api.patch<EvaluatorSettings>(
        '/api/v1/evaluator/settings',
        partial,
      );
      setData(res.data);
      setSavedAt(new Date());
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to save');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="mx-auto max-w-4xl space-y-4 p-6">
      <p className="text-xs text-slate-500">
        <Link
          href="/careers/evaluator"
          className="inline-flex items-center gap-1 hover:text-slate-700"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
          Evaluator home
        </Link>
      </p>

      <header className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <h1 className="text-xl font-semibold text-slate-900">Settings</h1>
        <p className="text-xs text-slate-500">
          Per-Evaluator preferences and notification routing.
        </p>
        <nav className="mt-4 flex gap-1 border-b border-slate-200">
          <TabBtn label="Preferences" icon={<Sliders className="h-3.5 w-3.5" />} active={tab === 'preferences'} onClick={() => setTab('preferences')} />
          <TabBtn label="Notifications" icon={<Bell className="h-3.5 w-3.5" />} active={tab === 'notifications'} onClick={() => setTab('notifications')} />
          <TabBtn label="About" icon={<Info className="h-3.5 w-3.5" />} active={tab === 'about'} onClick={() => setTab('about')} />
        </nav>
      </header>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}
      {loading && !data && (
        <div className="h-48 animate-pulse rounded-lg bg-slate-100" />
      )}

      {data && tab === 'preferences' && (
        <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-900">Preferences</h2>
          <div className="mt-4 space-y-4">
            <Row label="Default session duration"
              hint="Pre-fills the Schedule Session modal so you don't pick the same value twice.">
              <select
                value={data.defaultDurationMinutes ?? 45}
                onChange={(e) => void save({
                  defaultDurationMinutes: parseInt(e.target.value, 10),
                })}
                disabled={saving}
                className="rounded-md border border-slate-200 px-3 py-1.5 text-sm"
              >
                {[15, 30, 45, 60, 75, 90, 120].map((d) => (
                  <option key={d} value={d}>{d} minutes</option>
                ))}
              </select>
            </Row>
            <Row label="Email reminder frequency"
              hint="How often we email you about unacknowledged evaluations.">
              <select
                value={data.reminderFrequency ?? 'WEEKLY'}
                onChange={(e) => void save({
                  reminderFrequency: e.target.value as EvaluatorSettings['reminderFrequency'],
                })}
                disabled={saving}
                className="rounded-md border border-slate-200 px-3 py-1.5 text-sm"
              >
                <option value="DAILY">Daily</option>
                <option value="WEEKLY">Weekly</option>
                <option value="NEVER">Never</option>
              </select>
            </Row>
          </div>
        </section>
      )}

      {data && tab === 'notifications' && (
        <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-900">Notifications</h2>
          <div className="mt-4 space-y-3">
            <Toggle label="Notify me when an intern acknowledges an evaluation"
              hint="In-app + email. Transactional emails are never suppressed."
              value={data.notifyAcknowledged ?? true}
              disabled={saving}
              onChange={(v) => void save({ notifyAcknowledged: v })}
            />
            <Toggle label="Notify me when an I-983 DSO submission window approaches expiry"
              hint="Federal STEM OPT compliance reminder."
              value={data.notifyDsoWindow ?? true}
              disabled={saving}
              onChange={(v) => void save({ notifyDsoWindow: v })}
            />
            <hr className="border-slate-200" />
            <Toggle label="Receive email reminders (general)"
              hint="Account-level opt-out for all reminder emails."
              value={data.prefsRemindersEmail ?? true}
              disabled={saving}
              onChange={(v) => void save({ prefsRemindersEmail: v })}
            />
            <Toggle label="Receive engagement update emails"
              hint="Weekly digest of evaluees' activity."
              value={data.prefsEngagementUpdatesEmail ?? true}
              disabled={saving}
              onChange={(v) => void save({ prefsEngagementUpdatesEmail: v })}
            />
          </div>
        </section>
      )}

      {data && tab === 'about' && (
        <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-900">About this account</h2>
          <dl className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2 text-sm">
            <Field label="Name" value={data.fullName ?? '—'} />
            <Field label="Email" value={data.email ?? '—'} />
            <Field label="Zoom email" value={data.zoomEmail ?? 'Not provisioned'} />
            <Field label="Role" value="Evaluator" />
          </dl>
          <p className="mt-4 text-[11px] text-slate-500">
            Account details, Zoom provisioning, and role assignments are managed
            by ERM / SUPER_ADMIN. Contact your admin to update these fields.
          </p>
        </section>
      )}

      <div className="flex items-center justify-end text-[11px] text-slate-500">
        {saving ? 'Saving…' : savedAt && (
          <span className="inline-flex items-center gap-1 text-green-700">
            <Save className="h-3 w-3" />
            Saved {savedAt.toLocaleTimeString()}
          </span>
        )}
      </div>
    </div>
  );
}

function TabBtn({
  label, icon, active, onClick,
}: {
  label: string;
  icon: React.ReactNode;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={
        'inline-flex items-center gap-1 border-b-2 px-3 py-2 text-xs font-medium ' +
        (active
          ? 'border-brand-700 text-brand-800'
          : 'border-transparent text-slate-600 hover:text-slate-800')
      }
    >
      {icon}
      {label}
    </button>
  );
}

function Row({
  label, hint, children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div>
        <p className="text-sm font-medium text-slate-800">{label}</p>
        {hint && <p className="text-[11px] text-slate-500">{hint}</p>}
      </div>
      <div>{children}</div>
    </div>
  );
}

function Toggle({
  label, hint, value, disabled, onChange,
}: {
  label: string;
  hint?: string;
  value: boolean;
  disabled?: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <label className={'flex cursor-pointer items-center justify-between gap-3 rounded-md border border-slate-200 px-3 py-2 ' + (disabled ? 'opacity-60' : 'hover:bg-slate-50')}>
      <div>
        <p className="text-sm font-medium text-slate-800">{label}</p>
        {hint && <p className="text-[11px] text-slate-500">{hint}</p>}
      </div>
      <input
        type="checkbox"
        checked={value}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
        className="h-4 w-4 accent-teal-700"
      />
    </label>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border border-slate-100 bg-slate-50 p-3">
      <dt className="text-[10px] uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="mt-1 text-sm font-medium text-slate-900">{value}</dd>
    </div>
  );
}
