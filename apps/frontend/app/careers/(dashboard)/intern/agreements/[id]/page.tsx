import { redirect } from 'next/navigation';

/**
 * Pipeline-restore — redirect old per-document deep links (from
 * notification emails predating the rename) to the offer-letter
 * pipeline. The fill/sign UI is doc-shape-agnostic, so the same
 * component serves both offer-family and any legacy IDMS instances
 * that still hit this URL.
 */
export default function InternAgreementDetailRedirect(
  { params }: { params: { id: string } },
) {
  redirect(`/careers/intern/offer-letter/${params.id}`);
}
