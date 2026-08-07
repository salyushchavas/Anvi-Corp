import { redirect } from 'next/navigation';

/**
 * Pipeline-restore — the intern "Agreements" surface has been retired
 * in favour of the offer-letter pipeline. This redirect keeps every
 * emailed / bookmarked deep link to {@code /careers/intern/agreements}
 * working: server-side 307 (temporary) so the browser preserves the
 * request method + doesn't cache the target hard.
 */
export default function InternAgreementsRedirect() {
  redirect('/careers/intern/offer-letter');
}
