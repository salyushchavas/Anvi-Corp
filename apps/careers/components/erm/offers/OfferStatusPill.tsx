'use client';

import type { OfferStatus } from './types';

const STYLES: Record<OfferStatus, string> = {
  DRAFT: 'bg-slate-100 text-slate-600',
  SENT: 'bg-amber-100 text-amber-800',
  SIGNED: 'bg-green-100 text-green-800',
  VOIDED: 'bg-slate-200 text-slate-700',
  EXPIRED: 'bg-red-100 text-red-800',
  DECLINED: 'bg-red-100 text-red-800',
};

const LABEL: Record<OfferStatus, string> = {
  DRAFT: 'Draft',
  SENT: 'Sent',
  SIGNED: 'Signed',
  VOIDED: 'Voided',
  EXPIRED: 'Expired',
  DECLINED: 'Declined',
};

export default function OfferStatusPill({ status }: { status: OfferStatus }) {
  return (
    <span
      className={
        'inline-flex rounded-full px-2 py-0.5 text-[11px] font-semibold ' +
        STYLES[status]
      }
    >
      {LABEL[status]}
    </span>
  );
}
