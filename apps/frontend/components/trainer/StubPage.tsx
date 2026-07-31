'use client';

/**
 * Trainer Phase 0 — uniform placeholder for the 9 sidebar destinations.
 * Each renders title + description + "Coming in Trainer Phase N" badge
 * so the doc-spec'd 9-item navigation is reachable end-to-end without
 * any business logic shipping yet.
 */
export default function StubPage({
  title,
  description,
}: {
  title: string;
  description: string;
  phase?: number;
}) {
  return (
    <div className="mx-auto max-w-3xl p-6">
      <div className="rounded-lg border border-dashed border-slate-300 bg-white p-8">
        <div className="mb-3 flex items-baseline justify-between">
          <h1 className="text-xl font-semibold text-slate-900">{title}</h1>
          <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-semibold text-amber-800">
            Coming soon
          </span>
        </div>
        <p className="text-sm text-slate-600">{description}</p>
        <p className="mt-4 text-[11px] text-slate-400">
          This feature is coming soon.
        </p>
      </div>
    </div>
  );
}
