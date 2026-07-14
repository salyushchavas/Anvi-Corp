'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
  AlertCircle,
  Building2,
  CalendarDays,
  CheckCircle2,
  Clock,
  FileSignature,
  Mail,
  Sparkles,
  XCircle,
} from 'lucide-react';
import api from '@/lib/careers/api';
import InternPageShell from '@/components/intern/InternPageShell';
import { useInternDashboard } from '@/components/intern/InternDashboardContext';
import { BRAND } from '@/lib/careers/brand';
import type { CandidateOfferResponse, OfferStatus } from '@/types';

const STATUS_PILL: Record<OfferStatus, string> = {
  DRAFT:    'bg-slate-100 text-slate-700',
  SENT:     'bg-amber-100 text-amber-800',
  SIGNED:   'bg-green-100 text-green-800',
  ACCEPTED: 'bg-green-100 text-green-800',
  VOIDED:   'bg-red-100 text-red-800',
  REVOKED:  'bg-red-100 text-red-800',
  EXPIRED:  'bg-slate-100 text-slate-600',
  DECLINED: 'bg-slate-100 text-slate-600',
};

export default function InternOfferPage() {
  const router = useRouter();
  const { refresh } = useInternDashboard();
  const [offer, setOffer] = useState<CandidateOfferResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<CandidateOfferResponse[]>('/api/v1/offers/mine');
      const list = res.data ?? [];
      const latest = [...list].sort((a, b) =>
        (b.sentAt ?? b.expiresAt ?? '').localeCompare(a.sentAt ?? a.expiresAt ?? ''))[0] ?? null;
      setOffer(latest);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load offer');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  if (loading && !offer) {
    return (
      <InternPageShell title="Offer Letter">
        <div className="h-48 animate-pulse rounded-lg bg-slate-50" aria-hidden />
      </InternPageShell>
    );
  }
  if (err) {
    return (
      <InternPageShell title="Offer Letter">
        <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">{err}</p>
      </InternPageShell>
    );
  }
  if (!offer) {
    return (
      <InternPageShell title="Offer Letter" subtitle="Your IDMS offer will appear here.">
        <p className="rounded-md border border-dashed border-slate-300 bg-white p-10 text-center text-sm text-slate-500">
          No offer letter yet. We'll notify you the moment ERM sends one.
        </p>
      </InternPageShell>
    );
  }

  function startSigning() {
    // Routes to the IDMS in-house signing page.
    if (!offer) return;
    router.push(`/careers/intern/offer/sign/${offer.id}`);
  }

  const subtitle = (
    <span className={'inline-flex rounded-full px-2.5 py-0.5 text-xs font-semibold ' + STATUS_PILL[offer.status]}>
      {offer.status}
    </span>
  );

  return (
    <InternPageShell title="Your Offer Letter" subtitle={subtitle}>
      {offer.status === 'SENT' && (
        <SentVariant
          offer={offer}
          onSign={startSigning}
        />
      )}
      {(offer.status === 'SIGNED' || offer.status === 'ACCEPTED') && (
        <SignedVariant offer={offer} />
      )}
      {(offer.status === 'VOIDED' || offer.status === 'REVOKED') && (
        <VoidedVariant offer={offer} />
      )}
      {offer.status === 'EXPIRED' && <ExpiredVariant offer={offer} />}
      {offer.status === 'DECLINED' && <DeclinedVariant offer={offer} />}
    </InternPageShell>
  );
}

// ── Variants ────────────────────────────────────────────────────────────────

function SentVariant({
  offer,
  onSign,
}: {
  offer: CandidateOfferResponse;
  onSign: () => void;
}) {
  const daysUntilExpiry = useMemo(() => {
    if (!offer.expiresAt) return null;
    const diff = new Date(offer.expiresAt).getTime() - Date.now();
    return Math.max(0, Math.ceil(diff / 86_400_000));
  }, [offer.expiresAt]);

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="inline-flex items-center gap-1.5 rounded-full bg-amber-100 px-2.5 py-0.5 text-[11px] font-semibold uppercase tracking-wide text-amber-800">
            <FileSignature className="h-3 w-3" strokeWidth={2.5} />
            Awaiting signature
          </div>
          <h2 className="mt-2 text-2xl font-semibold text-slate-900">
            {offer.roleTitle ?? offer.jobPostingTitle ?? 'Offer'}
          </h2>
          {offer.entityName && (
            <p className="mt-1 inline-flex items-center gap-1 text-sm text-slate-600">
              <Building2 className="h-3.5 w-3.5" /> {offer.entityName}
            </p>
          )}
        </div>
        {daysUntilExpiry !== null && (
          <span className={
            'rounded-full px-3 py-1 text-xs font-medium '
            + (daysUntilExpiry <= 2 ? 'bg-red-100 text-red-800' : 'bg-slate-100 text-slate-700')
          }>
            <Clock className="mr-1 inline h-3 w-3" />
            Expires in {daysUntilExpiry} day{daysUntilExpiry === 1 ? '' : 's'}
          </span>
        )}
      </div>

      <dl className="mt-5 grid gap-4 sm:grid-cols-2">
        <InfoCell icon={<CalendarDays className="h-4 w-4" />} label="Tentative start date"
          value={new Date(offer.startDate).toLocaleDateString()} />
        {offer.worksite && (
          <InfoCell icon={<Building2 className="h-4 w-4" />} label="Worksite" value={offer.worksite} />
        )}
        {offer.expectedHoursPerWeek && (
          <InfoCell icon={<Clock className="h-4 w-4" />} label="Expected hours"
            value={`${offer.expectedHoursPerWeek}h / week`} />
        )}
        {offer.compensationSummary && (
          <InfoCell icon={<Sparkles className="h-4 w-4" />} label="Compensation"
            value={offer.compensationSummary} />
        )}
      </dl>

      <div className="mt-6 flex flex-wrap items-center gap-3 border-t border-slate-100 pt-5">
        <button
          type="button"
          onClick={onSign}
          className="inline-flex items-center gap-2 rounded-md bg-brand-700 px-5 py-2.5 text-sm font-semibold text-white hover:bg-brand-800"
        >
          <FileSignature className="h-4 w-4" />
          Review and sign
        </button>
      </div>

      {(offer.createdByName || offer.createdByEmail) && (
        <ErmContactCard name={offer.createdByName} email={offer.createdByEmail} />
      )}
    </section>
  );
}

function SignedVariant({ offer }: { offer: CandidateOfferResponse }) {
  return (
    <section className="overflow-hidden rounded-lg border border-green-200 bg-white shadow-sm">
      <div className="bg-green-600 px-6 py-3 text-sm font-semibold text-white">
        <CheckCircle2 className="mr-2 inline h-4 w-4" strokeWidth={2.5} />
        Signed{offer.signedAt ? ` on ${new Date(offer.signedAt).toLocaleString()}` : ''}
      </div>
      <div className="p-6">
        <h2 className="text-2xl font-semibold text-slate-900">
          {offer.roleTitle ?? offer.jobPostingTitle ?? 'Offer'}
        </h2>
        {offer.entityName && (
          <p className="mt-1 text-sm text-slate-600">{offer.entityName}</p>
        )}

        {offer.employeeId && (
          <div className="mt-4 inline-flex items-center gap-2 rounded-md border border-green-200 bg-green-50 px-3 py-2 text-sm font-semibold text-green-900">
            <Sparkles className="h-4 w-4" strokeWidth={2.5} />
            Employee ID: <span className="font-mono">{offer.employeeId}</span>
          </div>
        )}

        <p className="mt-4 text-sm text-slate-700">
          Welcome to {BRAND.name}! Your onboarding documents will appear next.
        </p>

        <section className="mt-6 rounded-md border border-slate-200 bg-slate-50 p-4">
          <h3 className="text-sm font-semibold text-slate-900">What happens next</h3>
          <p className="mt-2 text-sm text-slate-700">
            ERM will assign your onboarding packet. Watch your dashboard — it
            unlocks the moment your packet is ready.
          </p>
        </section>
      </div>
    </section>
  );
}

function VoidedVariant({ offer }: { offer: CandidateOfferResponse }) {
  return (
    <section className="rounded-lg border border-red-200 bg-white p-6 shadow-sm">
      <div className="inline-flex items-center gap-1.5 rounded-full bg-red-100 px-2.5 py-0.5 text-[11px] font-semibold uppercase tracking-wide text-red-800">
        <XCircle className="h-3 w-3" strokeWidth={2.5} />
        Voided
      </div>
      <h2 className="mt-2 text-xl font-semibold text-slate-900">
        {offer.roleTitle ?? offer.jobPostingTitle ?? 'Offer'}
      </h2>
      <p className="mt-2 text-sm text-slate-600">
        This offer was voided{offer.voidedAt ? ` on ${new Date(offer.voidedAt).toLocaleDateString()}` : ''}.
      </p>
      {offer.voidedReason && (
        <div className="mt-3 rounded-md border border-slate-200 bg-slate-50 p-3 text-sm text-slate-700">
          {offer.voidedReason}
        </div>
      )}
      {(offer.createdByName || offer.createdByEmail) && (
        <ErmContactCard name={offer.createdByName} email={offer.createdByEmail} />
      )}
    </section>
  );
}

function ExpiredVariant({ offer }: { offer: CandidateOfferResponse }) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <div className="inline-flex items-center gap-1.5 rounded-full bg-slate-100 px-2.5 py-0.5 text-[11px] font-semibold uppercase tracking-wide text-slate-600">
        <AlertCircle className="h-3 w-3" strokeWidth={2.5} />
        Expired
      </div>
      <h2 className="mt-2 text-xl font-semibold text-slate-900">
        {offer.roleTitle ?? offer.jobPostingTitle ?? 'Offer'}
      </h2>
      <p className="mt-2 text-sm text-slate-600">
        The signing window for this offer has closed. Contact ERM if you'd like
        to discuss next steps.
      </p>
      {(offer.createdByName || offer.createdByEmail) && (
        <ErmContactCard name={offer.createdByName} email={offer.createdByEmail} />
      )}
    </section>
  );
}

function DeclinedVariant({ offer }: { offer: CandidateOfferResponse }) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <div className="inline-flex items-center gap-1.5 rounded-full bg-slate-100 px-2.5 py-0.5 text-[11px] font-semibold uppercase tracking-wide text-slate-600">
        Declined
      </div>
      <h2 className="mt-2 text-xl font-semibold text-slate-900">
        {offer.roleTitle ?? offer.jobPostingTitle ?? 'Offer'}
      </h2>
      <p className="mt-2 text-sm text-slate-600">
        You declined this offer. Other openings are posted regularly — check
        Job Postings if you'd like to apply again.
      </p>
    </section>
  );
}

function InfoCell({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="rounded-md border border-slate-200 bg-white p-3">
      <div className="flex items-center gap-1.5 text-xs uppercase tracking-wide text-slate-400">
        {icon}
        {label}
      </div>
      <div className="mt-1 text-sm font-medium text-slate-900">{value}</div>
    </div>
  );
}

function ErmContactCard({ name, email }: { name?: string; email?: string }) {
  return (
    <section className="mt-6 rounded-md border border-slate-200 bg-slate-50 p-4">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
        Questions? Contact ERM
      </h3>
      <div className="mt-2 text-sm text-slate-800">{name}</div>
      {email && (
        <a href={`mailto:${email}`} className="mt-0.5 inline-flex items-center gap-1 text-xs text-brand-700 hover:underline">
          <Mail className="h-3 w-3" />
          {email}
        </a>
      )}
    </section>
  );
}
