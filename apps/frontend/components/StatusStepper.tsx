import { Check } from 'lucide-react';

/**
 * 5-stage application stepper matching the candidate funnel:
 *   0 Applied  1 Shortlisted  2 Interview  3 Offer  4 Hired
 *
 * - currentIndex in 0..4 (anything outside that range = nothing highlighted).
 * - isExited renders a muted/terminated treatment — useful for REJECTED /
 *   WITHDRAWN / LAPSED / NO_SHOW rows where the funnel stopped mid-way.
 * - size 'mini' for cards; 'full' for the My Applications page later.
 *
 * Post-offer override (Phase 3 step 10): when the application has an
 * Engagement, the dashboard passes {@link finalLabel} and {@link finalState}
 * derived from {@code Engagement.status}. The final dot's label + styling
 * become "Onboarding" (current) / "Active" (current) / "Completed" (✓) /
 * "Blocked" (red ring) so the stepper agrees with the banner.
 */
const STAGES = ['Applied', 'Shortlisted', 'Interview', 'Offer', 'Hired'] as const;

export type StepperFinalState = 'current' | 'completed' | 'blocked';

export interface StatusStepperProps {
  currentIndex: number;
  isExited?: boolean;
  size?: 'mini' | 'full';
  className?: string;
  /** Override the final ("Hired") label, typically from Engagement.status. */
  finalLabel?: string | null;
  /** Override the final dot's visual state. Ignored unless the app is at stage 4. */
  finalState?: StepperFinalState | null;
}

export default function StatusStepper({
  currentIndex,
  isExited = false,
  size = 'mini',
  className,
  finalLabel,
  finalState,
}: StatusStepperProps) {
  const dotSize = size === 'full' ? 'h-6 w-6 text-xs' : 'h-4 w-4 text-[10px]';
  const labelSize = size === 'full' ? 'text-xs' : 'text-[10px]';
  const labelGap = size === 'full' ? 'mt-2' : 'mt-1';
  const connectorThickness = size === 'full' ? 'h-0.5' : 'h-px';

  return (
    <ol
      role="list"
      aria-label="Application progress"
      className={(className ?? '') + ' flex w-full items-start'}
    >
      {STAGES.map((rawLabel, i) => {
        const isFinal = i === STAGES.length - 1;
        // Apply the engagement-derived overrides only on the final node, and
        // only when that node is the current stage (post-offer apps).
        const overrideActive =
          isFinal && !isExited && i === currentIndex && finalState != null;
        const label = isFinal && finalLabel ? finalLabel : rawLabel;
        const completed =
          (!isExited && i < currentIndex)
          || (overrideActive && finalState === 'completed');
        const current =
          (!isExited && i === currentIndex && !overrideActive)
          || (overrideActive && finalState === 'current');
        const blocked = overrideActive && finalState === 'blocked';

        // Dot palette
        const dotClasses = [
          'flex shrink-0 items-center justify-center rounded-full border transition-colors',
          dotSize,
          blocked
            ? 'border-red-500 bg-red-500 text-white shadow-[0_0_0_3px_rgba(239,68,68,0.18)]'
            : completed
              ? 'border-accent bg-accent text-white'
              : current
                ? 'border-accent bg-accent text-white shadow-[0_0_0_3px_rgba(251,155,71,0.18)]'
                : isExited
                  ? 'border-gray-200 bg-gray-100 text-gray-300'
                  : 'border-gray-300 bg-white text-gray-400',
        ].join(' ');

        // Connector (between this dot and the next one) — same color logic
        // as "completed" stages, dimmed otherwise.
        const connectorClasses = [
          'mx-1 flex-1 self-center transition-colors',
          connectorThickness,
          completed && !isExited ? 'bg-accent' : 'bg-gray-200',
          isExited ? 'opacity-60' : '',
        ].join(' ');

        return (
          <li
            key={label}
            className="flex flex-1 items-start last:flex-none"
            aria-current={current ? 'step' : undefined}
          >
            <div className="flex min-w-0 flex-col items-center">
              <span className={dotClasses} aria-hidden="true">
                {completed ? (
                  <Check
                    className={size === 'full' ? 'h-3.5 w-3.5' : 'h-2.5 w-2.5'}
                    strokeWidth={3}
                  />
                ) : (
                  <span>{i + 1}</span>
                )}
              </span>
              <span
                className={[
                  labelGap,
                  labelSize,
                  'truncate text-center',
                  blocked
                    ? 'font-semibold text-red-700'
                    : current
                      ? 'font-semibold text-gray-900'
                      : completed
                        ? 'text-gray-700'
                        : isExited
                          ? 'text-gray-400 line-through'
                          : 'text-gray-400',
                ].join(' ')}
              >
                {label}
              </span>
            </div>
            {i < STAGES.length - 1 && (
              <div className={connectorClasses} aria-hidden="true" />
            )}
          </li>
        );
      })}
    </ol>
  );
}
