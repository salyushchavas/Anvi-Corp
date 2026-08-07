import { redirect } from 'next/navigation';

/**
 * Pipeline-restore — legacy offer landing (backed by the /api/v1/offers/mine
 * legacy offers table) is superseded by the IDMS-driven offer-letter
 * page. Redirect so nav/deep-links continue to land somewhere useful.
 *
 * <p>Note: the per-offer signing sub-route {@code /careers/intern/offer/sign/[id]}
 * is left in place — that flow signs the legacy {@code Offer} entity
 * and is still exercised by any pre-migration offer emails already in
 * intern inboxes. New offers ride the IDMS pipeline and land here
 * (then bounce to the offer-letter page).</p>
 */
export default function InternLegacyOfferRedirect() {
  redirect('/careers/intern/offer-letter');
}
