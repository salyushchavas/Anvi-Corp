import { AlertTriangle, Clock } from 'lucide-react';
import { formatDateOnly } from '@/lib/format-date';
import type { I9FormResponse } from '@/types';

interface Props {
  form: I9FormResponse;
}

export default function Section2WaitingCard({ form }: Props) {
  return (
    <div className="max-w-3xl rounded-lg border border-amber-200 bg-amber-50 p-6">
      <div className="flex items-center gap-2">
        <Clock className="h-5 w-5 text-amber-700" strokeWidth={2} />
        <h3 className="text-base font-semibold text-amber-900">
          Section 2 — In Progress
        </h3>
      </div>
      <p className="mt-2 text-sm text-amber-900/90">
        Your HR team will complete Section 2 by reviewing your identification
        documents. This typically happens on or shortly after your first day of
        work.
      </p>

      <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-3">
        <div>
          <div className="text-xs uppercase tracking-wide text-amber-800/80">
            First day of work
          </div>
          <div className="mt-1 text-sm font-medium text-gray-900">
            {form.firstDayOfEmployment
              ? formatDateOnly(form.firstDayOfEmployment)
              : 'Not yet set'}
          </div>
        </div>
        <div>
          <div className="text-xs uppercase tracking-wide text-amber-800/80">
            Section 1 deadline
          </div>
          <div className="mt-1 text-sm font-medium text-gray-900">
            {form.section1DueDate
              ? formatDateOnly(form.section1DueDate)
              : form.firstDayOfEmployment
                ? formatDateOnly(form.firstDayOfEmployment)
                : 'Will be set with start date'}
          </div>
        </div>
        <div>
          <div className="text-xs uppercase tracking-wide text-amber-800/80">
            Section 2 deadline
          </div>
          <div className="mt-1 text-sm font-medium text-gray-900">
            {form.section2DueDate ? (
              <>
                {formatDateOnly(form.section2DueDate)}
                {form.daysUntilDue != null && (
                  <span className="ml-2 text-xs text-gray-500">
                    ({form.daysUntilDue} days)
                  </span>
                )}
              </>
            ) : (
              'Will be set with start date'
            )}
          </div>
        </div>
      </div>

      {(form.section2Overdue ?? form.overdue) && (
        <div className="mt-4 flex items-start gap-2 rounded-md border border-red-300 bg-red-50 p-3 text-sm text-red-800">
          <AlertTriangle className="h-4 w-4 flex-shrink-0" strokeWidth={2} />
          <span>
            Section 2 is past due. Please contact your HR representative.
          </span>
        </div>
      )}
    </div>
  );
}
