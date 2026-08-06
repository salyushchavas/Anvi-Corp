'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { ChevronLeft } from 'lucide-react';
import api from '@/lib/careers/api';

/** Full read-only intern portfolio detail. Every section reflects what
 *  the underlying entities have captured; missing pieces (no address,
 *  no application when direct-onboarded, etc.) render as neutral
 *  "not captured" notes rather than being hidden — so a manager can
 *  see at a glance what data is present. */

interface NamedRef { userId: string; fullName: string | null; email: string | null }

interface PortfolioDetail {
  profile: {
    userId: string;
    lifecycleId: string | null;
    fullName: string | null;
    employeeId: string | null;
    applicantId: string | null;
    stage: string;
    stageLabel: string;
    joinedAt: string | null;
    hiredAt: string | null;
    startedAt: string | null;
    endedAt: string | null;
    monthsInProgram: number;
  };
  contact: {
    primaryEmail: string | null;
    phoneNumber: string | null;
    companyEmail: string | null;
  };
  address: {
    line1: string | null; line2: string | null;
    city: string | null; state: string | null;
    postalCode: string | null; country: string | null;
  } | null;
  education: {
    highestDegree: string | null;
    fieldOfStudy: string | null;
    university: string | null;
    graduationYear: string | null;
  } | null;
  workAuth: {
    workAuthType: string | null;
    authExpiresOn: string | null;
    i983PlanStatus: string | null;
  } | null;
  application: {
    applicationId: string;
    jobTitle: string | null;
    status: string;
    appliedAt: string | null;
    lastDecisionAt: string | null;
    lastDecisionBy: string | null;
  } | null;
  documents: {
    packetId: string | null;
    packetStatus: string;
    totalTasks: number;
    submittedTasks: number;
    reviewedTasks: number;
  };
  offers: {
    items: {
      offerId: string;
      offerNumber: string | null;
      status: string;
      statusLabel: string;
      issuedAt: string | null;
      executedAt: string | null;
      replacedAt: string | null;
      revokedAt: string | null;
      replacedByReason: string | null;
    }[];
  };
  projectsAndEvals: {
    totalProjects: number;
    completedProjects: number;
    publishedEvaluations: number;
    averageOverallScore: number | null;
    recentProjects: {
      projectId: string;
      title: string | null;
      status: string;
      monthYear: string | null;
      startedAt: string | null;
      completedAt: string | null;
      latestEvaluationStatus: string | null;
      latestOverallScore: number | null;
    }[];
  };
  team: {
    trainer: NamedRef | null;
    evaluator: NamedRef | null;
    manager: NamedRef | null;
    erm: NamedRef | null;
  };
  exit: {
    exitType: string | null;
    exitTypeLabel: string | null;
    exitedAt: string | null;
    reasonSummary: string | null;
  } | null;
}

const STAGE_PILL: Record<string, string> = {
  ACCOUNT_CREATED:  'bg-slate-100 text-slate-700',
  ONBOARDING:       'bg-amber-100 text-amber-900',
  ACTIVE:           'bg-emerald-100 text-emerald-800',
  EXITED:           'bg-slate-100 text-slate-500',
};

const OFFER_CHIP: Record<string, string> = {
  SENT:      'bg-slate-100 text-slate-700',
  SIGNED:    'bg-blue-100 text-blue-800',
  EXECUTED:  'bg-emerald-100 text-emerald-800',
  REPLACED:  'bg-amber-100 text-amber-900',
  REVOKED:   'bg-red-100 text-red-700',
  EXPIRED:   'bg-slate-100 text-slate-500',
  DECLINED:  'bg-slate-100 text-slate-500',
  WITHDRAWN: 'bg-slate-100 text-slate-500',
};

export default function ManagerInternPortfolioDetailPage() {
  const params = useParams<{ userId: string }>();
  const userId = params?.userId;
  const [data, setData] = useState<PortfolioDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!userId) return;
    setLoading(true);
    try {
      const res = await api.get<PortfolioDetail>(
        `/api/v1/manager/intern-portfolio/${userId}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [userId]);
  useEffect(() => { void load(); }, [load]);

  if (loading && !data) {
    return (
      <div className="mx-auto max-w-5xl p-6">
        <div className="h-40 animate-pulse rounded-lg bg-slate-100" />
      </div>
    );
  }
  if (err || !data) {
    return (
      <div className="mx-auto max-w-5xl p-6">
        <p className="text-xs text-slate-500">
          <Link href="/careers/manager/intern-portfolio" className="inline-flex items-center gap-1 hover:text-slate-700">
            <ChevronLeft className="h-3.5 w-3.5" />
            Back to Intern Portfolio
          </Link>
        </p>
        <p className="mt-4 rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          {err ?? 'Intern not found'}
        </p>
      </div>
    );
  }

  const { profile } = data;
  const stagePill = STAGE_PILL[profile.stage] ?? STAGE_PILL.ACCOUNT_CREATED;

  return (
    <div className="mx-auto max-w-5xl space-y-4 p-6">
      <p className="text-xs text-slate-500">
        <Link href="/careers/manager/intern-portfolio"
          className="inline-flex items-center gap-1 hover:text-slate-700">
          <ChevronLeft className="h-3.5 w-3.5" />
          Back to Intern Portfolio
        </Link>
      </p>

      {/* Header */}
      <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <h1 className="text-xl font-semibold text-slate-900">
              {profile.fullName ?? '(unnamed)'}
            </h1>
            <p className="text-xs text-slate-500">
              {profile.employeeId ?? '—'}
              {profile.applicantId && <span className="ml-2">· {profile.applicantId}</span>}
            </p>
            <p className="text-[11px] text-slate-500">
              {profile.monthsInProgram} months in program
              {profile.joinedAt && <> · Joined {new Date(profile.joinedAt).toLocaleDateString()}</>}
              {profile.startedAt && <> · Started {new Date(profile.startedAt).toLocaleDateString()}</>}
              {profile.endedAt && <> · Ended {new Date(profile.endedAt).toLocaleDateString()}</>}
            </p>
          </div>
          <span className={
            'inline-flex whitespace-nowrap rounded-full px-2.5 py-1 text-xs font-semibold ' + stagePill
          }>
            {profile.stageLabel}
          </span>
        </div>
      </section>

      {/* Two-column grid — profile-adjacent sections on the left,
          activity + team on the right. Read-only throughout. */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <Section title="Contact">
          <Field label="Primary email" value={data.contact.primaryEmail} />
          <Field label="Phone" value={data.contact.phoneNumber} />
          <Field label="Company email" value={data.contact.companyEmail}
            emptyLabel="Not provisioned" />
        </Section>

        <Section title="Address">
          {!data.address || allEmpty(Object.values(data.address)) ? (
            <Empty>No address captured.</Empty>
          ) : (
            <>
              {data.address.line1 && <p className="text-sm text-slate-700">{data.address.line1}</p>}
              {data.address.line2 && <p className="text-sm text-slate-700">{data.address.line2}</p>}
              <p className="text-sm text-slate-700">
                {[data.address.city, data.address.state, data.address.postalCode].filter(Boolean).join(', ')}
              </p>
              {data.address.country && <p className="text-sm text-slate-700">{data.address.country}</p>}
            </>
          )}
        </Section>

        <Section title="Education">
          {!data.education || allEmpty(Object.values(data.education)) ? (
            <Empty>No education captured.</Empty>
          ) : (
            <>
              <Field label="Highest degree" value={data.education.highestDegree} />
              <Field label="Field of study" value={data.education.fieldOfStudy} />
              <Field label="University" value={data.education.university} />
              <Field label="Graduation year" value={data.education.graduationYear} />
            </>
          )}
        </Section>

        <Section title="Work authorization">
          {!data.workAuth ? (
            <Empty>No work-authorization record on file.</Empty>
          ) : (
            <>
              <Field label="Type" value={data.workAuth.workAuthType?.replaceAll('_', ' ') ?? null} />
              <Field label="Auth expires on"
                value={data.workAuth.authExpiresOn
                  ? new Date(data.workAuth.authExpiresOn).toLocaleDateString() : null} />
              {data.workAuth.i983PlanStatus && (
                <Field label="I-983 plan status"
                  value={data.workAuth.i983PlanStatus.replaceAll('_', ' ')} />
              )}
            </>
          )}
        </Section>

        <Section title="Application">
          {!data.application ? (
            <Empty>Direct-onboarded — no pipeline application on file.</Empty>
          ) : (
            <>
              <Field label="Job" value={data.application.jobTitle} />
              <Field label="Status" value={data.application.status?.replaceAll('_', ' ')} />
              <Field label="Applied"
                value={data.application.appliedAt
                  ? new Date(data.application.appliedAt).toLocaleDateString() : null} />
              <Field label="Last decision by" value={data.application.lastDecisionBy} />
              {data.application.lastDecisionAt && (
                <Field label="Decision at"
                  value={new Date(data.application.lastDecisionAt).toLocaleDateString()} />
              )}
            </>
          )}
        </Section>

        <Section title="Documents">
          <Field label="Packet status"
            value={data.documents.packetStatus === 'NONE'
              ? null
              : data.documents.packetStatus.replaceAll('_', ' ')}
            emptyLabel="No packet assigned" />
          {data.documents.totalTasks > 0 && (
            <p className="mt-1 text-xs text-slate-600">
              {data.documents.reviewedTasks} approved · {data.documents.submittedTasks} submitted ·{' '}
              {data.documents.totalTasks} total
            </p>
          )}
        </Section>
      </div>

      {/* Offers / IDMS document history — full-width table, chip per state */}
      <Section title="Offer &amp; IDMS document history">
        {data.offers.items.length === 0 ? (
          <Empty>No offers have been issued.</Empty>
        ) : (
          <ul className="mt-2 divide-y divide-slate-100">
            {data.offers.items.map((o) => (
              <li key={o.offerId} className="flex flex-wrap items-center justify-between gap-2 py-2">
                <div className="min-w-0">
                  <p className="text-sm font-medium text-slate-900">
                    {o.offerNumber ?? o.offerId.slice(0, 8) + '…'}
                  </p>
                  <p className="text-[11px] text-slate-500">
                    {o.issuedAt && <>Issued {new Date(o.issuedAt).toLocaleDateString()}</>}
                    {o.executedAt && <> · Executed {new Date(o.executedAt).toLocaleDateString()}</>}
                    {o.replacedAt && <> · Replaced {new Date(o.replacedAt).toLocaleDateString()}</>}
                    {o.replacedByReason && <> · {o.replacedByReason}</>}
                  </p>
                </div>
                <span className={
                  'inline-flex whitespace-nowrap rounded-full px-2 py-0.5 text-[10px] font-semibold '
                  + (OFFER_CHIP[o.status] ?? 'bg-slate-100 text-slate-700')
                }>
                  {o.statusLabel}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Section>

      {/* Projects & evaluations summary */}
      <Section title="Projects &amp; evaluations">
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Stat label="Total projects" value={data.projectsAndEvals.totalProjects} />
          <Stat label="Completed" value={data.projectsAndEvals.completedProjects} />
          <Stat label="Published evals" value={data.projectsAndEvals.publishedEvaluations} />
          <Stat label="Avg overall"
            value={data.projectsAndEvals.averageOverallScore != null
              ? data.projectsAndEvals.averageOverallScore.toFixed(2)
              : '—'} />
        </div>
        {data.projectsAndEvals.recentProjects.length === 0 ? (
          <p className="mt-3 rounded-md border border-dashed border-slate-200 bg-slate-50 p-3 text-center text-xs text-slate-500">
            No projects on the roster yet.
          </p>
        ) : (
          <ul className="mt-3 divide-y divide-slate-100">
            {data.projectsAndEvals.recentProjects.map((p) => (
              <li key={p.projectId} className="flex flex-wrap items-center justify-between gap-2 py-2">
                <div className="min-w-0">
                  <p className="text-sm font-medium text-slate-900">
                    {p.title ?? '(untitled project)'}
                  </p>
                  <p className="text-[11px] text-slate-500">
                    {p.monthYear && <>{p.monthYear} · </>}
                    {p.status?.replaceAll('_', ' ')}
                    {p.completedAt && <> · Completed {new Date(p.completedAt).toLocaleDateString()}</>}
                  </p>
                </div>
                <div className="flex items-center gap-2 text-[11px]">
                  {p.latestOverallScore != null && (
                    <span className="rounded bg-slate-100 px-1.5 py-0.5 font-semibold text-slate-700">
                      {p.latestOverallScore} / 5
                    </span>
                  )}
                  {p.latestEvaluationStatus && (
                    <span className="rounded bg-slate-100 px-1.5 py-0.5 text-slate-600">
                      {p.latestEvaluationStatus.replaceAll('_', ' ')}
                    </span>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Section>

      {/* Team */}
      <Section title="Team">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <TeamRow label="Trainer" ref={data.team.trainer} />
          <TeamRow label="Evaluator" ref={data.team.evaluator} />
          <TeamRow label="Manager" ref={data.team.manager} />
          <TeamRow label="ERM" ref={data.team.erm} />
        </div>
      </Section>

      {/* Exit — only shown when the engagement has ended */}
      {data.exit && (
        <Section title="Exit record">
          <Field label="Type" value={data.exit.exitTypeLabel ?? data.exit.exitType} />
          {data.exit.exitedAt && (
            <Field label="Exited on"
              value={new Date(data.exit.exitedAt).toLocaleDateString()} />
          )}
          {data.exit.reasonSummary && (
            <Field label="Reason" value={data.exit.reasonSummary} />
          )}
        </Section>
      )}
    </div>
  );
}

// ─── Reusable render helpers ─────────────────────────────────────────────

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <h3 className="text-sm font-semibold text-slate-900" dangerouslySetInnerHTML={{ __html: title }} />
      <div className="mt-2 space-y-1">{children}</div>
    </section>
  );
}

function Field({ label, value, emptyLabel }: {
  label: string; value: string | number | null | undefined; emptyLabel?: string;
}) {
  const empty = value == null || value === '';
  return (
    <p className="text-xs text-slate-700">
      <span className="font-medium text-slate-500">{label}:</span>{' '}
      {empty
        ? <span className="italic text-slate-400">{emptyLabel ?? 'Not captured'}</span>
        : <span className="text-slate-900">{value}</span>}
    </p>
  );
}

function Empty({ children }: { children: React.ReactNode }) {
  return (
    <p className="rounded-md border border-dashed border-slate-200 bg-slate-50 p-3 text-center text-xs text-slate-500">
      {children}
    </p>
  );
}

function Stat({ label, value }: { label: string; value: number | string }) {
  return (
    <div className="rounded-md border border-slate-200 bg-slate-50 p-2">
      <p className="text-[10px] uppercase tracking-wide text-slate-500">{label}</p>
      <p className="mt-0.5 text-sm font-semibold tabular-nums text-slate-900">{value}</p>
    </div>
  );
}

function TeamRow({ label, ref: r }: { label: string; ref: NamedRef | null }) {
  return (
    <div className="rounded-md border border-slate-200 bg-slate-50 p-2">
      <p className="text-[10px] uppercase tracking-wide text-slate-500">{label}</p>
      {r ? (
        <>
          <p className="mt-0.5 text-sm font-medium text-slate-900">{r.fullName ?? '—'}</p>
          {r.email && <p className="text-[11px] text-slate-500">{r.email}</p>}
        </>
      ) : (
        <p className="mt-0.5 text-xs italic text-slate-400">Not assigned</p>
      )}
    </div>
  );
}

function allEmpty(vals: (string | null | undefined)[]): boolean {
  return vals.every((v) => v == null || v === '');
}
