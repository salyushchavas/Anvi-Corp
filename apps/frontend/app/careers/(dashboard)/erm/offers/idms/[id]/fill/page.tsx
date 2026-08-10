'use client';

import { useMemo } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { ArrowLeft, Send } from 'lucide-react';
import api from '@/lib/careers/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import IdmsFillSurface, {
  type IdmsFillSurfaceConfig,
} from '@/components/idms/IdmsFillSurface';
import { useAuth } from '@/lib/careers/auth-context';
import { humanDate, type InstanceDetail } from '@/lib/careers/idms';

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
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, user?.fullName]);

  if (!config) return null;
  return <IdmsFillSurface config={config} />;
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
