'use client';

import { useState } from 'react';
import { CheckCircle2, ChevronDown, ChevronUp } from 'lucide-react';
import { formatFull } from '@/lib/format-date';
import Section2ReadOnlyView from './Section2ReadOnlyView';
import type { I9FormResponse } from '@/types';

interface Props {
  form: I9FormResponse;
}

export default function Section2CompletedCard({ form }: Props) {
  const [expanded, setExpanded] = useState(false);
  return (
    <div className="max-w-3xl">
      <div className="rounded-lg border border-green-200 bg-green-50 p-6">
        <div className="flex items-center gap-2">
          <CheckCircle2 className="h-5 w-5 text-green-600" strokeWidth={2} />
          <h3 className="text-base font-semibold text-green-900">
            Your I-9 is complete and on file
          </h3>
        </div>
        <p className="mt-2 text-sm text-green-900/90">
          {form.section2SignedAt
            ? `Section 2 was completed by HR on ${formatFull(form.section2SignedAt)}. `
            : 'Section 2 was completed by HR. '}
          Your employment eligibility has been verified.
        </p>

        <button
          type="button"
          onClick={() => setExpanded((v) => !v)}
          className="mt-3 inline-flex items-center gap-1 text-sm font-medium text-green-800 hover:text-green-900"
          aria-expanded={expanded}
        >
          {expanded ? 'Hide documents recorded' : 'View documents recorded'}
          {expanded ? (
            <ChevronUp className="h-4 w-4" strokeWidth={2} />
          ) : (
            <ChevronDown className="h-4 w-4" strokeWidth={2} />
          )}
        </button>
      </div>

      {expanded && <Section2ReadOnlyView form={form} />}
    </div>
  );
}
