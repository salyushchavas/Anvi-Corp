import { CheckCircle2, Clock, FileText, RotateCcw } from 'lucide-react';
import { formatDateOnly, formatFull } from '@/lib/format-date';
import type { I9FormResponse } from '@/types';

interface Props {
  form: I9FormResponse;
}

const BASE =
  '-mx-4 md:-mx-8 -mt-4 md:-mt-8 mb-8 px-4 md:px-8 py-4 border-b flex items-center gap-3 flex-wrap';

export default function I9StatusBanner({ form }: Props) {
  if (form.status === 'NOT_STARTED') {
    const due = form.section1DueDate;
    const section1Overdue = form.section1Overdue;
    return (
      <div
        className={
          BASE +
          (section1Overdue
            ? ' bg-red-50 border-red-200 text-red-900'
            : ' bg-slate-100 border-slate-300 text-slate-700')
        }
      >
        <FileText className="h-6 w-6 flex-shrink-0" strokeWidth={2} />
        <div>
          <div className="text-base font-medium">
            Let&apos;s complete your I-9 — Section 1
          </div>
          <div className="text-sm opacity-80">
            Federal employment eligibility verification.{' '}
            {due
              ? `Required by your first day of work — ${formatDateOnly(due)}.`
              : 'Required by your first day of work.'}
          </div>
          {section1Overdue && (
            <div className="mt-1 text-sm font-medium">
              Section 1 is past due — please complete now.
            </div>
          )}
        </div>
      </div>
    );
  }

  if (form.status === 'REOPENED') {
    return (
      <div className={BASE + ' bg-slate-100 border-slate-300 text-slate-700'}>
        <RotateCcw className="h-6 w-6 flex-shrink-0" strokeWidth={2} />
        <div>
          <div className="text-base font-medium">
            This form has been reopened for correction
          </div>
          <div className="text-sm opacity-80">
            Please review and resubmit with the corrections requested.
          </div>
        </div>
      </div>
    );
  }

  if (form.status === 'SECTION_2_PENDING' || form.status === 'SECTION_1_COMPLETE') {
    const days = form.daysUntilDue;
    const overdue = form.section2Overdue ?? form.overdue;
    return (
      <div className={BASE + ' bg-amber-50 border-amber-200 text-amber-900'}>
        <Clock className="h-6 w-6 flex-shrink-0" strokeWidth={2} />
        <div>
          <div className="text-base font-medium">
            {form.section2DueDate
              ? `Section 1 complete. HR will complete Section 2 by ${formatDateOnly(form.section2DueDate)}`
              : 'Section 1 complete. HR will complete Section 2 once your start date is set.'}
          </div>
          <div
            className={
              'text-sm ' +
              (overdue
                ? 'font-medium text-red-700'
                : 'opacity-80')
            }
          >
            {days != null
              ? overdue
                ? `${Math.abs(days)} day(s) overdue for Section 2`
                : `${days} day(s) remaining for Section 2`
              : 'Section 2 deadline pending'}
          </div>
        </div>
      </div>
    );
  }

  // COMPLETED
  return (
    <div className={BASE + ' bg-green-50 border-green-200 text-green-900'}>
      <CheckCircle2 className="h-6 w-6 flex-shrink-0" strokeWidth={2} />
      <div>
        <div className="text-base font-medium">
          Your I-9 is complete and on file
        </div>
        <div className="text-sm opacity-80">
          {form.section2SignedAt
            ? `Verified on ${formatFull(form.section2SignedAt)}`
            : 'Verified by HR'}
        </div>
      </div>
    </div>
  );
}
