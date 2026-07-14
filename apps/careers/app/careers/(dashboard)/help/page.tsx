'use client';

import { LifeBuoy, Mail, MessagesSquare } from 'lucide-react';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import ProtectedRoute from '@/components/ProtectedRoute';

/**
 * Help & Support landing page for the candidate / intern sidebar.
 *
 * Lives under the (dashboard) group so the sidebar + protected route shell
 * are reused. Content is intentionally compact — point people at the right
 * channel rather than re-explain the product here.
 */
export default function HelpPage() {
  return (
    <ProtectedRoute requiredRoles={['INTERN']}>
      <DashboardLayout title="Help & Support">
        <Body />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Body() {
  return (
    <section className="space-y-6">
      <header className="flex items-start gap-3">
        <span className="rounded-full bg-accent/10 p-2 text-accent">
          <LifeBuoy className="h-5 w-5" strokeWidth={2} />
        </span>
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">We&apos;re here to help</h1>
          <p className="mt-1 text-sm text-gray-600">
            Pick the channel that fits — the team responds during US business hours.
          </p>
        </div>
      </header>

      <div className="grid gap-4 sm:grid-cols-2">
        <a
          href="mailto:careers@skyzentech.com"
          className="rounded-lg border border-gray-200 bg-white p-5 transition-shadow hover:shadow-sm"
        >
          <div className="mb-2 flex items-center gap-2">
            <Mail className="h-4 w-4 text-accent" strokeWidth={2} />
            <h2 className="text-sm font-semibold text-gray-900">Email</h2>
          </div>
          <p className="text-sm text-gray-700">careers@skyzentech.com</p>
          <p className="mt-1 text-xs text-gray-500">
            Best for application, offer, or compliance questions.
          </p>
        </a>

        <div className="rounded-lg border border-gray-200 bg-white p-5">
          <div className="mb-2 flex items-center gap-2">
            <MessagesSquare className="h-4 w-4 text-accent" strokeWidth={2} />
            <h2 className="text-sm font-semibold text-gray-900">In-product</h2>
          </div>
          <p className="text-sm text-gray-700">
            Use the dashboard side panel on any screen — your recruiter or supervisor
            will see your note.
          </p>
        </div>
      </div>

      <div className="rounded-lg border border-gray-200 bg-white p-5">
        <h2 className="mb-2 text-sm font-semibold text-gray-900">Common questions</h2>
        <ul className="space-y-2 text-sm text-gray-700">
          <li>
            <span className="font-medium text-gray-900">Where&apos;s my offer letter?</span>{' '}
            Once HR sends it, you&apos;ll find it under <strong>Offers</strong> in the
            sidebar.
          </li>
          <li>
            <span className="font-medium text-gray-900">I-9 deadline?</span> Section 1 is
            due your first day of work; Section 2 within three business days after.
          </li>
          <li>
            <span className="font-medium text-gray-900">Lost track of my code?</span>{' '}
            Hit <em>Resend</em> on the verify screen — a fresh one is emailed in seconds.
          </li>
        </ul>
      </div>
    </section>
  );
}
