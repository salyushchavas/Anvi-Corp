'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import AlertSeverityDot from '@/components/erm/compliance/AlertSeverityDot';
import UpdateWorkAuthModal from '@/components/erm/compliance/UpdateWorkAuthModal';
import RecordI9Section2Modal from '@/components/erm/compliance/RecordI9Section2Modal';
import {
  RecordEverifyModal,
  UpdateEverifyStatusModal,
} from '@/components/erm/compliance/EverifyModals';
import type {
  EverifyCard,
  I9TimelineCard,
  InternTimeline,
  WorkAuthCard,
} from '@/components/erm/compliance/types';

export default function InternComplianceTimelinePage() {
  // useParams (next/navigation) — Next 14.2 delivers params as a plain
  // object, not a Promise; React's use() rejects non-thenables with #438.
  const params = useParams<{ userId: string }>();
  const userId = params?.userId ?? '';
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <Body userId={userId} />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Body({ userId }: { userId: string }) {
  const [t, setT] = useState<InternTimeline | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [waOpen, setWaOpen] = useState(false);
  const [i9Open, setI9Open] = useState(false);
  const [evOpenRecord, setEvOpenRecord] = useState(false);
  const [evOpenStatus, setEvOpenStatus] = useState(false);
  const [revealedCase, setRevealedCase] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await api.get<InternTimeline>(
        `/api/v1/erm/compliance/interns/${userId}/timeline`,
      );
      setT(res.data);
      setErr(null);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Failed to load timeline');
    }
  }, [userId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function revealCase() {
    if (!t?.everify?.caseId) return;
    try {
      const res = await api.post<{ caseId: string; caseNumberFull: string }>(
        `/api/v1/erm/compliance/everify-cases/${t.everify.caseId}/reveal-number`,
      );
      setRevealedCase(res.data.caseNumberFull);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Reveal failed');
    }
  }

  if (err && !t) {
    return (
      <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
        {err}
      </p>
    );
  }
  if (!t) {
    return <div className="h-64 animate-pulse rounded-lg bg-slate-100" />;
  }

  return (
    <>
      <PageHeader
        title={t.fullName ?? 'Intern'}
        subtitle={t.email ?? undefined}
      />
      <div className="mb-4">
        <Link
          href="/careers/erm/compliance"
          className="text-xs text-slate-500 hover:text-slate-700"
        >
          ← Pipeline
        </Link>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <WorkAuthSection
          card={t.workAuth}
          onEdit={() => setWaOpen(true)}
        />
        <I9Section
          card={t.i9}
          onSign={() => setI9Open(true)}
        />
        <EverifySection
          card={t.everify}
          i9FormId={t.i9?.i9FormId ?? null}
          onRecord={() => setEvOpenRecord(true)}
          onUpdate={() => setEvOpenStatus(true)}
          revealedCase={revealedCase}
          onReveal={() => void revealCase()}
        />
        <I983Section card={t.i983} />
      </div>

      <section className="mt-6 rounded-lg border border-slate-200 bg-white p-4">
        <h3 className="mb-3 text-sm font-semibold text-slate-900">
          Upcoming deadlines
        </h3>
        {t.upcomingEvents.length === 0 ? (
          <p className="text-xs text-slate-500">No upcoming compliance events.</p>
        ) : (
          <ul className="divide-y divide-slate-100">
            {t.upcomingEvents.map((e, i) => (
              <li
                key={i}
                className="flex items-center justify-between py-2 text-sm"
              >
                <div>
                  <div className="text-slate-900">{e.label}</div>
                  <div className="text-[11px] text-slate-500">
                    {e.eventDate}
                  </div>
                </div>
                <AlertSeverityDot
                  severity={e.severity}
                  label={e.daysUntil != null ? e.daysUntil + 'd' : undefined}
                />
              </li>
            ))}
          </ul>
        )}
      </section>

      {waOpen && (
        <UpdateWorkAuthModal
          userId={userId}
          initial={t.workAuth}
          onClose={() => setWaOpen(false)}
          onSaved={(updated) => {
            setT({ ...t, workAuth: updated });
            setWaOpen(false);
          }}
        />
      )}
      {i9Open && (
        <RecordI9Section2Modal
          userId={userId}
          initialFirstDay={t.i9?.firstDayOfEmployment ?? null}
          onClose={() => setI9Open(false)}
          onSaved={(updated) => {
            setT({ ...t, i9: updated });
            setI9Open(false);
          }}
        />
      )}
      {evOpenRecord && t.i9?.i9FormId && (
        <RecordEverifyModal
          i9FormId={t.i9.i9FormId}
          onClose={() => setEvOpenRecord(false)}
          onSaved={(updated) => {
            setT({ ...t, everify: updated });
            setEvOpenRecord(false);
          }}
        />
      )}
      {evOpenStatus && t.everify?.caseId && (
        <UpdateEverifyStatusModal
          caseId={t.everify.caseId}
          initial={t.everify}
          onClose={() => setEvOpenStatus(false)}
          onSaved={(updated) => {
            setT({ ...t, everify: updated });
            setEvOpenStatus(false);
          }}
        />
      )}
    </>
  );
}

function WorkAuthSection({
  card,
  onEdit,
}: {
  card: WorkAuthCard | null;
  onEdit: () => void;
}) {
  return (
    <Card title="Work authorization" onAction={onEdit} actionLabel={card ? 'Edit' : 'Record'}>
      {card ? (
        <dl className="space-y-1 text-xs">
          <Row label="Type" value={card.workAuthType} />
          <Row label="From" value={card.authorizedFrom} />
          <Row label="Until" value={card.authorizedUntil} />
          <Row label="EAD" value={card.eadCardNumberMasked} />
          <Row label="EAD exp" value={card.eadExpiration} />
          <Row label="I-20 exp" value={card.i20Expiration} />
          <Row
            label="I-983 required"
            value={card.i983Required ? 'Yes' : 'No'}
          />
          {card.dsoName && (
            <>
              <Row label="DSO" value={card.dsoName} />
              <Row label="DSO email" value={card.dsoEmail} />
              <Row label="DSO phone" value={card.dsoPhone} />
            </>
          )}
          {card.ermNotes && (
            <div className="mt-2 rounded bg-slate-50 p-2 text-[11px] text-slate-700">
              <span className="block text-[10px] font-semibold uppercase text-red-600">
                ERM-only
              </span>
              {card.ermNotes}
            </div>
          )}
        </dl>
      ) : (
        <p className="text-xs text-slate-500">
          No work-auth record yet. Click "Record" to seed one.
        </p>
      )}
    </Card>
  );
}

function I9Section({
  card,
  onSign,
}: {
  card: I9TimelineCard | null;
  onSign: () => void;
}) {
  const canSign = card && !card.section2SignedAt;
  return (
    <Card
      title="I-9 Section 2"
      onAction={canSign ? onSign : undefined}
      actionLabel="Sign §2"
    >
      {card ? (
        <dl className="space-y-1 text-xs">
          <Row label="Status" value={card.status} />
          <Row
            label="First day"
            value={card.firstDayOfEmployment}
          />
          <Row label="§2 due by" value={card.section2DueByCalculated} />
          <Row
            label="§2 signed"
            value={
              card.section2SignedAt
                ? new Date(card.section2SignedAt).toLocaleString()
                : null
            }
          />
          <div className="pt-1">
            <AlertSeverityDot
              severity={card.section2Severity}
              label={
                card.section2DaysUntil != null
                  ? card.section2DaysUntil + 'd'
                  : undefined
              }
            />
          </div>
        </dl>
      ) : (
        <p className="text-xs text-slate-500">
          No I-9 form for this intern yet. The applicant starts §1; ERM signs
          §2 here.
        </p>
      )}
    </Card>
  );
}

function EverifySection({
  card,
  i9FormId,
  onRecord,
  onUpdate,
  revealedCase,
  onReveal,
}: {
  card: EverifyCard | null;
  i9FormId: string | null;
  onRecord: () => void;
  onUpdate: () => void;
  revealedCase: string | null;
  onReveal: () => void;
}) {
  const canRecord = !card && i9FormId;
  return (
    <Card
      title="E-Verify (manual)"
      onAction={card ? onUpdate : canRecord ? onRecord : undefined}
      actionLabel={card ? 'Update status' : 'Record case'}
    >
      {card ? (
        <dl className="space-y-1 text-xs">
          <Row label="Status" value={card.status} />
          <Row
            label="Case #"
            value={revealedCase ?? card.caseNumberMasked}
          />
          {!revealedCase && card.caseNumberMasked && (
            <button
              type="button"
              onClick={onReveal}
              className="text-[11px] text-brand-700 hover:underline"
            >
              Reveal full # (audit-logged)
            </button>
          )}
          <Row label="Due by" value={card.dueBy} />
          <Row label="Expected close" value={card.expectedCloseBy} />
          <Row
            label="Opened"
            value={card.openedAt ? new Date(card.openedAt).toLocaleString() : null}
          />
          {card.closedAt && (
            <Row
              label="Closed"
              value={new Date(card.closedAt).toLocaleString()}
            />
          )}
          {card.ermNotes && (
            <div className="mt-2 rounded bg-slate-50 p-2 text-[11px] text-slate-700">
              <span className="block text-[10px] font-semibold uppercase text-red-600">
                ERM-only
              </span>
              {card.ermNotes}
            </div>
          )}
          <div className="pt-1">
            <AlertSeverityDot severity={card.severity} />
          </div>
        </dl>
      ) : i9FormId ? (
        <p className="text-xs text-slate-500">
          No E-Verify case yet. Open one once you have the federal case # from
          your manual E-Verify session.
        </p>
      ) : (
        <p className="text-xs text-slate-500">
          Need an I-9 form first. E-Verify cases attach to a signed §2.
        </p>
      )}
    </Card>
  );
}

function I983Section({ card }: { card: InternTimeline['i983'] }) {
  return (
    <Card title="I-983 (STEM OPT)">
      {card ? (
        <dl className="space-y-1 text-xs">
          <Row label="Status" value={card.status} />
          <Row label="Period start" value={card.periodStartDate} />
          <Row label="Period end" value={card.periodEndDate} />
          <Row
            label="Last evaluation"
            value={
              card.lastEvaluationAt
                ? new Date(card.lastEvaluationAt).toLocaleDateString()
                : null
            }
          />
          <div className="pt-1">
            <AlertSeverityDot
              severity={card.severity}
              label={
                card.daysUntilNext != null
                  ? card.daysUntilNext + 'd'
                  : undefined
              }
            />
          </div>
        </dl>
      ) : (
        <p className="text-xs text-slate-500">
          I-983 not required for this intern's track.
        </p>
      )}
    </Card>
  );
}

function Card({
  title,
  children,
  onAction,
  actionLabel,
}: {
  title: string;
  children: React.ReactNode;
  onAction?: () => void;
  actionLabel?: string;
}) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4">
      <div className="mb-2 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-slate-900">{title}</h3>
        {onAction && actionLabel && (
          <button
            type="button"
            onClick={onAction}
            className="rounded-md border border-slate-300 px-2 py-0.5 text-[11px] font-medium text-slate-700 hover:bg-slate-50"
          >
            {actionLabel}
          </button>
        )}
      </div>
      {children}
    </section>
  );
}

function Row({
  label,
  value,
}: {
  label: string;
  value: string | null | undefined;
}) {
  return (
    <div className="flex justify-between border-b border-slate-100 pb-0.5">
      <span className="text-slate-500">{label}</span>
      <span className="text-slate-800">{value ?? '—'}</span>
    </div>
  );
}
