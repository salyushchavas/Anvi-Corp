'use client';

import { useMemo } from 'react';
import { useParams } from 'next/navigation';
import { Download, MessageSquareWarning, Send } from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/careers/api';
import IdmsFillSurface, {
  type IdmsFillSurfaceConfig,
} from '@/components/idms/IdmsFillSurface';
import { useAuth } from '@/lib/careers/auth-context';
import {
  RETURN_REASONS,
  downloadIdmsFinalPdf,
  humanDate,
  stageToneClass,
  type InstanceDetail,
} from '@/lib/careers/idms';

/**
 * Intern offer-letter fill/sign page — thin wrapper over the shared
 * {@link IdmsFillSurface}. Identical orchestration to the ERM fill
 * page (same state / effects / handlers / split-view layout / inline
 * signature capture); only the intern-facing chrome differs (status
 * chip subtitle, RETURNED / REVOKED banners, Download-executed-copy
 * action, Submit button copy).
 *
 * <p>Renders content-only — ProtectedRoute (INTERN gate) +
 * DashboardLayout are supplied by {@code intern/layout.tsx}.</p>
 *
 * <p>Any future change to fill/sign UX lives in
 * {@link IdmsFillSurface} and reflects on both this page AND the ERM
 * fill page automatically — parity by construction.</p>
 */
export default function InternOfferLetterFillPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const { user } = useAuth();

  const config = useMemo<IdmsFillSurfaceConfig | null>(() => {
    if (!id) return null;
    const base = `/api/v1/intern/agreements/${id}`;
    return {
      instanceId: id,
      role: 'INTERN',
      signerName: user?.fullName ?? '',
      resource: {
        load: () => api.get<InstanceDetail>(base).then((r) => r.data),
        fill: (p) => api.post<InstanceDetail>(`${base}/fill`, p).then((r) => r.data),
        sign: (p) => api.post<InstanceDetail>(`${base}/sign`, p).then((r) => r.data),
        // Intern's /submit returns the updated detail so the surface
        // reflects the new (INTERN_SUBMITTED) state without a refetch.
        transition: (p) =>
          api.post<InstanceDetail>(`${base}/submit`, p).then((r) => r.data),
      },
      canEdit: (d) => d.status === 'SENT_TO_INTERN' || d.status === 'RETURNED',
      header: {
        backLink: { href: '/careers/intern/offer-letter', label: 'Back to offer letter' },
        subtitle: (d) => (
          <span
            className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${stageToneClass(externalStage(d.status))}`}
          >
            {externalStage(d.status)}
          </span>
        ),
        titleActions: (d) =>
          d.status === 'FINALIZED' && d.finalPdfUrl ? (
            <DownloadExecutedButton detail={d} />
          ) : null,
      },
      primaryAction: {
        label: 'Submit',
        icon: <Send className="h-4 w-4" />,
        successToast: 'Submitted to your ERM.',
        confirmTitle: 'Submit for verification?',
        confirmDescription:
          "Your ERM will get a notification to verify what you've signed. "
          + "You can't edit the document after submitting unless it comes back with corrections.",
        confirmLabel: 'Submit',
      },
      extraBanners: (d) => (
        <>
          {d.status === 'RETURNED' && d.returnReasonCode && (
            <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
              <p className="flex items-center gap-1.5 font-medium">
                <MessageSquareWarning className="h-4 w-4" />
                Your ERM asked for changes
              </p>
              <p className="mt-1 text-xs">
                Reason:{' '}
                {RETURN_REASONS.find((r) => r.code === d.returnReasonCode)?.label
                  ?? d.returnReasonCode}
              </p>
              {d.returnComments && (
                <p className="mt-1 text-xs">Notes: {d.returnComments}</p>
              )}
            </div>
          )}
          {d.status === 'REVOKED' && (
            <div className="rounded-md border border-slate-200 bg-slate-50 p-4 text-sm text-slate-700">
              <p className="font-medium">This document has been revoked.</p>
              <p className="mt-1 text-xs text-slate-500">
                {humanDate(d.revokedAt)}. Please reach out to your ERM if you have questions.
              </p>
              {d.revokeComments && (
                <p className="mt-2 rounded-md border border-slate-200 bg-white p-2 text-xs text-slate-700">
                  {d.revokeComments}
                </p>
              )}
            </div>
          )}
        </>
      ),
      panelFooter: () => 'Autosaves as you type.',
      containerMaxWidthClass: 'max-w-6xl',
      // Intern header has more chrome than ERM (status chip + optional
      // RETURNED / REVOKED banners); reserve extra vertical space so
      // the preview doesn't push below the fold in those states.
      previewMaxHeightClass: 'max-h-[calc(100vh-260px)]',
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, user?.fullName]);

  if (!config) return null;
  return <IdmsFillSurface config={config} />;
}

/** FINALIZED-state Download button in the header. Uses the shared
 *  downloadIdmsFinalPdf helper so the finalized PDF blob is fetched
 *  with the auth cookie/token attached. */
function DownloadExecutedButton({ detail }: { detail: InstanceDetail }) {
  return (
    <button
      type="button"
      onClick={async () => {
        try {
          await downloadIdmsFinalPdf(
            api, detail.finalPdfUrl!,
            (detail.templateTitle ?? 'Offer Letter') + '.pdf');
        } catch (e) {
          const ax = e as { response?: { data?: { error?: string } }; message?: string };
          toast.error(ax.response?.data?.error ?? ax.message ?? 'Download failed');
        }
      }}
      className="inline-flex items-center gap-1.5 rounded-md bg-slate-800 px-3 py-2 text-xs font-semibold text-white hover:bg-slate-900"
    >
      <Download className="h-3.5 w-3.5" />
      Download executed copy
    </button>
  );
}

function externalStage(status: string): string {
  switch (status) {
    case 'DRAFT':            return 'Being prepared';
    case 'SENT_TO_INTERN':   return 'Awaiting your signature';
    case 'INTERN_SUBMITTED': return 'Signed — verifying';
    case 'RETURNED':         return 'Returned';
    case 'VERIFIED':         return 'Signed — verifying';
    case 'FINALIZED':        return 'Executed';
    case 'REVOKED':          return 'Revoked';
    case 'SUPERSEDED':       return 'Replaced';
    default: return status;
  }
}
