'use client';

import Link from 'next/link';
import { useState } from 'react';
import {
  AlertOctagon,
  BookOpen,
  CalendarClock,
  ChevronDown,
  ClipboardList,
  FileText,
  HelpCircle,
  Mail,
  ShieldCheck,
  Users,
} from 'lucide-react';

type QuickStart = {
  icon: React.ReactNode;
  title: string;
  body: string;
  href: string;
  cta: string;
};

const QUICK_STARTS: QuickStart[] = [
  {
    icon: <Users className="h-4 w-4" />,
    title: 'View your active interns',
    body: 'Each card shows their current project, weekly meeting status, and pending submissions.',
    href: '/careers/trainer/active-interns',
    cta: 'Open Active Interns',
  },
  {
    icon: <ClipboardList className="h-4 w-4" />,
    title: 'Assign a project',
    body: 'Pick from the catalog or write a custom brief. The 2-per-month limit is enforced server-side.',
    href: '/careers/trainer/assign-project',
    cta: 'Open Assign Project',
  },
  {
    icon: <CalendarClock className="h-4 w-4" />,
    title: 'Schedule a weekly meeting',
    body: 'Sets up Zoom + reminders. Default cadence is taken from your Settings page.',
    href: '/careers/trainer/weekly-meetings',
    cta: 'Open Weekly Meetings',
  },
  {
    icon: <FileText className="h-4 w-4" />,
    title: 'Review pending submissions',
    body: '4 decisions — Accept, Request revision, Escalate, No action yet. SLA is 48h.',
    href: '/careers/trainer/pending-reviews',
    cta: 'Open Pending Reviews',
  },
];

type Faq = { q: string; a: React.ReactNode };

const FAQS: Faq[] = [
  {
    q: 'Why does the assignment wizard say "limit reached"?',
    a: (
      <p>
        Trainer doc §5 caps every intern at <strong>2 active projects per calendar month</strong>.
        Either wait until the next month rolls over, or have the intern mark one of the current
        projects as Completed.
      </p>
    ),
  },
  {
    q: 'How do I escalate a stuck intern?',
    a: (
      <p>
        Open <Link href="/careers/trainer/pending-reviews" className="text-brand-700 underline">Pending Reviews</Link>,
        choose <strong>Escalate</strong>, and write a ≥50 character reason. ERM + Manager are notified
        automatically and the intern sees a banner.
      </p>
    ),
  },
  {
    q: 'Can I edit feedback after I publish it?',
    a: (
      <p>
        No — published feedback is immutable for audit purposes. If you made a mistake, submit a new
        feedback round on the next submission and reference the correction in the notes.
      </p>
    ),
  },
  {
    q: 'Why is my intern missing from the Reports CSV?',
    a: (
      <p>
        The report is scoped to interns who were active in the selected month. Newly assigned
        interns appear from their lifecycle start date; off-boarded interns drop off after their
        end date.
      </p>
    ),
  },
  {
    q: 'What data am I NOT allowed to see?',
    a: (
      <p>
        Per doc §3, Trainers do not see compensation, performance review files, or any document
        outside the training scope. If you need that data, route through the Reporting Manager.
      </p>
    ),
  },
  {
    q: 'How do I turn off Saturday reminder emails?',
    a: (
      <p>
        Go to <Link href="/careers/trainer/settings" className="text-brand-700 underline">Settings → Notifications</Link>
        {' '}and set Email Frequency to <strong>Weekly</strong> or <strong>Never</strong>.
        Transactional notices (assignments, escalations) still send regardless.
      </p>
    ),
  },
];

export default function TrainerHelpPage() {
  return (
    <div className="mx-auto max-w-4xl space-y-6 p-6">
      <div>
        <p className="text-xs text-slate-500">
          <Link href="/careers/trainer" className="hover:text-slate-700">← Trainer dashboard</Link>
        </p>
        <h1 className="mt-1 text-xl font-semibold text-slate-900">Help</h1>
        <p className="text-xs text-slate-500">
          Quick reference for the Trainer role — boundaries, escalation pathways, and contacts.
        </p>
      </div>

      <section>
        <h2 className="mb-2 inline-flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-slate-600">
          <BookOpen className="h-3.5 w-3.5" />
          Quick start
        </h2>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {QUICK_STARTS.map((qs) => (
            <Link key={qs.href} href={qs.href}
              className="group rounded-lg border border-slate-200 bg-white p-4 hover:border-brand-300 hover:shadow-sm">
              <div className="flex items-center gap-2 text-brand-700">
                {qs.icon}
                <p className="text-sm font-semibold text-slate-900">{qs.title}</p>
              </div>
              <p className="mt-2 text-xs text-slate-600">{qs.body}</p>
              <p className="mt-3 text-[11px] font-semibold text-brand-700 group-hover:underline">
                {qs.cta} →
              </p>
            </Link>
          ))}
        </div>
      </section>

      <section>
        <h2 className="mb-2 inline-flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-slate-600">
          <HelpCircle className="h-3.5 w-3.5" />
          FAQ
        </h2>
        <div className="divide-y divide-slate-200 overflow-hidden rounded-lg border border-slate-200 bg-white">
          {FAQS.map((f, i) => <FaqItem key={i} q={f.q} a={f.a} />)}
        </div>
      </section>

      <section>
        <h2 className="mb-2 inline-flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-slate-600">
          <Mail className="h-3.5 w-3.5" />
          Contact
        </h2>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <ContactCard
            role="ERM (Engagement & Relationship Manager)"
            email="erm@skyzen.test"
            blurb="Escalations, intern lifecycle changes, document chasing." />
          <ContactCard
            role="Reporting Manager"
            email="manager@skyzen.test"
            blurb="Resource conflicts, scope changes, off-boarding." />
        </div>
      </section>

      <section className="rounded-lg border border-amber-200 bg-amber-50 p-4">
        <h2 className="inline-flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-amber-900">
          <ShieldCheck className="h-3.5 w-3.5" />
          Compliance reminder
        </h2>
        <ul className="mt-2 list-inside list-disc space-y-1 text-xs text-amber-900">
          <li>Do not share intern personal data outside the platform — use document links only.</li>
          <li>Feedback notes are written verbatim into audit logs. Avoid hostile language.</li>
          <li>If an intern reports harassment or discrimination, escalate immediately and do <strong>not</strong> investigate yourself.</li>
          <li>Per doc §3, you do not have access to payroll, salary, or performance review documents.</li>
        </ul>
      </section>

      <section className="rounded-lg border border-red-200 bg-red-50 p-4">
        <h2 className="inline-flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-red-900">
          <AlertOctagon className="h-3.5 w-3.5" />
          When to escalate
        </h2>
        <ul className="mt-2 list-inside list-disc space-y-1 text-xs text-red-900">
          <li>Intern misses 2 consecutive weekly meetings without notice.</li>
          <li>Same project rejected 3 times in a row with no improvement trajectory.</li>
          <li>Intern reports a blocker outside your control (access, equipment, account).</li>
          <li>Any conduct or compliance concern.</li>
        </ul>
      </section>
    </div>
  );
}

function FaqItem({ q, a }: { q: string; a: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  return (
    <div>
      <button type="button" onClick={() => setOpen((o) => !o)}
        className="flex w-full items-center justify-between gap-3 px-4 py-3 text-left hover:bg-slate-50">
        <span className="text-sm font-medium text-slate-900">{q}</span>
        <ChevronDown className={'h-4 w-4 text-slate-400 transition ' + (open ? 'rotate-180' : '')} />
      </button>
      {open && (
        <div className="border-t border-slate-100 bg-slate-50 px-4 py-3 text-xs leading-relaxed text-slate-700">
          {a}
        </div>
      )}
    </div>
  );
}

function ContactCard({ role, email, blurb }: { role: string; email: string; blurb: string }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">{role}</p>
      <a href={`mailto:${email}`} className="mt-1 block text-sm font-medium text-brand-700 hover:underline">
        {email}
      </a>
      <p className="mt-2 text-xs text-slate-600">{blurb}</p>
    </div>
  );
}
