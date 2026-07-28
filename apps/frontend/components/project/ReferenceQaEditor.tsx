'use client';

import { useCallback, useEffect, useState } from 'react';
import { HelpCircle, Plus, Save, Trash2 } from 'lucide-react';
import api from '@/lib/careers/api';

/**
 * Trainer-facing editor for a project's Reference Q&A pairs. Loads the
 * current list, lets the trainer add / edit / remove pairs, saves the
 * whole list atomically to the write endpoint. Neutral copy — "Reference
 * Q&A" / "Discussion Points from Trainer" only. No framing suggests the
 * evaluator lacks knowledge; this is trainer-provided reference material.
 *
 * <p>Save is decoupled from the outer review-feedback submit — the pairs
 * persist on the Project row, independent of any single submission or
 * review round. That way the reference material carries across
 * resubmissions and future evaluations of the same project.</p>
 */
export interface ReferenceQaPair {
  question: string;
  answer: string;
  order?: number | null;
}

interface Props {
  projectId: string;
}

export default function ReferenceQaEditor({ projectId }: Props) {
  const [pairs, setPairs] = useState<ReferenceQaPair[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadErr, setLoadErr] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveErr, setSaveErr] = useState<string | null>(null);
  const [savedAt, setSavedAt] = useState<Date | null>(null);
  const [dirty, setDirty] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadErr(null);
    try {
      const res = await api.get<{ pairs: ReferenceQaPair[] }>(
        `/api/v1/projects/${projectId}/reference-qa`,
      );
      setPairs((res.data.pairs ?? []).map((p) => ({
        question: p.question ?? '',
        answer: p.answer ?? '',
      })));
      setDirty(false);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setLoadErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load reference Q&A');
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => { void load(); }, [load]);

  function update(idx: number, patch: Partial<ReferenceQaPair>) {
    setPairs((prev) => prev.map((p, i) => (i === idx ? { ...p, ...patch } : p)));
    setDirty(true);
  }
  function addRow() {
    setPairs((prev) => [...prev, { question: '', answer: '' }]);
    setDirty(true);
  }
  function removeRow(idx: number) {
    setPairs((prev) => prev.filter((_, i) => i !== idx));
    setDirty(true);
  }

  async function save() {
    setSaving(true);
    setSaveErr(null);
    try {
      const clean = pairs
        .map((p) => ({ question: p.question.trim(), answer: p.answer.trim() }))
        .filter((p) => p.question || p.answer);
      // Reject half-filled pairs client-side so the backend 400 message
      // never surprises the trainer mid-save.
      const halfFilled = clean.find((p) => !p.question || !p.answer);
      if (halfFilled) {
        setSaveErr('Every pair needs both a question and an answer.');
        setSaving(false);
        return;
      }
      const res = await api.put<{ pairs: ReferenceQaPair[] }>(
        `/api/v1/trainer/projects/${projectId}/reference-qa`,
        { pairs: clean },
      );
      setPairs((res.data.pairs ?? []).map((p) => ({
        question: p.question ?? '',
        answer: p.answer ?? '',
      })));
      setSavedAt(new Date());
      setDirty(false);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setSaveErr(ax.response?.data?.error ?? ax.message ?? 'Failed to save');
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="rounded-md border border-slate-200 bg-white p-3">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <HelpCircle className="h-4 w-4 text-brand-700" />
          <h4 className="text-sm font-semibold text-slate-900">
            Reference Q&amp;A <span className="text-xs font-normal text-slate-500">(optional)</span>
          </h4>
        </div>
        <div className="flex items-center gap-2">
          {savedAt && !dirty && (
            <span className="text-[11px] text-green-700">
              Saved at {savedAt.toLocaleTimeString()}
            </span>
          )}
          <button type="button" onClick={save} disabled={saving || !dirty}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-2.5 py-1 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-60">
            <Save className="h-3 w-3" />
            {saving ? 'Saving…' : 'Save Q&A'}
          </button>
        </div>
      </div>
      <p className="mt-1 text-[11px] text-slate-500">
        Discussion points for this project — question + answer pairs the
        evaluator can reference during the evaluation.
      </p>
      {loading && <div className="mt-2 h-16 animate-pulse rounded bg-slate-100" aria-hidden />}
      {loadErr && (
        <p className="mt-2 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
          {loadErr}
        </p>
      )}
      {!loading && !loadErr && (
        <div className="mt-3 space-y-2">
          {pairs.length === 0 && (
            <p className="rounded-md border border-dashed border-slate-300 p-3 text-center text-xs text-slate-500">
              No reference Q&amp;A yet. Add the first one below.
            </p>
          )}
          {pairs.map((p, idx) => (
            <div key={idx} className="rounded-md border border-slate-200 bg-slate-50 p-2">
              <div className="flex items-start gap-2">
                <div className="flex-1 space-y-2">
                  <div>
                    <label className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
                      Question {idx + 1}
                    </label>
                    <textarea
                      value={p.question}
                      onChange={(e) => update(idx, { question: e.target.value })}
                      rows={2}
                      maxLength={2000}
                      placeholder="e.g. Why was Kubernetes chosen over ECS for this deployment?"
                      className="mt-0.5 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs"
                    />
                  </div>
                  <div>
                    <label className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
                      Answer
                    </label>
                    <textarea
                      value={p.answer}
                      onChange={(e) => update(idx, { answer: e.target.value })}
                      rows={3}
                      maxLength={5000}
                      placeholder="The reference answer the evaluator can compare against."
                      className="mt-0.5 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs"
                    />
                  </div>
                </div>
                <button type="button" onClick={() => removeRow(idx)}
                  aria-label="Remove pair"
                  className="rounded-md border border-slate-200 bg-white p-1 text-slate-500 hover:bg-red-50 hover:text-red-700">
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              </div>
            </div>
          ))}
          <button type="button" onClick={addRow}
            className="inline-flex items-center gap-1 rounded-md border border-dashed border-slate-300 px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50">
            <Plus className="h-3 w-3" /> Add pair
          </button>
        </div>
      )}
      {saveErr && (
        <p className="mt-2 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
          {saveErr}
        </p>
      )}
    </section>
  );
}
