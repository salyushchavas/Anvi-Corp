import type { Metadata } from 'next';
import Link from 'next/link';
import { fetchOpenJobPostings } from '@/lib/server-api';
import AdaptiveCareersLayout from '@/components/careers/AdaptiveCareersLayout';
import OpeningsSplit from '@/components/careers/OpeningsSplit';

export const metadata: Metadata = {
  title: 'Open Internships — Skyzen Careers',
  description:
    'Browse open STEM internship positions at Skyzen Technologies. Apply directly through Skyzen Careers.',
};

export const dynamic = 'force-dynamic';

export default async function OpeningsPage() {
  let page;
  let loadError = false;
  try {
    page = await fetchOpenJobPostings(0, 50);
  } catch {
    loadError = true;
  }

  const postings = page?.content ?? [];

  return (
    <AdaptiveCareersLayout title="Open Internships">
      <section>
        <div className="mb-8">
          <h1 className="mb-2 text-3xl font-semibold text-slate-900">Open Internships</h1>
          <p className="text-sm text-slate-600">
            Join the Skyzen Careers STEM internship program.
          </p>
        </div>

        {loadError ? (
          <div className="rounded-lg border border-red-200 bg-red-50 p-6 text-sm text-red-700">
            <p className="font-medium">Couldn&apos;t load openings.</p>
            <p className="mt-1">
              The backend may be starting up. Refresh in a few seconds or{' '}
              <Link href="/careers/openings" className="underline">
                try again
              </Link>
              .
            </p>
          </div>
        ) : (
          // OpeningsSplit handles its own empty state. For authenticated
          // candidates it refetches client-side to layer in the applied flags
          // and splits into "Your applications" + "Available internships".
          <OpeningsSplit initialPostings={postings} />
        )}
      </section>
    </AdaptiveCareersLayout>
  );
}
