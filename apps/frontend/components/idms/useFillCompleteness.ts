'use client';

import { useMemo } from 'react';
import type { FieldSchemaEntry, InstanceDetail } from '@/lib/careers/idms';

/**
 * IDMS shared fill-page completeness selector.
 *
 * <p>Derived LIVE from current form state on every render — no snapshot,
 * no server-refetch dependency. Both fill pages (ERM + intern) drive
 * their Send/Submit enablement, the "N of M" completion counter, and
 * the blocking-reason banner from this one selector so the two surfaces
 * can never disagree on what "complete" means and the F1 exemplar bug
 * (Send stuck disabled because a derived-from-snapshot flag went
 * stale) has one place to be re-audited.</p>
 *
 * <h3>Multi-anchor</h3>
 * The schema drives the field list; each field id is counted once
 * regardless of how many anchor spans it wraps in the document.
 *
 * <h3>Optimistic signature tracking</h3>
 * The parent may pass {@code optimisticallySignedFieldIds} — a Set of
 * field ids the client just signed but whose /sign response hasn't yet
 * updated {@code detail.values[id].signatureUrl}. Without this the
 * signature row flashes "incomplete" for ~200 ms after the drawer's
 * Save signature button (F15). With it, the moment the drawer closes
 * the field counts as done.
 */
export interface FillCompletenessInput {
  detail: InstanceDetail | null;
  fields: FieldSchemaEntry[];
  role: 'ERM' | 'INTERN';
  /** Live text-input state keyed by fieldId. */
  textValues: Record<string, string>;
  /** Field ids the client optimistically knows are signed. */
  optimisticallySignedFieldIds?: ReadonlySet<string>;
}

export interface FillCompleteness {
  /** Every field the caller owns. */
  ownFields: FieldSchemaEntry[];
  /** Those the caller has completed (any level). */
  ownDone: number;
  ownTotal: number;
  /** Required-only counts — drives the "unlock Send" gate. */
  requiredMissing: FieldSchemaEntry[];
  requiredMissingNames: string[];
  requiredDone: number;
  requiredTotal: number;
  /** True iff every required field the caller owns is filled — the
   *  authoritative Send/Submit enablement bit. */
  canTransition: boolean;
  /** One-liner for the disabled tooltip / banner copy. */
  blockingReason: string | null;
}

export function useFillCompleteness(input: FillCompletenessInput): FillCompleteness {
  const { detail, fields, role, textValues, optimisticallySignedFieldIds } = input;
  return useMemo(() => {
    // Field-level correction lock — on a narrowed correction cycle
    // the intern only owns the unlocked ids; everything else is
    // read-only and already-filled from the prior submission. Locked
    // fields must NOT count toward the current submit gate (they're
    // not editable, so requiring them completes would be a stuck
    // Send button). Legacy null set → all intern-assigneed fields
    // own as today.
    const lockNarrowing = role === 'INTERN' && detail && detail.unlockedFieldIds
      ? new Set(detail.unlockedFieldIds)
      : null;
    const ownFields = fields.filter((f) =>
      f.assignee === role
      && (lockNarrowing == null || lockNarrowing.has(f.id)));
    const optimistic = optimisticallySignedFieldIds ?? new Set<string>();
    const isFilled = (f: FieldSchemaEntry): boolean => {
      if (!detail) return false;
      if (f.type === 'signature') {
        return Boolean(detail.values[f.id]?.signatureUrl) || optimistic.has(f.id);
      }
      const live = (textValues[f.id] ?? '').trim();
      if (live.length > 0) return true;
      const persisted = detail.values[f.id]?.valueText;
      return Boolean(persisted && persisted.trim().length > 0);
    };
    const ownDone = ownFields.filter(isFilled).length;
    const required = ownFields.filter((f) => f.required);
    const requiredMissing = required.filter((f) => !isFilled(f));
    const requiredMissingNames = requiredMissing.map((f) => f.name);
    const requiredDone = required.length - requiredMissing.length;
    const canTransition = requiredMissing.length === 0;
    const blockingReason = canTransition
      ? null
      : (requiredMissing.length === 1
          ? `Complete "${requiredMissing[0].name}" before ${role === 'ERM' ? 'sending' : 'submitting'}.`
          : `Complete ${requiredMissingNames.join(', ')} before ${role === 'ERM' ? 'sending' : 'submitting'}.`);
    return {
      ownFields,
      ownDone,
      ownTotal: ownFields.length,
      requiredMissing,
      requiredMissingNames,
      requiredDone,
      requiredTotal: required.length,
      canTransition,
      blockingReason,
    };
  }, [detail, fields, role, textValues, optimisticallySignedFieldIds]);
}
