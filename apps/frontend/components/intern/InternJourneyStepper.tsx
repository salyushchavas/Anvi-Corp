'use client';

import StepperHorizontal from '@/components/ui/StepperHorizontal';
import type { InternLifecycleStatus } from './InternDashboardContext';

/**
 * Single source of truth for the intern journey bar — used by every
 * intern page (via {@link InternPageShell}) and by the home page
 * directly. Renders the milestones in brand-orange: completed =
 * filled+checked, current = ringed/highlighted, upcoming = muted slate.
 *
 * <p>Pipeline-restore — the Offer stage is now split into three
 * explicit sub-milestones (Sent → Signed → Executed) so the intern's
 * progress through their offer letter is visible without opening the
 * dedicated offer page. Onboarding is gated behind {@code
 * offer-executed} (enforced by
 * {@code DocumentPacketService}'s offer-family gate).</p>
 */
export const MILESTONES = [
  { key: 'apply', label: 'Apply' },
  { key: 'shortlist', label: 'Shortlist' },
  { key: 'interview', label: 'Interview' },
  { key: 'offer-sent', label: 'Offer Sent' },
  { key: 'offer-signed', label: 'Signed' },
  { key: 'offer-executed', label: 'Executed' },
  { key: 'onboard', label: 'Onboard' },
  { key: 'active', label: 'Active' },
];

export function milestoneIndexFor(s: InternLifecycleStatus): number {
  switch (s) {
    case 'REGISTERED':
    case 'EMAIL_VERIFIED':
      return 0;
    case 'APPLICATION_SUBMITTED':
      return 1;
    case 'SHORTLISTED':
    case 'INTERVIEW_SCHEDULED':
    case 'INTERVIEW_COMPLETED':
      return 2;
    case 'OFFER_SENT':
      return 3;
    case 'OFFER_SIGNED':
      return 4;
    case 'EMPLOYEE_ID_CREATED':
      return 5;
    case 'ONBOARDING_ASSIGNED':
    case 'ONBOARDING_ACCEPTED':
      return 6;
    case 'ACTIVE_INTERN':
      return 7;
    case 'INACTIVE_INTERN':
      // Past every milestone — StepperHorizontal renders all as done.
      return MILESTONES.length;
    default:
      return 0;
  }
}

export function currentMilestoneLabel(s: InternLifecycleStatus): string {
  if (s === 'INACTIVE_INTERN') return 'Internship concluded';
  if (s === 'ACTIVE_INTERN') return 'Active intern';
  const idx = milestoneIndexFor(s);
  return MILESTONES[idx]?.label ?? 'Getting started';
}

interface Props {
  status: InternLifecycleStatus;
  className?: string;
}

/**
 * W3 #21 / #22 — once the intern is ACTIVE_INTERN (or INACTIVE_INTERN
 * post-engagement) the applicant funnel bar (Apply → Shortlist → …
 * → Active) is no longer meaningful. It also visually strands "Active"
 * as the perma-highlighted `isCurrent` step (never advancing to `isPast`
 * because 8 is the last milestone), which is the exact "active is
 * incomplete" symptom the ERM/intern have both hit after activation.
 *
 * <p>Returning null here removes the stepper from every intern surface
 * (both the home page and every shared {@code InternPageShell} page).
 * The home page substitutes an {@code ActiveInternCard} for the active
 * state; other pages simply show no journey bar (correct — the intern
 * is past the funnel).</p>
 */
export default function InternJourneyStepper({ status, className }: Props) {
  if (status === 'ACTIVE_INTERN' || status === 'INACTIVE_INTERN') return null;
  const currentIndex = milestoneIndexFor(status);
  return (
    <section
      aria-label="Your journey"
      className={
        (className ?? 'mb-6')
        + ' overflow-x-auto rounded-lg border border-slate-200 bg-white p-4 shadow-ds-sm'
      }
    >
      <div className="min-w-[36rem]">
        <StepperHorizontal steps={MILESTONES} currentIndex={currentIndex} />
      </div>
    </section>
  );
}
