'use client';

import { useState } from 'react';
import api from '@/lib/api';
import type { AssetStatus, ErmExitDetail } from './types';

type Props = {
  exitRecordId: string;
  initial: AssetStatus | null;
  onClose: () => void;
  onSaved: (updated: ErmExitDetail) => void;
};

const FIELDS: { key: keyof AssetStatus; label: string }[] = [
  { key: 'laptopReturned', label: 'Laptop returned' },
  { key: 'badgeReturned', label: 'Badge returned' },
  { key: 'buildingAccessRemoved', label: 'Building access removed' },
  { key: 'parkingPassReturned', label: 'Parking pass returned' },
  { key: 'keysReturned', label: 'Keys returned' },
];

export default function AssetStatusModal({
  exitRecordId,
  initial,
  onClose,
  onSaved,
}: Props) {
  const [s, setS] = useState<AssetStatus>(
    initial ?? {
      laptopReturned: null,
      badgeReturned: null,
      buildingAccessRemoved: null,
      parkingPassReturned: null,
      keysReturned: null,
      otherNotes: null,
    },
  );
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    setSaving(true);
    setErr(null);
    try {
      const res = await api.post<ErmExitDetail>(
        `/api/v1/erm/exits/${exitRecordId}/assets`,
        { status: s },
      );
      onSaved(res.data);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
      <div className="w-full max-w-md rounded-lg bg-white shadow-xl">
        <div className="border-b border-slate-200 px-5 py-3">
          <h3 className="text-sm font-semibold text-slate-900">
            Asset return status
          </h3>
          <p className="text-xs text-slate-500">
            Check off each item the intern has returned or that doesn't apply.
          </p>
        </div>
        <div className="space-y-2 px-5 py-4">
          {FIELDS.map((f) => (
            <label
              key={f.key}
              className="flex items-center justify-between gap-3 rounded-md border border-slate-200 px-3 py-2 text-sm"
            >
              <span className="text-slate-800">{f.label}</span>
              <input
                type="checkbox"
                checked={!!s[f.key]}
                onChange={(e) =>
                  setS({ ...s, [f.key]: e.target.checked })
                }
              />
            </label>
          ))}
          <label className="block text-xs font-medium text-slate-700">
            Other notes
            <textarea
              rows={2}
              value={s.otherNotes ?? ''}
              onChange={(e) => setS({ ...s, otherNotes: e.target.value })}
              className="mt-1 block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
            />
          </label>
          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
              {err}
            </p>
          )}
        </div>
        <div className="flex items-center justify-end gap-2 border-t border-slate-200 px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={saving}
            onClick={() => void submit()}
            className="rounded-md bg-brand-700 px-3 py-1.5 text-sm font-semibold text-white disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save assets'}
          </button>
        </div>
      </div>
    </div>
  );
}
