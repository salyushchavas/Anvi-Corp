'use client';

import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from 'react';
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
 *
 * <p><strong>No auto-save.</strong> An earlier iteration debounced-saved
 * on typing pause + blur, but that filtered out empty pairs on every
 * save and then reset local state from the server response — so adding
 * a new empty pair and pasting into it 2s later made the pair vanish
 * mid-paste. Now the only save path is the explicit "Save Q&A" button
 * OR the parent's imperative {@code saveIfDirty()} call (used by the
 * pending-reviews modal to persist Q&A the instant the trainer clicks
 * "Publish feedback").</p>
 */
export interface ReferenceQaPair {
  question: string;
  answer: string;
  order?: number | null;
}

export interface ReferenceQaEditorHandle {
  /**
   * If there are unsaved changes AND every pair is complete
   * (question + answer both non-empty), PUT them. Resolves to true
   * when a save fired successfully, false when nothing to save or the
   * caller should surface the returned reason. Never throws — errors
   * land in the editor's own {@code saveErr} panel so the parent can
   * proceed with its own submit flow regardless.
   */
  saveIfDirty: () => Promise<boolean>;
}

interface Props {
  projectId: string;
}

const ReferenceQaEditor = forwardRef<ReferenceQaEditorHandle, Props>(function ReferenceQaEditor(
  { projectId },
  ref,
) {
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

  // Ref-tracked mutable copies so imperative saveIfDirty() reads the
  // latest values without depending on stale closures.
  const pairsRef = useRef(pairs);
  useEffect(() => { pairsRef.current = pairs; }, [pairs]);
  const dirtyRef = useRef(dirty);
  useEffect(() => { dirtyRef.current = dirty; }, [dirty]);
  const savingRef = useRef(false);
  useEffect(() => { savingRef.current = saving; }, [saving]);

  /**
   * Core save. Does NOT reset local state from the server response —
   * that was the old bug: newly-added empty pairs vanished when the
   * server (correctly) dropped them from the persisted set. Now we
   * only clear the dirty flag + stamp savedAt; the on-screen pairs
   * keep their identity so pasting into a fresh row works.
   */
  const doSave = useCallback(async (opts?: { silent?: boolean }) => {
    if (savingRef.current) return false;
    const currentPairs = pairsRef.current;
    const clean = currentPairs
      .map((p) => ({ question: p.question.trim(), answer: p.answer.trim() }))
      .filter((p) => p.question || p.answer);
    const halfFilled = clean.find((p) => !p.question || !p.answer);
    if (halfFilled) {
      if (!opts?.silent) {
        setSaveErr('Every pair needs both a question and an answer before saving.');
      }
      return false;
    }
    setSaving(true);
    setSaveErr(null);
    try {
      await api.put<{ pairs: ReferenceQaPair[] }>(
        `/api/v1/trainer/projects/${projectId}/reference-qa`,
        { pairs: clean },
      );
      setSavedAt(new Date());
      setDirty(false);
      return true;
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setSaveErr(ax.response?.data?.error ?? ax.message ?? 'Failed to save');
      return false;
    } finally {
      setSaving(false);
    }
  }, [projectId]);

  // Manual "Save Q&A" button — surfaces the half-filled error loudly.
  const save = useCallback(() => { void doSave({ silent: false }); }, [doSave]);

  // Parent-callable: fire silently right before publishing feedback so
  // nothing typed is lost when the trainer clicks the outer submit.
  useImperativeHandle(ref, () => ({
    saveIfDirty: async () => {
      if (!dirtyRef.current) return false;
      return doSave({ silent: true });
    },
  }), [doSave]);

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
          {dirty && !saving && (
            <span className="text-[11px] font-medium text-amber-700">Unsaved changes</span>
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
        evaluator can reference during the evaluation. Publishing
        feedback auto-saves any pending pairs.
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
});

export default ReferenceQaEditor;
