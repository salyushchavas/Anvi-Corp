'use client';

import { useEffect, useState } from 'react';
import api from '@/lib/api';
import type { UserStub } from '@/components/erm/offers/types';

interface Props {
  lifecycleId: string;
  currentTrainerId?: string | null;
  currentEvaluatorId?: string | null;
  currentManagerId?: string | null;
  open: boolean;
  onClose: () => void;
  onApplied: () => void;
}

export default function AssignReportingStructureModal({
  lifecycleId,
  currentTrainerId,
  currentEvaluatorId,
  currentManagerId,
  open,
  onClose,
  onApplied,
}: Props) {
  const [trainers, setTrainers] = useState<UserStub[]>([]);
  const [evaluators, setEvaluators] = useState<UserStub[]>([]);
  const [managers, setManagers] = useState<UserStub[]>([]);
  const [trainerId, setTrainerId] = useState(currentTrainerId ?? '');
  const [evaluatorId, setEvaluatorId] = useState(currentEvaluatorId ?? '');
  const [managerId, setManagerId] = useState(currentManagerId ?? '');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setErr(null);
    void (async () => {
      try {
        const [t, ev, m] = await Promise.all([
          api.get<UserStub[]>('/api/v1/erm/new-hire/eligible-trainers'),
          api.get<UserStub[]>('/api/v1/erm/new-hire/eligible-evaluators'),
          api.get<UserStub[]>('/api/v1/erm/new-hire/eligible-managers'),
        ]);
        setTrainers(t.data ?? []);
        setEvaluators(ev.data ?? []);
        setManagers(m.data ?? []);
      } catch {
        setTrainers([]);
        setEvaluators([]);
        setManagers([]);
      }
    })();
  }, [open]);

  if (!open) return null;

  async function submit() {
    setErr(null);
    if (!trainerId || !evaluatorId || !managerId) {
      setErr('All three roles are required.');
      return;
    }
    setSubmitting(true);
    try {
      await api.post(`/api/v1/erm/new-hire/${lifecycleId}/assign-reporting`, {
        trainerUserId: trainerId,
        evaluatorUserId: evaluatorId,
        managerUserId: managerId,
      });
      onApplied();
      onClose();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(
        ax.response?.data?.error ??
          (e instanceof Error ? e.message : 'Assignment failed'),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="w-full max-w-lg rounded-lg bg-white p-6 shadow-xl">
        <h2 className="text-lg font-semibold text-slate-900">
          Assign reporting structure
        </h2>
        <p className="mt-2 text-xs text-slate-500">
          All three roles are required. Onboarding packet assignment is blocked
          until this is complete.
        </p>
        <div className="mt-4 space-y-4">
          <RolePicker
            label="Trainer"
            value={trainerId}
            options={trainers}
            onChange={setTrainerId}
          />
          <RolePicker
            label="Evaluator"
            value={evaluatorId}
            options={evaluators}
            onChange={setEvaluatorId}
          />
          <RolePicker
            label="Manager"
            value={managerId}
            options={managers}
            onChange={setManagerId}
          />
          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
              {err}
            </p>
          )}
        </div>
        <div className="mt-6 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={submitting}
            className="rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            {submitting ? 'Saving…' : 'Assign'}
          </button>
        </div>
      </div>
    </div>
  );
}

function RolePicker({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: string;
  options: UserStub[];
  onChange: (v: string) => void;
}) {
  return (
    <div>
      <label className="text-sm font-medium text-slate-800">
        {label} <span className="text-red-600">*</span>
      </label>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
      >
        <option value="">Pick a {label.toLowerCase()}…</option>
        {options.map((o) => (
          <option key={o.userId} value={o.userId}>
            {o.fullName} · {o.currentInternCount} intern
            {o.currentInternCount === 1 ? '' : 's'}
          </option>
        ))}
      </select>
    </div>
  );
}
