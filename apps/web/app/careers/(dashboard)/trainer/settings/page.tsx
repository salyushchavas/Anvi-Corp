'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import api from '@/lib/careers/api';
import { Bell, CheckCircle2, Info, Save, Sliders, Video } from 'lucide-react';
import { BRAND } from '@/lib/careers/brand';

type Settings = {
  defaultRecurrence: string | null;
  defaultDuration: number | null;
  reviewPriority: string | null;
  notifyStakeholders: boolean | null;
  emailFrequency: string | null;
  notifySubmissions: boolean | null;
  notifyEscalationResolved: boolean | null;
  prefsReminders: boolean | null;
  prefsEngagementUpdates: boolean | null;
};

const DEFAULTS: Settings = {
  defaultRecurrence: 'WEEKLY',
  defaultDuration: 30,
  reviewPriority: 'OLDEST',
  notifyStakeholders: true,
  emailFrequency: 'DAILY',
  notifySubmissions: true,
  notifyEscalationResolved: true,
  prefsReminders: true,
  prefsEngagementUpdates: true,
};

type Tab = 'PREFERENCES' | 'NOTIFICATIONS' | 'ABOUT';

export default function TrainerSettingsPage() {
  const [tab, setTab] = useState<Tab>('PREFERENCES');
  const [settings, setSettings] = useState<Settings | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<Date | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<Settings>('/api/v1/trainer/settings');
      const merged: Settings = {
        defaultRecurrence: res.data.defaultRecurrence ?? DEFAULTS.defaultRecurrence,
        defaultDuration: res.data.defaultDuration ?? DEFAULTS.defaultDuration,
        reviewPriority: res.data.reviewPriority ?? DEFAULTS.reviewPriority,
        notifyStakeholders: res.data.notifyStakeholders ?? DEFAULTS.notifyStakeholders,
        emailFrequency: res.data.emailFrequency ?? DEFAULTS.emailFrequency,
        notifySubmissions: res.data.notifySubmissions ?? DEFAULTS.notifySubmissions,
        notifyEscalationResolved: res.data.notifyEscalationResolved ?? DEFAULTS.notifyEscalationResolved,
        prefsReminders: res.data.prefsReminders ?? DEFAULTS.prefsReminders,
        prefsEngagementUpdates: res.data.prefsEngagementUpdates ?? DEFAULTS.prefsEngagementUpdates,
      };
      setSettings(merged);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  async function save() {
    if (!settings) return;
    setSaving(true);
    setErr(null);
    try {
      const res = await api.put<Settings>('/api/v1/trainer/settings', settings);
      setSettings({
        defaultRecurrence: res.data.defaultRecurrence ?? DEFAULTS.defaultRecurrence,
        defaultDuration: res.data.defaultDuration ?? DEFAULTS.defaultDuration,
        reviewPriority: res.data.reviewPriority ?? DEFAULTS.reviewPriority,
        notifyStakeholders: res.data.notifyStakeholders ?? DEFAULTS.notifyStakeholders,
        emailFrequency: res.data.emailFrequency ?? DEFAULTS.emailFrequency,
        notifySubmissions: res.data.notifySubmissions ?? DEFAULTS.notifySubmissions,
        notifyEscalationResolved: res.data.notifyEscalationResolved ?? DEFAULTS.notifyEscalationResolved,
        prefsReminders: res.data.prefsReminders ?? DEFAULTS.prefsReminders,
        prefsEngagementUpdates: res.data.prefsEngagementUpdates ?? DEFAULTS.prefsEngagementUpdates,
      });
      setSavedAt(new Date());
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  function patch<K extends keyof Settings>(k: K, v: Settings[K]) {
    setSettings((s) => (s ? { ...s, [k]: v } : s));
  }

  return (
    <div className="mx-auto max-w-4xl space-y-4 p-6">
      <div>
        <p className="text-xs text-slate-500">
          <Link href="/careers/trainer" className="hover:text-slate-700">← Trainer dashboard</Link>
        </p>
        <h1 className="mt-1 text-xl font-semibold text-slate-900">Settings</h1>
        <p className="text-xs text-slate-500">
          Personal trainer preferences. Stored per-user — changing them does not affect other trainers.
        </p>
      </div>

      <div className="flex gap-1 border-b border-slate-200">
        <TabButton active={tab === 'PREFERENCES'} onClick={() => setTab('PREFERENCES')}
          icon={<Sliders className="h-3.5 w-3.5" />} label="Preferences" />
        <TabButton active={tab === 'NOTIFICATIONS'} onClick={() => setTab('NOTIFICATIONS')}
          icon={<Bell className="h-3.5 w-3.5" />} label="Notifications" />
        <TabButton active={tab === 'ABOUT'} onClick={() => setTab('ABOUT')}
          icon={<Info className="h-3.5 w-3.5" />} label="About" />
      </div>

      {err && <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err}</p>}

      {loading || !settings ? (
        <div className="h-48 animate-pulse rounded-lg bg-slate-100" />
      ) : (
        <div className="space-y-4">
          {tab === 'PREFERENCES' && (
            <div className="space-y-4 rounded-lg border border-slate-200 bg-white p-5">
              <Group title="Schedule meeting defaults"
                hint="Pre-selected values on the Schedule Meeting modal.">
                <Field label="Default recurrence">
                  <select value={settings.defaultRecurrence ?? 'WEEKLY'}
                    onChange={(e) => patch('defaultRecurrence', e.target.value)}
                    className="rounded-md border border-slate-200 px-2 py-1.5 text-sm">
                    <option value="WEEKLY">Weekly</option>
                    <option value="NONE">One-off (no recurrence)</option>
                  </select>
                </Field>
                <Field label="Default duration (minutes)">
                  <input type="number" min={15} max={180} step={5}
                    value={settings.defaultDuration ?? 30}
                    onChange={(e) => patch('defaultDuration', Number(e.target.value))}
                    className="w-24 rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
                </Field>
              </Group>

              <Group title="Review queue"
                hint="Default sort applied when you open Pending Reviews.">
                <Field label="Review priority">
                  <select value={settings.reviewPriority ?? 'OLDEST'}
                    onChange={(e) => patch('reviewPriority', e.target.value)}
                    className="rounded-md border border-slate-200 px-2 py-1.5 text-sm">
                    <option value="OLDEST">Oldest first</option>
                    <option value="NEWEST">Newest first</option>
                    <option value="INTERN">By intern</option>
                  </select>
                </Field>
              </Group>

              <Group title="Project assignment"
                hint="Default state of the stakeholder-notify checkbox on the assignment wizard.">
                <Toggle label="Notify intern + manager + ERM on assignment"
                  value={settings.notifyStakeholders ?? true}
                  onChange={(v) => patch('notifyStakeholders', v)} />
              </Group>

              <ZoomHostEmailCard />
            </div>
          )}

          {tab === 'NOTIFICATIONS' && (
            <div className="space-y-4 rounded-lg border border-slate-200 bg-white p-5">
              <Group title="Email digest" hint="In-app reminders are always shown live; this controls email cadence.">
                <Field label="Email frequency">
                  <select value={settings.emailFrequency ?? 'DAILY'}
                    onChange={(e) => patch('emailFrequency', e.target.value)}
                    className="rounded-md border border-slate-200 px-2 py-1.5 text-sm">
                    <option value="DAILY">Daily digest</option>
                    <option value="WEEKLY">Weekly digest</option>
                    <option value="NEVER">Never</option>
                  </select>
                </Field>
              </Group>

              <Group title="Trainer events">
                <Toggle label="Notify me when an intern submits a project for review"
                  value={settings.notifySubmissions ?? true}
                  onChange={(v) => patch('notifySubmissions', v)} />
                <Toggle label="Notify me when one of my escalations is reviewed by ERM"
                  value={settings.notifyEscalationResolved ?? true}
                  onChange={(v) => patch('notifyEscalationResolved', v)} />
              </Group>

              <Group title="Account-wide" hint="Mirrored from your account preferences.">
                <Toggle label="Reminder emails (deadlines, weekly meetings)"
                  value={settings.prefsReminders ?? true}
                  onChange={(v) => patch('prefsReminders', v)} />
                <Toggle label="Engagement updates (product news, new features)"
                  value={settings.prefsEngagementUpdates ?? true}
                  onChange={(v) => patch('prefsEngagementUpdates', v)} />
              </Group>

              <p className="rounded-md border border-slate-200 bg-slate-50 p-3 text-[11px] text-slate-600">
                Transactional emails (assignment notices, escalation summaries) always send regardless of these toggles.
              </p>
            </div>
          )}

          {tab === 'ABOUT' && (
            <div className="space-y-3 rounded-lg border border-slate-200 bg-white p-5 text-sm text-slate-700">
              <h3 className="text-sm font-semibold text-slate-900">About the Trainer dashboard</h3>
              <p>
                You are operating as a <strong>Trainer</strong>. This role assigns 2 projects per intern per month,
                runs weekly meetings, reviews project submissions, and escalates blockers to ERM + Manager.
              </p>
              <ul className="list-inside list-disc space-y-1 text-xs text-slate-600">
                <li>Doc §3 — your permission boundaries (training scope only; no payroll / HR data).</li>
                <li>Doc §5 — 2-projects-per-month rule.</li>
                <li>Doc §9 — Pending Reviews queue + 4-decision feedback flow.</li>
                <li>Doc §11 — Reports + Feedback History acceptance criteria.</li>
              </ul>
              <p className="text-xs text-slate-500">Phase: Trainer Phase 4 (closes Trainer dashboard doc).</p>
            </div>
          )}

          {tab !== 'ABOUT' && (
            <div className="flex items-center justify-between rounded-lg border border-slate-200 bg-white px-4 py-3">
              <div className="text-xs text-slate-500">
                {savedAt && (
                  <span className="inline-flex items-center gap-1 text-green-700">
                    <CheckCircle2 className="h-3.5 w-3.5" />
                    Saved {savedAt.toLocaleTimeString()}
                  </span>
                )}
              </div>
              <div className="flex gap-2">
                <button type="button" onClick={() => void load()}
                  className="rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50">
                  Discard
                </button>
                <button type="button" onClick={() => void save()} disabled={saving}
                  className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-4 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300">
                  <Save className="h-3.5 w-3.5" />
                  {saving ? 'Saving…' : 'Save changes'}
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function TabButton({ active, onClick, icon, label }: {
  active: boolean; onClick: () => void; icon: React.ReactNode; label: string;
}) {
  return (
    <button type="button" onClick={onClick}
      className={'-mb-px inline-flex items-center gap-1.5 border-b-2 px-3 py-2 text-sm ' +
        (active
          ? 'border-brand-700 font-semibold text-brand-700'
          : 'border-transparent text-slate-600 hover:text-slate-900')}>
      {icon}
      {label}
    </button>
  );
}

function Group({ title, hint, children }: {
  title: string; hint?: string; children: React.ReactNode;
}) {
  return (
    <div className="space-y-2">
      <div>
        <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-600">{title}</h3>
        {hint && <p className="text-[11px] text-slate-500">{hint}</p>}
      </div>
      <div className="space-y-2">{children}</div>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-wrap items-center justify-between gap-3">
      <span className="text-sm text-slate-700">{label}</span>
      {children}
    </label>
  );
}

/**
 * Self-service writer for users.zoom_email. Saves to a dedicated
 * endpoint (PUT /api/v1/users/me/zoom-email) so it doesn't collide with
 * the trainer-settings PUT above. Must match the user's licensed Zoom
 * account on the company tenant — when blank, ZoomService falls back to
 * the service account ("me") and the user is just an attendee on
 * meetings they "host" from this app.
 */
function ZoomHostEmailCard() {
  const [value, setValue] = useState<string>('');
  const [initial, setInitial] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [savedAt, setSavedAt] = useState<Date | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const res = await api.get<{ zoomEmail: string | null }>('/api/v1/users/me');
        setInitial(res.data.zoomEmail ?? '');
        setValue(res.data.zoomEmail ?? '');
      } catch {
        // soft-fail; user can still type and save
      } finally { setLoading(false); }
    })();
  }, []);

  async function save() {
    setSaving(true); setErr(null);
    try {
      await api.put('/api/v1/users/me/zoom-email', { zoomEmail: value.trim() });
      setInitial(value.trim());
      setSavedAt(new Date());
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Save failed');
    } finally { setSaving(false); }
  }

  const changed = value.trim() !== initial.trim();

  return (
    <div className="space-y-2 border-t border-slate-200 pt-4">
      <div>
        <h3 className="inline-flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-slate-600">
          <Video className="h-3.5 w-3.5" /> Zoom host email
        </h3>
        <p className="text-[11px] text-slate-500">
          Must match your licensed Zoom account on the {BRAND.name} tenant.
          When set, you host your own weekly meetings + interviews.
          When blank, meetings fall back to the service account.
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <input
          type="email"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder={loading ? 'Loading…' : 'you@skyzen.com'}
          disabled={loading || saving}
          className="min-w-[220px] flex-1 rounded-md border border-slate-200 px-2 py-1.5 text-sm"
        />
        <button
          type="button"
          onClick={() => void save()}
          disabled={loading || saving || !changed}
          className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300"
        >
          <Save className="h-3.5 w-3.5" />
          {saving ? 'Saving…' : 'Save Zoom email'}
        </button>
      </div>
      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 px-2 py-1.5 text-xs text-red-800">{err}</p>
      )}
      {savedAt && !err && (
        <p className="inline-flex items-center gap-1 text-[11px] text-green-700">
          <CheckCircle2 className="h-3 w-3" /> Saved {savedAt.toLocaleTimeString()}
        </p>
      )}
      <p className="rounded-md border border-amber-200 bg-amber-50 px-2 py-1.5 text-[11px] text-amber-900">
        Hosting requires a paid Zoom seat on the {BRAND.name} account. If you
        haven&apos;t been provisioned yet, leave this blank — your meetings
        will still be created under the service-account host.
      </p>
    </div>
  );
}

function Toggle({ label, value, onChange }: {
  label: string; value: boolean; onChange: (v: boolean) => void;
}) {
  return (
    <label className="flex cursor-pointer items-center justify-between gap-3 rounded-md border border-slate-200 px-3 py-2 hover:bg-slate-50">
      <span className="text-sm text-slate-700">{label}</span>
      <button type="button" role="switch" aria-checked={value}
        onClick={() => onChange(!value)}
        className={'relative inline-flex h-5 w-9 items-center rounded-full transition ' +
          (value ? 'bg-brand-600' : 'bg-slate-300')}>
        <span className={'inline-block h-4 w-4 transform rounded-full bg-white transition ' +
          (value ? 'translate-x-4' : 'translate-x-0.5')} />
      </button>
    </label>
  );
}
