'use client';

import { useMemo, useRef, useState } from 'react';
import { Check, Search, X } from 'lucide-react';

export interface ComboInternOption {
  candidateId: string;
  name: string;
  email?: string | null;
  githubUsername?: string | null;
  position?: string | null;
}

interface Props {
  options: ComboInternOption[];
  selectedIds: string[];
  onChange: (next: string[]) => void;
  placeholder?: string;
}

function initials(name?: string | null): string {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/**
 * Searchable multi-select for picking interns. Filters by name / email /
 * GitHub username. Shows selected interns as removable pills above the input.
 * Highlights missing GitHub usernames in red so the TE notices missing data
 * before allocating.
 */
export default function InternComboBox({
  options,
  selectedIds,
  onChange,
  placeholder = 'Search by name, email, or GitHub username…',
}: Props) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const selected = useMemo(
    () => options.filter((o) => selectedIds.includes(o.candidateId)),
    [options, selectedIds],
  );

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return options;
    return options.filter((o) =>
      [o.name, o.email ?? '', o.githubUsername ?? '']
        .some((s) => s.toLowerCase().includes(q)),
    );
  }, [options, query]);

  function toggle(id: string) {
    if (selectedIds.includes(id)) {
      onChange(selectedIds.filter((x) => x !== id));
    } else {
      onChange([...selectedIds, id]);
    }
  }

  function remove(id: string) {
    onChange(selectedIds.filter((x) => x !== id));
  }

  return (
    <div className="relative">
      {selected.length > 0 && (
        <div className="mb-2 flex flex-wrap gap-1.5" aria-label="Selected interns">
          {selected.map((o) => (
            <span
              key={o.candidateId}
              className="inline-flex items-center gap-1 rounded-full bg-accent/10 px-2 py-1 text-xs font-medium text-accent-dark"
            >
              {o.name}
              <button
                type="button"
                onClick={() => remove(o.candidateId)}
                aria-label={`Remove ${o.name}`}
                className="rounded-full p-0.5 hover:bg-accent/20"
              >
                <X className="h-3 w-3" strokeWidth={2.5} />
              </button>
            </span>
          ))}
        </div>
      )}
      <div className="relative">
        <Search
          className="pointer-events-none absolute left-2 top-2.5 h-4 w-4 text-slate-400"
          strokeWidth={2}
        />
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setOpen(true);
          }}
          onFocus={() => setOpen(true)}
          placeholder={placeholder}
          className="w-full rounded-md border border-slate-300 py-2 pl-8 pr-3 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          aria-expanded={open}
          role="combobox"
          aria-controls="intern-combo-list"
          aria-autocomplete="list"
        />
      </div>
      {open && (
        <ul
          id="intern-combo-list"
          role="listbox"
          className="absolute z-10 mt-1 max-h-72 w-full overflow-auto rounded-md border border-slate-200 bg-white py-1 shadow-lg"
        >
          {filtered.length === 0 ? (
            <li className="px-3 py-2 text-xs text-slate-500">No interns match your search.</li>
          ) : (
            filtered.map((o) => {
              const isSelected = selectedIds.includes(o.candidateId);
              return (
                <li key={o.candidateId} role="option" aria-selected={isSelected}>
                  <button
                    type="button"
                    onClick={() => toggle(o.candidateId)}
                    className={
                      'flex w-full items-center gap-3 px-3 py-2 text-left text-sm hover:bg-slate-50 '
                      + (isSelected ? 'bg-accent/5' : '')
                    }
                  >
                    <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-accent/15 text-[10px] font-semibold text-accent-dark">
                      {initials(o.name)}
                    </div>
                    <div className="min-w-0 flex-1">
                      <p className="truncate font-medium text-slate-900">{o.name}</p>
                      <p className="truncate text-[11px] text-slate-500">
                        {o.email ?? '—'}
                        {' · '}
                        <span className={o.githubUsername ? '' : 'text-red-700'}>
                          {o.githubUsername ?? 'GitHub username missing'}
                        </span>
                      </p>
                    </div>
                    {isSelected && (
                      <Check className="h-4 w-4 shrink-0 text-accent" strokeWidth={2.5} />
                    )}
                  </button>
                </li>
              );
            })
          )}
        </ul>
      )}
    </div>
  );
}
