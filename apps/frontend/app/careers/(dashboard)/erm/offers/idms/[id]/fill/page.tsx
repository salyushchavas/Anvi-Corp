'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { ArrowLeft, RefreshCw, Send } from 'lucide-react';
import toast from 'react-hot-toast';
import { AxiosError } from 'axios';
import api from '@/lib/careers/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import IdmsFillSurface, {
  type IdmsFillSurfaceConfig,
} from '@/components/idms/IdmsFillSurface';
import { useAuth } from '@/lib/careers/auth-context';
import {
  humanDate,
  type InstanceDetail,
  type ResyncTemplateResponse,
} from '@/lib/careers/idms';

/**
 * ERM IDMS fill page — thin wrapper over the shared
 * {@link IdmsFillSurface}. All state, effects, and layout live in the
 * surface; this file supplies only the role-scoped API bindings,
 * canEdit predicate, header chrome, and Send-action copy.
 *
 * <p>Any future change to the fill/sign UX (signature capture, font
 * matching, completeness gate, blocking-reason banner, autosave
 * plumbing, validate-on-click, in-preview signature slot behavior,
 * split-view layout) lives in {@link IdmsFillSurface} and reflects on
 * BOTH this page AND the intern offer-letter fill/agreements page
 * automatically — parity by construction.</p>
 */
export default function ErmFillPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout title="Fill document">
        <PageContent />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function PageContent() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const router = useRouter();
  const { user } = useAuth();

  // Config is memoised on the identity of the id + user's name so
  // IdmsFillSurface's effects (which depend on `resource`) don't
  // re-fire on every parent re-render.
  const config = useMemo<IdmsFillSurfaceConfig | null>(() => {
    if (!id) return null;
    const base = `/api/v1/erm/idms/${id}`;
    return {
      instanceId: id,
      role: 'ERM',
      signerName: user?.fullName ?? '',
      resource: {
        load: () => api.get<InstanceDetail>(base).then((r) => r.data),
        fill: (p) => api.post<InstanceDetail>(`${base}/fill`, p).then((r) => r.data),
        sign: (p) => api.post<InstanceDetail>(`${base}/sign`, p).then((r) => r.data),
        // ERM's /send returns 200 with no InstanceDetail body — we
        // return void so the surface knows to skip setDetail; the
        // onSuccess handler navigates away instead.
        transition: async (p) => {
          await api.post(`${base}/send`, p);
        },
      },
      // ERM edits only in DRAFT; every other status routes to the
      // {@link fullPageOverride} panel below.
      canEdit: (d) => d.status === 'DRAFT',
      fullPageOverride: (d) =>
        d.status === 'DRAFT' ? null : <NotDraftPanel id={d.id} />,
      header: {
        backLink: { href: '/careers/erm/offers', label: 'Back to cockpit' },
        subtitle: (d) => `For ${d.internName ?? '—'} · ${d.internEmail ?? '—'}`,
      },
      primaryAction: {
        label: 'Send to intern',
        icon: <Send className="h-4 w-4" />,
        successToast: 'Sent to intern.',
        confirmTitle: 'Send this document to the intern?',
        confirmDescription: "They'll be notified to fill and sign.",
        confirmLabel: 'Send',
        onSuccess: () => router.push(`/careers/erm/offers/idms/${id}`),
      },
      panelFooter: (d) => `Draft started ${humanDate(d.createdAt)} · autosaves as you type`,
      stalenessBanner: (d, onResynced) => (
        <TemplateStalenessBanner
          instanceId={d.id}
          onResynced={onResynced}
        />
      ),
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, user?.fullName]);

  if (!config) return null;
  return <IdmsFillSurface config={config} />;
}

/**
 * "Update to latest template" banner shown ONLY when the loaded draft
 * is stale (backend flipped {@code isTemplateStale} true because the
 * admin has edited the source template since this draft was
 * snapshotted). The surface renders this only for ERM + DRAFT +
 * stale, so we don't need to re-check those here.
 *
 * <p>Click → POST {@code /resync-template} → on success, hand the
 * refreshed instance back to the surface via {@code onResynced} so
 * the preview + values re-render; show a toast that names the
 * dropped fields if any (so the ERM doesn't discover a silently-
 * vanished entry on the doc they're about to send). On 409 (the
 * doc raced to non-DRAFT in another tab), tell the user + trigger
 * a full page navigation so the "not a draft anymore" panel renders
 * — leaving them on a dead button would be worse.</p>
 */
function TemplateStalenessBanner({
  instanceId,
  onResynced,
}: {
  instanceId: string;
  onResynced: (response: ResyncTemplateResponse) => void;
}) {
  const router = useRouter();
  const [pending, setPending] = useState(false);

  const onClick = async () => {
    if (pending) return;
    setPending(true);
    try {
      const { data } = await api.post<ResyncTemplateResponse>(
        `/api/v1/erm/idms/${instanceId}/resync-template`,
      );
      onResynced(data);
      const droppedCount =
        data.summary.droppedRemovedCount + data.summary.droppedTypeChangedCount;
      if (droppedCount === 0) {
        toast.success(
          'Draft updated to the latest template. Your entered details were kept.',
        );
      } else {
        // Name the REMOVED fields where possible — type-changed
        // drops are still visible in the refreshed form, so the
        // notice focuses on the fields the ERM might not otherwise
        // notice missing. Filter out nulls defensively (a legacy
        // value row may have had no fieldName snapshot).
        const removedNames = (data.summary.droppedRemovedFieldNames ?? [])
          .filter((n): n is string => Boolean(n));
        const namesClause = removedNames.length > 0
          ? `: ${removedNames.join(', ')}`
          : '';
        toast(
          `Draft updated. ${droppedCount} field(s) you'd filled were removed or `
            + `changed in the updated template${namesClause}. `
            + 'Please review before sending.',
          { duration: 8000, icon: '⚠️' },
        );
      }
    } catch (e) {
      const status = (e as AxiosError)?.response?.status;
      if (status === 409) {
        // The doc raced to a non-draft state (probably sent in
        // another tab). Tell the user + navigate to the detail
        // page which will render the correct state (verify /
        // revoke / history).
        toast.error(
          "This document can no longer be updated (it's no longer a draft).",
        );
        router.push(`/careers/erm/offers/idms/${instanceId}`);
      } else {
        toast.error("Couldn't update the template. Please try again.");
      }
    } finally {
      setPending(false);
    }
  };

  return (
    <div className="flex flex-wrap items-start gap-3 rounded-md border border-sky-200 bg-sky-50 p-3 text-xs text-sky-900">
      <RefreshCw className="mt-0.5 h-4 w-4 shrink-0" />
      <div className="min-w-0 flex-1 space-y-0.5">
        <p className="font-medium">
          The admin has updated this template.
        </p>
        <p className="text-sky-800">
          Update this draft to the latest version? Details you&apos;ve
          already entered are kept where the fields still exist.
        </p>
      </div>
      <button
        type="button"
        onClick={onClick}
        disabled={pending}
        className="inline-flex items-center gap-1.5 rounded-md border border-sky-300 bg-white px-3 py-1.5 text-xs font-medium text-sky-800 shadow-sm hover:bg-sky-100 disabled:cursor-not-allowed disabled:opacity-60"
      >
        <RefreshCw className={`h-3.5 w-3.5 ${pending ? 'animate-spin' : ''}`} />
        {pending ? 'Updating…' : 'Update to latest template'}
      </button>
    </div>
  );
}

/** ERM-only fallback when the loaded doc isn't in DRAFT anymore.
 *  Detail-page has the verify/revoke/history controls; this page
 *  is fill-only. */
function NotDraftPanel({ id }: { id: string }) {
  return (
    <div className="mx-auto max-w-3xl p-6 space-y-4">
      <Link
        href={`/careers/erm/offers/idms/${id}`}
        className="inline-flex items-center gap-1 text-xs text-slate-500 hover:text-slate-800"
      >
        <ArrowLeft className="h-3.5 w-3.5" />
        Back to document
      </Link>
      <div className="rounded-md border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
        <p className="font-medium">This document isn&apos;t in draft anymore.</p>
        <p className="mt-1 text-xs">Open the detail page to verify, revoke, or view its history.</p>
      </div>
    </div>
  );
}
