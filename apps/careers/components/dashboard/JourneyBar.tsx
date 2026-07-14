'use client';

import { Check, CircleDot, Clock, Lock, AlertTriangle, type LucideIcon } from 'lucide-react';
import type {
  CandidateJourney,
  CandidateJourneyStage,
  CandidateSubStep,
  StageState,
  SubStepOwner,
  SubStepState,
} from '@/types';

/**
 * SPEC §3 — the journey-bar component. Reusable across the applicant face
 * and (later) the intern face: pass in any `CandidateJourney` payload and
 * the component renders the macro-step strip + the expanded sub-checklist
 * for the current stage.
 *
 * Two altitudes, progressive disclosure:
 *   - Top: 6 (or N) macro dots, always visible. Only the current dot is
 *     visually accented; past dots show a checkmark; future dots are muted.
 *   - Below: the current stage's sub-checklist with per-row state (done /
 *     current / waiting / upcoming / blocked) and an "owner" pill that names
 *     who's actually responsible for the row (you / recruiter / employer /
 *     supervisor / DSO / system).
 *
 * Pure presentation — no data fetching. The page picks the journey out of
 * the dashboard response and hands it down.
 */
export default function JourneyBar({ journey }: { journey: CandidateJourney }) {
  if (!journey?.stages?.length) return null;
  const current = journey.stages.find((s) => s.state === 'current')
    ?? journey.stages.find((s) => s.key === journey.currentStageKey);

  return (
    <section
      className="rounded-xl border border-gray-200 bg-white p-5"
      aria-label="Journey progress"
    >
      <StageStrip stages={journey.stages} isExited={journey.isExited} />
      {current && current.subSteps && current.subSteps.length > 0 && (
        <div className="mt-5 border-t border-gray-100 pt-4">
          <p className="mb-3 text-xs font-medium uppercase tracking-wide text-gray-500">
            {current.label} — what&apos;s next
          </p>
          <ul className="space-y-2">
            {current.subSteps.map((s) => (
              <SubStepRow key={s.key} step={s} />
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}

function StageStrip({
  stages,
  isExited,
}: {
  stages: CandidateJourneyStage[];
  isExited: boolean;
}) {
  return (
    <ol className="flex flex-wrap items-center gap-x-2 gap-y-3">
      {stages.map((stage, idx) => (
        <li key={stage.key} className="flex flex-1 items-center gap-2 min-w-[8rem]">
          <StageDot state={isExited && idx === 0 ? 'blocked' : stage.state} />
          <div className="min-w-0">
            <div
              className={`truncate text-sm font-medium ${stageTextClass(stage.state)}`}
            >
              {stage.label}
            </div>
            {stage.state === 'current' && (
              <div className="text-[10px] uppercase tracking-wide text-accent">
                Current
              </div>
            )}
          </div>
          {idx < stages.length - 1 && (
            <div
              aria-hidden="true"
              className={`mx-1 hidden h-px flex-1 sm:block ${
                stage.state === 'done' ? 'bg-green-300' : 'bg-gray-200'
              }`}
            />
          )}
        </li>
      ))}
    </ol>
  );
}

function StageDot({ state }: { state: StageState }) {
  const v = stageVisuals(state);
  const Icon = v.icon;
  return (
    <span
      className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full ${v.bg} ${v.ring}`}
    >
      <Icon className={`h-4 w-4 ${v.fg}`} strokeWidth={2.5} />
    </span>
  );
}

function SubStepRow({ step }: { step: CandidateSubStep }) {
  const v = subStepVisuals(step.state);
  const Icon = v.icon;
  const content = (
    <div className="flex items-start gap-3">
      <span
        className={`mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full ${v.bg}`}
      >
        <Icon className={`h-3.5 w-3.5 ${v.fg}`} strokeWidth={2.5} />
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className={`text-sm font-medium ${v.text}`}>{step.label}</span>
          {step.owner && <OwnerPill owner={step.owner} />}
          {step.state === 'waiting' && (
            <span className="rounded-full bg-amber-50 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-amber-700">
              Waiting
            </span>
          )}
        </div>
        {step.subtitle && (
          <p className="mt-0.5 text-xs text-gray-500">{step.subtitle}</p>
        )}
      </div>
    </div>
  );
  if (step.href) {
    return (
      <li>
        <a
          href={step.href}
          className="block -mx-2 rounded-md p-2 transition hover:bg-gray-50"
        >
          {content}
        </a>
      </li>
    );
  }
  return <li className="-mx-2 p-2">{content}</li>;
}

function OwnerPill({ owner }: { owner: SubStepOwner }) {
  const label = OWNER_LABEL[owner] ?? owner;
  return (
    <span className="rounded-full bg-gray-100 px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wide text-gray-600">
      {label}
    </span>
  );
}

const OWNER_LABEL: Record<SubStepOwner, string> = {
  you: 'You',
  recruiter: 'Recruiter',
  employer: 'Employer',
  supervisor: 'Supervisor',
  dso: 'DSO',
  system: 'System',
};

function stageVisuals(state: StageState): {
  icon: LucideIcon;
  bg: string;
  fg: string;
  ring: string;
} {
  switch (state) {
    case 'done':
      return {
        icon: Check,
        bg: 'bg-green-100',
        fg: 'text-green-700',
        ring: 'ring-1 ring-green-200',
      };
    case 'current':
      return {
        icon: CircleDot,
        bg: 'bg-accent/15',
        fg: 'text-accent',
        ring: 'ring-2 ring-accent',
      };
    case 'blocked':
      return {
        icon: AlertTriangle,
        bg: 'bg-red-100',
        fg: 'text-red-700',
        ring: 'ring-1 ring-red-200',
      };
    case 'upcoming':
    default:
      return {
        icon: Lock,
        bg: 'bg-gray-100',
        fg: 'text-gray-400',
        ring: 'ring-1 ring-gray-200',
      };
  }
}

function stageTextClass(state: StageState): string {
  switch (state) {
    case 'done':
      return 'text-gray-900';
    case 'current':
      return 'text-gray-900';
    case 'blocked':
      return 'text-red-700';
    case 'upcoming':
    default:
      return 'text-gray-400';
  }
}

function subStepVisuals(state: SubStepState): {
  icon: LucideIcon;
  bg: string;
  fg: string;
  text: string;
} {
  switch (state) {
    case 'done':
      return {
        icon: Check,
        bg: 'bg-green-100',
        fg: 'text-green-700',
        text: 'text-gray-700 line-through decoration-emerald-300',
      };
    case 'current':
      return {
        icon: CircleDot,
        bg: 'bg-accent/15',
        fg: 'text-accent',
        text: 'text-gray-900',
      };
    case 'waiting':
      return {
        icon: Clock,
        bg: 'bg-amber-100',
        fg: 'text-amber-700',
        text: 'text-gray-900',
      };
    case 'blocked':
      return {
        icon: AlertTriangle,
        bg: 'bg-red-100',
        fg: 'text-red-700',
        text: 'text-red-700',
      };
    case 'upcoming':
    default:
      return {
        icon: Lock,
        bg: 'bg-gray-100',
        fg: 'text-gray-400',
        text: 'text-gray-500',
      };
  }
}
