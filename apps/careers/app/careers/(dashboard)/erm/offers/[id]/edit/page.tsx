'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { ArrowLeft } from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/api';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import ProtectedRoute from '@/components/ProtectedRoute';
import { todayDateInput } from '@/lib/format-date';
import type {
  CompensationFrequency,
  OfferResponse,
  UpdateOfferRequest,
} from '@/types';

const FREQ_OPTIONS: { value: CompensationFrequency; label: string }[] = [
  { value: 'HOURLY', label: 'Hourly' },
  { value: 'MONTHLY', label: 'Monthly' },
  { value: 'YEARLY', label: 'Yearly' },
];

export default function EditOfferPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM']}>
      <DashboardLayout title="Edit Offer">
        <Body />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Body() {
  const params = useParams();
  const router = useRouter();
  const id =
    typeof params?.id === 'string'
      ? params.id
      : Array.isArray(params?.id)
        ? params.id[0]
        : null;

  const [offer, setOffer] = useState<OfferResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Form state
  const [amount, setAmount] = useState('');
  const [frequency, setFrequency] = useState<CompensationFrequency>('MONTHLY');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [daysToRespond, setDaysToRespond] = useState(7);
  const [additionalTerms, setAdditionalTerms] = useState('');
  const [letterContent, setLetterContent] = useState('');
  const [letterDirty, setLetterDirty] = useState(false);

  const [submitting, setSubmitting] = useState<'save' | 'send' | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    setError(null);
    try {
      const res = await api.get<OfferResponse>(`/api/v1/offers/${id}`);
      const o = res.data;
      setOffer(o);
      setAmount(String(o.compensationAmount ?? ''));
      setFrequency(o.compensationFrequency);
      setStartDate(o.startDate ?? '');
      setEndDate(o.expectedEndDate ?? '');
      // daysToRespond isn't part of the response — estimate it from expiresAt - createdAt
      // (default 7 if the math comes out weird).
      const expires = new Date(o.expiresAt).getTime();
      const created = new Date(o.createdAt).getTime();
      const days = Number.isFinite(expires) && Number.isFinite(created)
        ? Math.max(1, Math.min(30, Math.round((expires - created) / 86_400_000)))
        : 7;
      setDaysToRespond(days);
      setAdditionalTerms(o.additionalTerms ?? '');
      setLetterContent(o.letterContent ?? '');
      setLetterDirty(false);
    } catch (err: any) {
      setError(err?.response?.data?.error ?? 'Could not load offer');
      setOffer(null);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  function validate(): boolean {
    const errs: Record<string, string> = {};
    const amountNum = Number(amount);
    if (!amount || !Number.isFinite(amountNum) || amountNum <= 0) {
      errs.amount = 'Enter a positive amount';
    }
    if (!startDate) errs.startDate = 'Pick a start date';
    if (endDate && new Date(endDate) <= new Date(startDate)) {
      errs.endDate = 'End date must be after the start date';
    }
    if (
      !Number.isInteger(daysToRespond) ||
      daysToRespond < 1 ||
      daysToRespond > 30
    ) {
      errs.daysToRespond = 'Pick a value between 1 and 30';
    }
    setFieldErrors(errs);
    return Object.keys(errs).length === 0;
  }

  function buildBody(): UpdateOfferRequest {
    if (!offer) return {} as UpdateOfferRequest;
    const body: UpdateOfferRequest = {
      compensationAmount: Number(amount),
      compensationFrequency: frequency,
      startDate,
      expectedEndDate: endDate || undefined,
      daysToRespond,
      additionalTerms: additionalTerms.trim() || undefined,
    };
    // Only send letterContent when the user explicitly edited it — otherwise
    // the backend regenerates from template when compensation/dates change.
    if (letterDirty) {
      body.letterContent = letterContent;
    }
    return body;
  }

  async function save(sendImmediately: boolean) {
    if (!offer) return;
    setFormError(null);
    if (!validate()) return;
    setSubmitting(sendImmediately ? 'send' : 'save');
    try {
      await api.put(`/api/v1/offers/${offer.id}`, buildBody());
      if (sendImmediately) {
        try {
          await api.post(`/api/v1/offers/${offer.id}/send`);
          toast.success('Offer saved and sent');
        } catch (sendErr: any) {
          toast.error(
            sendErr?.response?.data?.error ??
              'Saved but send failed — open the offer and try again'
          );
        }
      } else {
        toast.success('Changes saved');
      }
      router.push(`/careers/erm/offers/${offer.id}`);
    } catch (err: any) {
      const msg = err?.response?.data?.error ?? 'Could not save offer';
      setFormError(msg);
      toast.error(msg);
    } finally {
      setSubmitting(null);
    }
  }

  if (loading) return <EditSkeleton />;

  if (error && !offer) {
    return (
      <div className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-700">
        <p className="mb-2">{error}</p>
        <Link
          href="/careers/erm/offers"
          className="rounded border border-red-300 px-3 py-1 text-xs font-medium hover:bg-red-100"
        >
          Back to offers
        </Link>
      </div>
    );
  }

  if (!offer) return null;

  // Guard — only DRAFT offers are editable.
  if (offer.status !== 'DRAFT') {
    return (
      <div className="mx-auto max-w-2xl">
        <Link
          href={`/careers/erm/offers/${offer.id}`}
          className="mb-4 inline-flex items-center gap-1 text-sm text-gray-600 hover:text-gray-900"
        >
          <ArrowLeft className="h-4 w-4" strokeWidth={2} />
          Back to offer
        </Link>
        <div className="rounded-lg border border-amber-200 bg-amber-50 p-6">
          <h2 className="text-base font-semibold text-amber-900">
            This offer can no longer be edited
          </h2>
          <p className="mt-2 text-sm text-amber-800">
            Only DRAFT offers can be edited. This offer is currently in status{' '}
            <span className="font-semibold">{offer.status}</span>.
          </p>
          <div className="mt-4">
            <Link
              href={`/careers/erm/offers/${offer.id}`}
              className="inline-flex items-center gap-1.5 rounded-md bg-accent px-3 py-1.5 text-sm font-semibold text-white hover:bg-accent-dark"
            >
              View offer
            </Link>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-2xl">
      <Link
        href={`/careers/erm/offers/${offer.id}`}
        className="mb-4 inline-flex items-center gap-1 text-sm text-gray-600 hover:text-gray-900"
      >
        <ArrowLeft className="h-4 w-4" strokeWidth={2} />
        Back to offer
      </Link>

      <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
        {/* Read-only candidate context */}
        <div className="mb-6 rounded-md border border-gray-200 bg-gray-50 p-3 text-sm">
          <div className="font-medium text-gray-900">
            {offer.candidateName ?? '(unnamed)'}
          </div>
          {offer.candidateEmail && (
            <div className="text-xs text-gray-500">{offer.candidateEmail}</div>
          )}
          <div className="mt-1.5 text-xs text-gray-700">
            {offer.jobPostingTitle ?? '—'}
            {offer.entityName && (
              <>
                {' '}
                <span className="text-gray-400">·</span>{' '}
                <span>{offer.entityName}</span>
              </>
            )}
          </div>
        </div>

        <form className="space-y-6" onSubmit={(e) => e.preventDefault()}>
          {/* Amount */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Compensation amount <span className="text-red-500">*</span>
            </label>
            <div className="flex overflow-hidden rounded-md border border-gray-300 focus-within:border-primary-700 focus-within:ring-1 focus-within:ring-primary-700">
              <span className="flex items-center bg-gray-50 px-3 py-2 text-sm text-gray-500">
                $
              </span>
              <input
                type="number"
                min="0.01"
                step="0.01"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                className="flex-1 border-l border-gray-200 bg-white px-3 py-2 text-sm focus:outline-none"
              />
            </div>
            {fieldErrors.amount && (
              <p className="mt-1 text-xs text-red-600">{fieldErrors.amount}</p>
            )}
          </div>

          {/* Frequency */}
          <div>
            <label className="mb-2 block text-sm font-medium text-gray-700">
              Frequency <span className="text-red-500">*</span>
            </label>
            <div className="inline-flex rounded-md border border-gray-300 bg-white p-0.5">
              {FREQ_OPTIONS.map((opt) => {
                const active = frequency === opt.value;
                return (
                  <button
                    key={opt.value}
                    type="button"
                    onClick={() => setFrequency(opt.value)}
                    className={
                      'rounded px-3 py-1 text-xs font-medium transition-colors ' +
                      (active
                        ? 'bg-accent text-white'
                        : 'text-gray-600 hover:bg-gray-100')
                    }
                  >
                    {opt.label}
                  </button>
                );
              })}
            </div>
          </div>

          {/* Start date */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Internship start date <span className="text-red-500">*</span>
            </label>
            <input
              type="date"
              min={todayDateInput()}
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              className="rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-primary-700 focus:outline-none focus:ring-1 focus:ring-primary-700"
            />
            {fieldErrors.startDate && (
              <p className="mt-1 text-xs text-red-600">{fieldErrors.startDate}</p>
            )}
          </div>

          {/* End date */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Expected end date (optional)
            </label>
            <input
              type="date"
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
              className="rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-primary-700 focus:outline-none focus:ring-1 focus:ring-primary-700"
            />
            {fieldErrors.endDate && (
              <p className="mt-1 text-xs text-red-600">{fieldErrors.endDate}</p>
            )}
          </div>

          {/* Days to respond */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Days for candidate to respond <span className="text-red-500">*</span>
            </label>
            <input
              type="number"
              min={1}
              max={30}
              value={daysToRespond}
              onChange={(e) => setDaysToRespond(Number(e.target.value))}
              className="w-32 rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-primary-700 focus:outline-none focus:ring-1 focus:ring-primary-700"
            />
            {fieldErrors.daysToRespond && (
              <p className="mt-1 text-xs text-red-600">{fieldErrors.daysToRespond}</p>
            )}
          </div>

          {/* Additional terms */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Additional terms
            </label>
            <textarea
              value={additionalTerms}
              onChange={(e) => setAdditionalTerms(e.target.value)}
              rows={4}
              className="w-full resize-y rounded-md border border-gray-300 p-2.5 text-sm focus:border-primary-700 focus:outline-none focus:ring-1 focus:ring-primary-700"
            />
          </div>

          {/* Letter content */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Offer letter content
            </label>
            <p className="mb-2 text-xs text-gray-500">
              Edit the letter directly. Variable parts are already filled in based on
              the candidate and position. Will be auto-regenerated if you change
              compensation/dates above — unless you also explicitly edit this field.
            </p>
            <textarea
              value={letterContent}
              onChange={(e) => {
                setLetterContent(e.target.value);
                setLetterDirty(true);
              }}
              rows={12}
              className="w-full resize-y rounded-md border border-gray-300 p-2.5 font-mono text-xs leading-relaxed focus:border-primary-700 focus:outline-none focus:ring-1 focus:ring-primary-700"
            />
            {letterDirty && (
              <p className="mt-1 text-xs text-amber-700">
                Letter has been customized. It will be saved as-is and won&apos;t
                regenerate.
              </p>
            )}
          </div>

          {formError && (
            <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
              {formError}
            </div>
          )}

          <div className="flex flex-wrap items-center justify-end gap-2 pt-2">
            <Link
              href={`/careers/erm/offers/${offer.id}`}
              className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
            >
              Cancel
            </Link>
            <button
              type="button"
              onClick={() => void save(false)}
              disabled={submitting !== null}
              className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
            >
              {submitting === 'save' ? 'Saving…' : 'Save Changes'}
            </button>
            <button
              type="button"
              onClick={() => void save(true)}
              disabled={submitting !== null}
              className="rounded-md bg-accent px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-accent-dark disabled:opacity-50"
            >
              {submitting === 'send' ? 'Saving & Sending…' : 'Save & Send Immediately'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

function EditSkeleton() {
  return (
    <div className="mx-auto max-w-2xl space-y-4">
      <div className="h-5 w-32 animate-pulse rounded bg-gray-200" />
      <div className="space-y-3 rounded-lg border border-gray-200 bg-white p-6">
        <div className="h-12 w-full animate-pulse rounded bg-gray-200" />
        <div className="h-12 w-full animate-pulse rounded bg-gray-200" />
        <div className="h-12 w-full animate-pulse rounded bg-gray-200" />
        <div className="h-40 w-full animate-pulse rounded bg-gray-200" />
      </div>
    </div>
  );
}
