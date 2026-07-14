'use client';

const STYLES: Record<string, string> = {
  COMPLETED: 'bg-green-100 text-green-800',
  RESIGNED: 'bg-amber-100 text-amber-800',
  TERMINATED: 'bg-red-100 text-red-800',
  EXTENDED: 'bg-slate-100 text-slate-700',
};

export default function ExitTypePill({ exitType }: { exitType: string }) {
  const cls = STYLES[exitType] ?? 'bg-slate-100 text-slate-700';
  return (
    <span className={'inline-flex rounded-full px-2 py-0.5 text-xs font-semibold ' + cls}>
      {exitType.charAt(0) + exitType.slice(1).toLowerCase()}
    </span>
  );
}
