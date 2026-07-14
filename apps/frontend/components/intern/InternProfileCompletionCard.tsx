'use client';

import Link from 'next/link';
import { CheckCircle2, Circle, UserCog } from 'lucide-react';
import type { InternApplyReadiness } from './InternDashboardContext';

const FIELD_LABEL: Record<string, string> = {
  phone: 'Phone number',
  school: 'School',
  degree: 'Degree',
  graduationYear: 'Graduation year',
  skillset: 'Skills',
  resume: 'Resume',
};

const ALL_KEYS = ['phone', 'school', 'degree', 'graduationYear', 'skillset', 'resume'];

interface Props {
  readiness: InternApplyReadiness;
}

/**
 * Approach 1 — dashboard panel that surfaces the intern's profile-completion
 * progress and routes them to the editor when fields are missing. Shows the
 * same `missing[]` keys the apply endpoint's 409 PROFILE_INCOMPLETE returns,
 * so the dashboard and the locked-Apply tooltip tell the same story.
 *
 * Caller renders this only when {@code readiness.complete === false}.
 */
export default function InternProfileCompletionCard({ readiness }: Props) {
  const missing = new Set(readiness.missing);
  const pct = Math.max(0, Math.min(100, readiness.percent));
  // The first missing key drives the deep-link focus param so the editor
  // opens at the right step.
  const focusKey = readiness.missing[0];
  const editorHref = focusKey
    ? `/careers/intern/profile/complete?focus=${encodeURIComponent(focusKey)}`
    : '/careers/intern/profile/complete';

  return (
    <section className="mb-6 rounded-lg border border-brand-200 bg-brand-50/60 p-6 shadow-sm">
      <div className="flex items-start justify-between gap-4">
        <div className="flex-1">
          <div className="mb-1 inline-flex items-center gap-1.5 rounded-full bg-brand-100 px-2.5 py-0.5 text-[11px] font-semibold uppercase tracking-wide text-brand-800">
            <UserCog className="h-3 w-3" strokeWidth={2.5} />
            Complete your profile
          </div>
          <h2 className="text-lg font-semibold text-slate-900">
            A few quick details and you can start applying
          </h2>
          <p className="mt-1 text-sm text-slate-600">
            Your profile is {pct}% complete. Apply unlocks once these are filled in.
          </p>
        </div>
        <Link
          href={editorHref}
          className="inline-flex shrink-0 items-center rounded-md bg-brand-700 px-3 py-1.5 text-sm font-semibold text-white transition-colors hover:bg-brand-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2"
        >
          Complete profile
        </Link>
      </div>

      <div className="mt-4 h-2 w-full overflow-hidden rounded-full bg-brand-100">
        <div
          className="h-full bg-brand-600 transition-all duration-300 ease-out"
          style={{ width: `${pct}%` }}
          aria-label={`Profile ${pct} percent complete`}
        />
      </div>

      <ul className="mt-4 grid gap-1.5 sm:grid-cols-2">
        {ALL_KEYS.map((key) => {
          const done = !missing.has(key);
          return (
            <li
              key={key}
              className="flex items-center gap-2 text-sm"
            >
              {done ? (
                <CheckCircle2
                  className="h-4 w-4 shrink-0 text-green-600"
                  strokeWidth={2.5}
                />
              ) : (
                <Circle
                  className="h-4 w-4 shrink-0 text-slate-400"
                  strokeWidth={2}
                />
              )}
              <span className={done ? 'text-slate-700 line-through decoration-slate-300' : 'text-slate-800'}>
                {FIELD_LABEL[key] ?? key}
              </span>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
