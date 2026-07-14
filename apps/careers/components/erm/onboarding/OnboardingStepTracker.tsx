'use client';

/**
 * 6-step gated onboarding tracker for the ERM intern detail page.
 *
 * Design provenance: shaped by the ui-ux-pro-max skill's guidance —
 * stepper indicators (UX Feedback §Progress Indicators), explicit
 * disabled state for LOCKED (opacity-50 + cursor-not-allowed +
 * aria-disabled), animated-pulse skeleton for loading >300ms, focus
 * rings preserved, ≥44px touch targets, no emoji icons (lucide-react
 * throughout). Palette is the existing Skyzen brand-700/accent-orange
 * theme — only the structural patterns came from the skill.
 *
 * The component is server-state-driven: it never decides which step is
 * CURRENT or whether canActivate is true — those come from the
 * /onboarding-tracker endpoint so the gating rules can't drift between
 * client and server.
 */

import { useCallback, useEffect, useState } from 'react';
import {
  AlertCircle,
  ArrowUpRight,
  Check,
  Clock,
  ExternalLink,
  FilePlus2,
  Lock,
  Mail,
  PartyPopper,
  Send,
  Sparkles,
  Users,
  Zap,
} from 'lucide-react';
import api from '@/lib/api';
import type {
  OnboardingStep,
  OnboardingStepStatus,
  OnboardingTracker,
} from './tracker-types';

interface Props {
  lifecycleId: string;
  /** Fires when an action (notify, activate-now, reminder, mail/joining
   *  save) is completed so the parent page can refresh the underlying
   *  NewHireDetail (the legacy modals already do this). The tracker
   *  itself re-fetches on every mount + on any internal action. */
  onChanged?: () => void;
  /** Bump from the parent (counter, timestamp, etc.) to force the
   *  tracker to re-fetch its payload. Needed when a sibling modal
   *  (Assign Packet, Set Joining Date, Assign Company Email) changes
   *  the underlying tracker state — those modals close on the parent,
   *  not the tracker, so the tracker has no other way to know it
   *  needs to refresh. */
  refreshKey?: number;
  /** Parent supplies the existing modal launchers so this component
   *  doesn't have to re-implement the assign-packet dialog, company-
   *  email dialog, joining date modal, etc. — they already exist on
   *  the detail page. */
  onOpenAssignPacketModal: () => void;
  onOpenCompanyEmailModal: () => void;
  onOpenJoiningDateModal: () => void;
  /** True iff the company email step is reachable today
   *  (mailHandoverState !== null && employeeId set). Mirrors the
   *  existing CompanyEmailSection gating so the tracker doesn't push
   *  the modal open prematurely. */
  companyEmailReady: boolean;
  /** Mirrors NewHireDetail.mailHandoverState — used to label the
   *  sub-task accurately ("activated" vs "pending activation"). */
  mailHandoverState: 'PERSONAL' | 'PENDING_ACTIVATION' | 'ACTIVATED' | null;
}

export default function OnboardingStepTracker({
  lifecycleId,
  onChanged,
  refreshKey,
  onOpenAssignPacketModal,
  onOpenCompanyEmailModal,
  onOpenJoiningDateModal,
  companyEmailReady,
  mailHandoverState,
}: Props) {
  const [tracker, setTracker] = useState<OnboardingTracker | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [notifyOpen, setNotifyOpen] = useState(false);
  const [activatingNow, setActivatingNow] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const res = await api.get<OnboardingTracker>(
        `/api/v1/erm/new-hire/${lifecycleId}/onboarding-tracker`,
      );
      setTracker(res.data);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load tracker');
    } finally {
      setLoading(false);
    }
  }, [lifecycleId]);

  // Re-fetch on mount, on lifecycle change, AND whenever the parent
  // bumps refreshKey (e.g. AssignPacket / SetJoiningDate / AssignMail
  // modal completes). Without the refreshKey dep, a sibling-modal save
  // wouldn't flip the relevant step to DONE until the page is reloaded.
  useEffect(() => { void load(); }, [load, refreshKey]);

  async function activateNow() {
    if (!confirm(
      'Activate this intern now? This bypasses the auto-activation schedule '
      + 'and flips them to ACTIVE_INTERN immediately.',
    )) return;
    setActivatingNow(true);
    try {
      await api.post(`/api/v1/intern-lifecycles/${lifecycleId}/activate`);
      await load();
      onChanged?.();
    } finally {
      setActivatingNow(false);
    }
  }

  async function confirmNotifyTeam() {
    try {
      const res = await api.post<OnboardingTracker>(
        `/api/v1/erm/new-hire/${lifecycleId}/notify-team`,
      );
      setTracker(res.data);
      setNotifyOpen(false);
      onChanged?.();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      alert(ax.response?.data?.error ?? ax.message ?? 'Notify failed');
    }
  }

  if (loading && !tracker) {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <div className="h-6 w-48 animate-pulse rounded bg-slate-100" />
        <div className="mt-4 h-16 animate-pulse rounded bg-slate-100" />
        <div className="mt-3 h-24 animate-pulse rounded bg-slate-100" />
      </div>
    );
  }
  if (err || !tracker) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-800">
        <AlertCircle className="mr-2 inline h-4 w-4" />
        {err ?? 'Tracker unavailable'}
      </div>
    );
  }

  const current = tracker.steps.find((s) => s.id === tracker.currentStepId) ?? null;
  const allDone = tracker.stepsCompleted === tracker.stepsTotal;

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <div>
          <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">
            Onboarding tracker
          </h2>
          <p className="mt-0.5 text-base font-semibold text-slate-900">
            {allDone
              ? 'All steps complete — intern is active.'
              : `${tracker.stepsCompleted} of ${tracker.stepsTotal} steps complete`}
          </p>
        </div>
        <div className="text-[11px] text-slate-500">
          Hard-gated: activation unlocks only after steps 1-5 finish.
        </div>
      </header>

      {/* Stepper — horizontal node row */}
      <ol className="mt-4 grid grid-cols-5 gap-2">
        {tracker.steps.map((s, i) => (
          <StepNode
            key={s.id}
            step={s}
            index={i + 1}
            isCurrent={tracker.currentStepId === s.id}
          />
        ))}
      </ol>

      {/* Next action banner */}
      <div className="mt-5">
        {allDone ? (
          <div className="flex items-center gap-3 rounded-md border border-green-200 bg-green-50 px-4 py-3 text-sm text-green-900">
            <PartyPopper className="h-5 w-5 shrink-0" />
            <div>
              <p className="font-semibold">All onboarding steps complete.</p>
              <p className="text-[12px] text-green-800">
                The intern is active and visible on every team dashboard.
              </p>
            </div>
          </div>
        ) : current ? (
          <NextActionBanner
            step={current}
            stepsRemaining={tracker.stepsRemaining}
            canActivate={tracker.canActivate}
            activatingNow={activatingNow}
            mailHandoverState={mailHandoverState}
            companyEmailReady={companyEmailReady}
            onActivateNow={activateNow}
            onOpenNotifyTeam={() => setNotifyOpen(true)}
            onOpenAssignPacketModal={onOpenAssignPacketModal}
            onOpenCompanyEmailModal={onOpenCompanyEmailModal}
            onOpenJoiningDateModal={onOpenJoiningDateModal}
          />
        ) : null}
      </div>

      {notifyOpen && (
        <NotifyTeamModal
          onCancel={() => setNotifyOpen(false)}
          onConfirm={confirmNotifyTeam}
        />
      )}
    </section>
  );
}

// ── Step node ────────────────────────────────────────────────────────────

function StepNode({
  step, index, isCurrent,
}: { step: OnboardingStep; index: number; isCurrent: boolean }) {
  const cfg = nodeConfig(step.status, isCurrent);
  return (
    <li
      className={
        'group relative flex flex-col items-center text-center'
        + (isCurrent ? ' [&_.node-circle]:ring-4 [&_.node-circle]:ring-brand-100' : '')
      }
      aria-current={isCurrent ? 'step' : undefined}
    >
      <div
        className={
          'node-circle inline-flex h-9 w-9 items-center justify-center rounded-full '
          + 'border-2 transition-colors duration-200 '
          + cfg.circle
        }
        title={step.label}
      >
        {cfg.icon ? <cfg.icon className="h-4 w-4" /> : (
          <span className="text-xs font-semibold">{index}</span>
        )}
      </div>
      <p
        className={
          'mt-1.5 line-clamp-2 px-1 text-[11px] font-medium leading-tight '
          + cfg.label
        }
      >
        {shortLabel(step.label)}
      </p>
    </li>
  );
}

function nodeConfig(status: OnboardingStepStatus, isCurrent: boolean) {
  switch (status) {
    case 'DONE':
      return {
        circle: 'border-green-300 bg-green-100 text-green-700',
        label: 'text-slate-700',
        icon: Check,
      };
    case 'CURRENT':
      return {
        circle: 'border-brand-700 bg-white text-brand-800',
        label: 'text-slate-900 font-semibold',
        icon: Sparkles,
      };
    case 'WAITING_INTERN':
      return {
        circle: 'border-amber-300 bg-amber-50 text-amber-700',
        label: 'text-amber-900',
        icon: Clock,
      };
    case 'LOCKED':
      return {
        circle: 'border-slate-200 bg-slate-100 text-slate-400',
        label: 'text-slate-400',
        icon: Lock,
      };
    case 'PENDING':
    default:
      return {
        circle: isCurrent
          ? 'border-brand-700 bg-white text-brand-800'
          : 'border-slate-300 bg-white text-slate-500',
        label: isCurrent ? 'text-slate-900 font-semibold' : 'text-slate-500',
        icon: null,
      };
  }
}

function shortLabel(label: string): string {
  // Tighten longer labels for the small step-node footprint.
  return label
    .replace('Assign documents', 'Assign docs')
    .replace('Verify documents', 'Verify docs')
    .replace('Notify trainer + manager', 'Notify team')
    .replace('Mail ID + joining date', 'Mail + date')
    .replace('Activate intern', 'Activate');
}

// ── Next action banner ──────────────────────────────────────────────────

function NextActionBanner({
  step, stepsRemaining, canActivate, activatingNow,
  mailHandoverState, companyEmailReady,
  onActivateNow, onOpenNotifyTeam,
  onOpenAssignPacketModal,
  onOpenCompanyEmailModal, onOpenJoiningDateModal,
}: {
  step: OnboardingStep;
  stepsRemaining: number;
  canActivate: boolean;
  activatingNow: boolean;
  mailHandoverState: 'PERSONAL' | 'PENDING_ACTIVATION' | 'ACTIVATED' | null;
  companyEmailReady: boolean;
  onActivateNow: () => void;
  onOpenNotifyTeam: () => void;
  onOpenAssignPacketModal: () => void;
  onOpenCompanyEmailModal: () => void;
  onOpenJoiningDateModal: () => void;
}) {
  return (
    <div className="rounded-md border border-brand-200 bg-brand-50 p-4">
      <div className="flex items-start gap-3">
        <div className="rounded-full bg-white p-2 shadow-sm ring-1 ring-brand-200">
          <Sparkles className="h-4 w-4 text-brand-700" />
        </div>
        <div className="flex-1">
          <p className="text-[11px] font-semibold uppercase tracking-wide text-brand-700">
            Next action
          </p>
          <p className="mt-0.5 text-sm font-semibold text-slate-900">
            {step.label}
          </p>
          {step.helpText && (
            <p className="mt-1 text-[12px] leading-snug text-slate-700">
              {step.helpText}
            </p>
          )}
          {step.subTasks.length > 0 && (
            <ul className="mt-2 space-y-1">
              {step.subTasks.map((t) => (
                <li
                  key={t.label}
                  className={
                    'flex items-center gap-2 text-[12px] '
                    + (t.done ? 'text-green-800' : 'text-slate-600')
                  }
                >
                  <span
                    className={
                      'inline-flex h-4 w-4 items-center justify-center rounded-full '
                      + (t.done
                        ? 'bg-green-200 text-green-800'
                        : 'border border-slate-300 bg-white')
                    }
                  >
                    {t.done ? <Check className="h-2.5 w-2.5" /> : null}
                  </span>
                  {t.label}
                  {!t.done && mailHandoverState === 'PENDING_ACTIVATION'
                    && t.label.startsWith('Company mail') && (
                      <span className="ml-1 rounded-full bg-amber-100 px-1.5 py-0.5 text-[10px] font-semibold text-amber-800">
                        awaiting intern activation
                      </span>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
        <div className="flex shrink-0 flex-col items-end gap-1.5">
          <ActionAffordance
            step={step}
            stepsRemaining={stepsRemaining}
            canActivate={canActivate}
            activatingNow={activatingNow}
            mailHandoverState={mailHandoverState}
            companyEmailReady={companyEmailReady}
            onActivateNow={onActivateNow}
            onOpenNotifyTeam={onOpenNotifyTeam}
            onOpenAssignPacketModal={onOpenAssignPacketModal}
            onOpenCompanyEmailModal={onOpenCompanyEmailModal}
            onOpenJoiningDateModal={onOpenJoiningDateModal}
          />
        </div>
      </div>
    </div>
  );
}

function ActionAffordance({
  step, stepsRemaining, canActivate, activatingNow,
  mailHandoverState, companyEmailReady,
  onActivateNow, onOpenNotifyTeam,
  onOpenAssignPacketModal,
  onOpenCompanyEmailModal, onOpenJoiningDateModal,
}: {
  step: OnboardingStep;
  stepsRemaining: number;
  canActivate: boolean;
  activatingNow: boolean;
  mailHandoverState: 'PERSONAL' | 'PENDING_ACTIVATION' | 'ACTIVATED' | null;
  companyEmailReady: boolean;
  onActivateNow: () => void;
  onOpenNotifyTeam: () => void;
  onOpenAssignPacketModal: () => void;
  onOpenCompanyEmailModal: () => void;
  onOpenJoiningDateModal: () => void;
}) {
  // GATED — Activate locked until prior steps done.
  if (step.actionType === 'GATED') {
    return (
      <button
        type="button"
        disabled
        aria-disabled
        className="inline-flex cursor-not-allowed items-center gap-1.5 rounded-md bg-slate-200 px-4 py-2.5 text-sm font-semibold text-slate-500 opacity-70"
        title={`Locked — ${stepsRemaining} step${stepsRemaining === 1 ? '' : 's'} left`}
      >
        <Lock className="h-4 w-4" />
        Locked — {stepsRemaining} left
      </button>
    );
  }

  // WAIT_REMINDER — offer signature or doc-submission waits.
  if (step.actionType === 'WAIT_REMINDER') {
    // Currently the only WAIT_REMINDER case is DOCS_VERIFIED while the
    // intern is still filling the packet. ERM jumps to the review screen
    // to nudge / inspect; the review screen owns the reminder UX itself.
    return (
      <>
        <span className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2.5 py-0.5 text-[11px] font-semibold text-amber-800 ring-1 ring-amber-200">
          <Clock className="h-3 w-3" /> Waiting on intern
        </span>
        {step.redirectHref && (
          <a
            href={step.redirectHref}
            className="inline-flex items-center gap-1.5 rounded-md border border-brand-300 bg-white px-3 py-2 text-xs font-semibold text-brand-800 hover:bg-brand-50"
          >
            <ExternalLink className="h-3.5 w-3.5" />
            Open review screen
          </a>
        )}
      </>
    );
  }

  // REDIRECT — go to existing screen (doc-review).
  if (step.actionType === 'REDIRECT' && step.redirectHref) {
    return (
      <a
        href={step.redirectHref}
        className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-brand-800"
      >
        <ArrowUpRight className="h-4 w-4" />
        Open review
      </a>
    );
  }

  // MODAL — per-step launcher.
  if (step.actionType === 'MODAL') {
    if (step.id === 'DOCS_ASSIGNED') {
      return (
        <button
          type="button"
          onClick={onOpenAssignPacketModal}
          className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-brand-800"
        >
          <FilePlus2 className="h-4 w-4" /> Assign documents
        </button>
      );
    }
    if (step.id === 'TEAM_NOTIFIED') {
      return (
        <button
          type="button"
          onClick={onOpenNotifyTeam}
          className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-brand-800"
        >
          <Users className="h-4 w-4" /> Notify team
        </button>
      );
    }
    if (step.id === 'MAIL_AND_JOINING') {
      const mailDone = mailHandoverState === 'ACTIVATED';
      const mailPending = mailHandoverState === 'PENDING_ACTIVATION';
      return (
        <>
          {!mailDone && (
            <button
              type="button"
              onClick={onOpenCompanyEmailModal}
              disabled={!companyEmailReady}
              title={companyEmailReady ? 'Assign company mailbox'
                : 'Available once the intern has an employee id'}
              className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 disabled:opacity-60"
            >
              <Mail className="h-4 w-4" />
              {mailPending ? 'Mail awaiting…' : 'Assign mail ID'}
            </button>
          )}
          <button
            type="button"
            onClick={onOpenJoiningDateModal}
            className="inline-flex items-center gap-1.5 rounded-md border border-brand-300 bg-white px-3 py-2 text-xs font-semibold text-brand-800 hover:bg-brand-50"
          >
            <Send className="h-3.5 w-3.5" />
            Set joining date
          </button>
        </>
      );
    }
    if (step.id === 'ACTIVATE') {
      return (
        <button
          type="button"
          onClick={onActivateNow}
          disabled={!canActivate || activatingNow}
          className="inline-flex items-center gap-1.5 rounded-md bg-gradient-to-br from-accent to-accent-dark px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:from-accent-dark hover:to-accent-dark disabled:opacity-60"
        >
          <Zap className="h-4 w-4" />
          {activatingNow ? 'Activating…' : 'Activate now'}
        </button>
      );
    }
    return null;
  }

  return null;
}

// ── Notify team modal ───────────────────────────────────────────────────

function NotifyTeamModal({
  onCancel, onConfirm,
}: { onCancel: () => void; onConfirm: () => void }) {
  const [submitting, setSubmitting] = useState(false);
  async function go() {
    setSubmitting(true);
    try { await onConfirm(); } finally { setSubmitting(false); }
  }
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="w-full max-w-md rounded-lg bg-white shadow-xl">
        <div className="border-b border-slate-200 px-5 py-3">
          <h3 className="text-base font-semibold text-slate-900">
            Notify trainer + manager
          </h3>
          <p className="mt-1 text-xs text-slate-600">
            Sends a one-time announcement that a new intern has joined.
            Reuses the existing notification + email system to nudge the
            single trainer and single manager linked to this intern.
            Doesn&rsquo;t assign or pick anyone — those are already set.
          </p>
        </div>
        <div className="px-5 py-4">
          <ul className="space-y-1.5 text-sm text-slate-700">
            <li className="flex items-center gap-2">
              <Users className="h-4 w-4 text-slate-500" />
              In-app notification → both recipients
            </li>
            <li className="flex items-center gap-2">
              <Mail className="h-4 w-4 text-slate-500" />
              Branded email → both recipients
            </li>
          </ul>
        </div>
        <div className="flex justify-end gap-2 border-t border-slate-200 px-5 py-3">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm text-slate-700"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={go}
            disabled={submitting}
            className="rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            {submitting ? 'Sending…' : 'Send notifications'}
          </button>
        </div>
      </div>
    </div>
  );
}
