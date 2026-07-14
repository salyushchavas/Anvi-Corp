'use client';

import * as Popover from '@radix-ui/react-popover';
import { Check, ChevronDown, Search, X } from 'lucide-react';
import { ReactNode, useMemo, useRef, useState } from 'react';
import { cn } from '@/lib/cn';

export interface ComboboxOption {
  value: string;
  label: string;
  description?: string;
  disabled?: boolean;
  disabledReason?: string;
  leftSlot?: ReactNode;
}

interface CommonProps {
  options: ComboboxOption[];
  placeholder?: string;
  searchable?: boolean;
  emptyMessage?: string;
  /** ARIA label for the trigger when there's no visible label. */
  ariaLabel?: string;
  className?: string;
  disabled?: boolean;
  renderOption?: (option: ComboboxOption) => ReactNode;
}

interface SingleProps extends CommonProps {
  multiple?: false;
  value: string | null;
  onChange: (next: string | null) => void;
}

interface MultipleProps extends CommonProps {
  multiple: true;
  value: string[];
  onChange: (next: string[]) => void;
}

export type ComboboxProps = SingleProps | MultipleProps;

export default function Combobox(props: ComboboxProps) {
  const {
    options,
    placeholder = 'Select…',
    searchable = true,
    emptyMessage = 'No matches.',
    ariaLabel,
    className,
    disabled,
    renderOption,
  } = props;
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  const isMultiple = props.multiple === true;
  const selectedValues = isMultiple ? props.value : props.value ? [props.value] : [];
  const selectedOptions = useMemo(
    () => options.filter((o) => selectedValues.includes(o.value)),
    [options, selectedValues],
  );

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return options;
    return options.filter((o) =>
      (o.label + ' ' + (o.description ?? '')).toLowerCase().includes(q),
    );
  }, [options, query]);

  function toggle(value: string) {
    if (isMultiple) {
      const next = selectedValues.includes(value)
        ? selectedValues.filter((v) => v !== value)
        : [...selectedValues, value];
      (props as MultipleProps).onChange(next);
    } else {
      (props as SingleProps).onChange(value === props.value ? null : value);
      setOpen(false);
    }
  }

  function clearSelection() {
    if (isMultiple) (props as MultipleProps).onChange([]);
    else (props as SingleProps).onChange(null);
  }

  function removeOne(value: string) {
    if (isMultiple) {
      (props as MultipleProps).onChange(selectedValues.filter((v) => v !== value));
    }
  }

  const triggerLabel =
    isMultiple
      ? selectedOptions.length > 0
        ? `${selectedOptions.length} selected`
        : placeholder
      : selectedOptions[0]?.label ?? placeholder;

  return (
    <div className={cn('w-full', className)}>
      {isMultiple && selectedOptions.length > 0 && (
        <div className="mb-1.5 flex flex-wrap gap-1.5">
          {selectedOptions.map((o) => (
            <span
              key={o.value}
              className="inline-flex items-center gap-1 rounded-full bg-brand-50 px-2 py-0.5 text-xs font-medium text-brand-700 ring-1 ring-brand-200"
            >
              {o.label}
              <button
                type="button"
                onClick={() => removeOne(o.value)}
                aria-label={`Remove ${o.label}`}
                className="rounded-full p-0.5 hover:bg-brand-100"
              >
                <X className="h-3 w-3" strokeWidth={2.5} />
              </button>
            </span>
          ))}
        </div>
      )}
      <Popover.Root open={open} onOpenChange={setOpen}>
        <Popover.Trigger asChild>
          <button
            type="button"
            disabled={disabled}
            aria-label={ariaLabel}
            className={cn(
              'flex h-10 w-full items-center justify-between gap-2 rounded-md border border-slate-300 bg-white px-3 text-sm text-slate-800 transition-colors',
              'hover:border-slate-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2',
              'disabled:cursor-not-allowed disabled:opacity-50',
              !selectedOptions.length && 'text-slate-500',
            )}
          >
            <span className="truncate">{triggerLabel}</span>
            <ChevronDown className="h-4 w-4 shrink-0 text-slate-400" strokeWidth={2} />
          </button>
        </Popover.Trigger>
        <Popover.Portal>
          <Popover.Content
            sideOffset={4}
            align="start"
            className="z-50 min-w-[var(--radix-popover-trigger-width)] max-w-md rounded-md border border-slate-200 bg-white shadow-ds-lg animate-fade-in"
            onOpenAutoFocus={(e) => {
              if (searchable) {
                e.preventDefault();
                requestAnimationFrame(() => inputRef.current?.focus());
              }
            }}
          >
            {searchable && (
              <div className="relative border-b border-slate-200 px-2">
                <Search className="pointer-events-none absolute left-2.5 top-2.5 h-4 w-4 text-slate-400" />
                <input
                  ref={inputRef}
                  type="text"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder="Search…"
                  className="w-full bg-transparent py-2 pl-7 pr-2 text-sm placeholder:text-slate-400 focus:outline-none"
                />
              </div>
            )}
            <ul role="listbox" className="max-h-72 overflow-auto py-1">
              {filtered.length === 0 ? (
                <li className="px-3 py-3 text-center text-xs text-slate-500">{emptyMessage}</li>
              ) : (
                filtered.map((o) => {
                  const selected = selectedValues.includes(o.value);
                  return (
                    <li key={o.value} role="option" aria-selected={selected}>
                      <button
                        type="button"
                        onClick={() => !o.disabled && toggle(o.value)}
                        disabled={o.disabled}
                        title={o.disabled ? o.disabledReason : undefined}
                        className={cn(
                          'flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm',
                          o.disabled
                            ? 'cursor-not-allowed text-slate-400'
                            : 'hover:bg-slate-50 text-slate-800',
                          selected && 'bg-brand-50/60',
                        )}
                      >
                        {o.leftSlot}
                        <div className="min-w-0 flex-1">
                          {renderOption ? (
                            renderOption(o)
                          ) : (
                            <>
                              <p className="truncate font-medium">{o.label}</p>
                              {o.description && (
                                <p className="truncate text-[11px] text-slate-500">
                                  {o.description}
                                </p>
                              )}
                              {o.disabled && o.disabledReason && (
                                <p className="truncate text-[11px] text-red-700">
                                  {o.disabledReason}
                                </p>
                              )}
                            </>
                          )}
                        </div>
                        {selected && (
                          <Check className="h-4 w-4 shrink-0 text-brand-700" strokeWidth={2.5} />
                        )}
                      </button>
                    </li>
                  );
                })
              )}
            </ul>
            {selectedValues.length > 0 && (
              <div className="border-t border-slate-200 px-2 py-1.5">
                <button
                  type="button"
                  onClick={clearSelection}
                  className="text-xs font-medium text-slate-500 hover:text-slate-700"
                >
                  Clear selection
                </button>
              </div>
            )}
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>
    </div>
  );
}
