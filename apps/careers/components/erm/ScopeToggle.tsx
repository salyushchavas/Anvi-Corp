'use client';

import type { ErmScope } from './ErmDashboardContext';

interface Props {
  scope: ErmScope;
  onChange: (s: ErmScope) => void;
}

export default function ScopeToggle({ scope, onChange }: Props) {
  const Btn = ({ value, label }: { value: ErmScope; label: string }) => {
    const active = scope === value;
    return (
      <button
        type="button"
        onClick={() => onChange(value)}
        className={
          'px-3 py-1.5 text-xs font-medium transition-colors ' +
          (active
            ? 'bg-brand-700 text-white'
            : 'bg-white text-slate-700 hover:bg-slate-50')
        }
      >
        {label}
      </button>
    );
  };
  return (
    <div className="inline-flex overflow-hidden rounded-md border border-slate-200">
      <Btn value="mine" label="My interns" />
      <div className="w-px bg-slate-200" />
      <Btn value="all" label="All interns" />
    </div>
  );
}
