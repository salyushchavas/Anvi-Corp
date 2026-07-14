'use client';

import Link from 'next/link';
import { useState } from 'react';
import { BRAND } from '@/lib/brand';
import {
  Award,
  BookOpen,
  CalendarCheck,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  ClipboardList,
  FileText,
  GraduationCap,
  HelpCircle,
  Mail,
  ShieldCheck,
  Sparkles,
} from 'lucide-react';

export default function EvaluatorHelpPage() {
  return (
    <div className="mx-auto max-w-5xl space-y-5 p-6">
      <p className="text-xs text-slate-500">
        <Link
          href="/careers/evaluator"
          className="inline-flex items-center gap-1 hover:text-slate-700"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
          Evaluator home
        </Link>
      </p>

      <header className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <h1 className="text-xl font-semibold text-slate-900">Help & Resources</h1>
        <p className="text-xs text-slate-500">
          Quick start, federal compliance notes for STEM OPT I-983, FAQ, and
          who to contact when something doesn&apos;t add up.
        </p>
      </header>

      <section>
        <h2 className="mb-3 text-sm font-semibold text-slate-900">Quick Start</h2>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <QuickCard
            icon={<CalendarCheck className="h-4 w-4" />}
            title="Schedule a session"
            body="Open an active evaluee, click Schedule next evaluation, pick a date — Zoom link is auto-created."
            href="/careers/evaluator/active-evaluees"
          />
          <QuickCard
            icon={<ClipboardList className="h-4 w-4" />}
            title="Compose & publish"
            body="Score the 4 rubric criteria, write at least 50 chars of feedback, pick a recommendation, publish to the intern."
            href="/careers/evaluator/pending-evaluations"
          />
          <QuickCard
            icon={<FileText className="h-4 w-4" />}
            title="I-983 evaluations"
            body="STEM OPT interns get 6-month + 12-month I-983 reviews. The intern signs; you submit to their DSO within 10 days."
            href="/careers/evaluator/i983-evaluations"
          />
          <QuickCard
            icon={<Award className="h-4 w-4" />}
            title="Schedule the Final"
            body="Final evaluations open once ERM initiates exit. They include a rehire-eligible verdict that feeds the rehire pipeline."
            href="/careers/evaluator/active-evaluees"
          />
          <QuickCard
            icon={<BookOpen className="h-4 w-4" />}
            title="Audit & History"
            body="Every published / amended / acknowledged evaluation is searchable in Evaluation History."
            href="/careers/evaluator/evaluation-history"
          />
          <QuickCard
            icon={<Sparkles className="h-4 w-4" />}
            title="Monthly Reports"
            body="Acknowledgment lag, recommendation mix, criterion averages, per-intern roll-up. CSV download."
            href="/careers/evaluator/reports"
          />
        </div>
      </section>

      <section className="rounded-lg border border-amber-200 bg-amber-50/40 p-5 shadow-sm">
        <h2 className="inline-flex items-center gap-2 text-sm font-semibold text-amber-900">
          <ShieldCheck className="h-4 w-4" />
          Federal Compliance Notes (STEM OPT / I-983)
        </h2>
        <ul className="mt-3 list-disc space-y-2 pl-5 text-sm text-amber-900">
          <li>
            <strong>6-month + 12-month evaluations are mandatory</strong> for
            every F-1 STEM OPT intern, per 8 CFR 214.2(f)(10)(ii)(C)(8)(ii).
            Missing the window puts the intern&apos;s status at risk.
          </li>
          <li>
            <strong>Intern&apos;s typed signature</strong> serves as
            attestation of accuracy. You countersign on publish; the platform
            stores both with timestamps.
          </li>
          <li>
            <strong>Submit to the DSO within 10 days</strong> of intern
            acknowledgment. Use the Mark DSO Submitted action with the chosen
            method (email / portal / in-person / mail).
          </li>
          <li>
            <strong>Amendments require a 30-character reason</strong> and
            reset the intern&apos;s acknowledgment — they must re-acknowledge
            the amended version.
          </li>
          <li>
            Never store DSO PII (DOB, A-number, etc.) in free-text fields.
            The platform&apos;s I-983 forms collect only the fields the
            regulation requires.
          </li>
        </ul>
      </section>

      <section>
        <h2 className="mb-3 text-sm font-semibold text-slate-900">FAQ</h2>
        <div className="space-y-2">
          <Faq q="When can I schedule a Final evaluation?">
            Only after ERM has opened the exit flow on the intern&apos;s
            lifecycle (creates an ExitRecord). If you don&apos;t see the
            option, ask ERM to initiate exit first. SUPER_ADMIN can bypass
            the gate for off-cycle cases.
          </Faq>
          <Faq q="What does REHIRE_ELIGIBLE actually do?">
            It&apos;s a coarse verdict — Final evaluation only — that flags
            the intern for the rehire pipeline managed by ERM. It does not
            automatically rehire anyone; it just surfaces them for review.
          </Faq>
          <Faq q="Can I edit a published evaluation?">
            Yes — open the evaluation detail and amend it. You must provide a
            reason of at least 30 characters, and the intern will need to
            re-acknowledge the new version.
          </Faq>
          <Faq q="The intern never acknowledged my evaluation — what happens?">
            After 7 days, the unacknowledged evaluation surfaces on the ERM
            Home as an exception. Reach out to the intern via the
            communication channel of your choice; the platform does not
            auto-resolve.
          </Faq>
          <Faq q="What if Zoom isn't ready when I schedule?">
            Scheduling still succeeds and the row is created in SCHEDULED
            status. You can paste a manual meeting link by amending, or wait
            until Zoom recovers — the next save will create the meeting on
            the next attempt.
          </Faq>
          <Faq q="Are my internal notes ever shown to the intern?">
            No. Internal notes are Evaluator-only, never appear in any
            intern-facing DTO, and are excluded from amendment snapshots
            visible to non-Evaluators.
          </Faq>
        </div>
      </section>

      <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <h2 className="inline-flex items-center gap-2 text-sm font-semibold text-slate-900">
          <HelpCircle className="h-4 w-4" />
          Contact
        </h2>
        <p className="mt-2 text-sm text-slate-700">
          For account questions, role assignments, or Zoom provisioning,
          contact your ERM / SUPER_ADMIN. For federal compliance questions on
          I-983 reviews, work with the intern&apos;s university DSO directly.
        </p>
        <p className="mt-3 text-xs text-slate-500">
          <Mail className="mr-1 inline h-3 w-3" />
          Platform support: open a ticket via your ERM lead.
        </p>
      </section>

      <section className="rounded-lg border border-slate-200 bg-slate-50 p-4 text-xs text-slate-600">
        <p className="inline-flex items-center gap-1 font-semibold">
          <GraduationCap className="h-3.5 w-3.5" />
          Compliance reminder
        </p>
        <p className="mt-1">
          You are responsible for the accuracy of every evaluation you
          publish. {BRAND.name} stores audit logs of every publish, amend, and
          DSO submission. Treat evaluation content as a federal record where
          the intern is on STEM OPT.
        </p>
      </section>
    </div>
  );
}

function QuickCard({
  icon, title, body, href,
}: {
  icon: React.ReactNode;
  title: string;
  body: string;
  href: string;
}) {
  return (
    <Link
      href={href}
      className="block rounded-lg border border-slate-200 bg-white p-4 shadow-sm hover:border-brand-300 hover:shadow"
    >
      <p className="inline-flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-brand-700">
        {icon}
        {title}
      </p>
      <p className="mt-2 text-xs text-slate-700">{body}</p>
      <p className="mt-3 inline-flex items-center gap-0.5 text-[11px] font-medium text-brand-700">
        Open <ChevronRight className="h-3 w-3" />
      </p>
    </Link>
  );
}

function Faq({ q, children }: { q: string; children: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="rounded-md border border-slate-200 bg-white">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="flex w-full items-center justify-between px-4 py-3 text-left text-sm font-medium text-slate-800 hover:bg-slate-50"
      >
        <span>{q}</span>
        <ChevronDown
          className={'h-4 w-4 transition-transform ' + (open ? 'rotate-180' : '')}
        />
      </button>
      {open && (
        <div className="border-t border-slate-100 px-4 py-3 text-sm text-slate-700">
          {children}
        </div>
      )}
    </div>
  );
}
