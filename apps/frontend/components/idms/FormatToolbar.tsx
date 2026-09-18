'use client';

import { useEffect, useRef, useState } from 'react';
import {
  AlignCenter,
  AlignJustify,
  AlignLeft,
  AlignRight,
  ChevronDown,
  Minus,
  Pilcrow,
  Plus,
  RotateCcw,
  Ruler,
  Type,
} from 'lucide-react';
import {
  LINE_SPACING_PRESETS,
  PAGE_MARGIN_PRESETS,
  computedLineRatio,
  describeTarget,
  formatLength,
  isSimpleLength,
  readComputed,
  readInlineDeclaration,
  readPageMargins,
  toInches,
  toPoints,
  type FormatTarget,
  type PageMargins,
} from '@/lib/careers/studio-format';

/**
 * Formatting toolbar for the Editable Documents studio.
 *
 * <p>A persistent strip at the top of the canvas — NOT a floating
 * popover. That placement is deliberate: the existing "Make field"
 * selection floater is absolutely positioned against the selection's
 * client rect, so anything else anchored to the selection would collide
 * with it. A fixed strip occupies its own row, is always visible, and
 * never competes for the same pixels.</p>
 *
 * <p>Every control writes exactly one inline declaration on the element
 * the parent resolved; the toolbar itself holds no formatting state —
 * it reads current values straight off the live DOM on each render
 * (keyed by the parent's {@code version} counter, bumped after every
 * write). That keeps the DOM the single source of truth, which matters
 * because the DOM is literally what gets serialised and saved.</p>
 *
 * <p>FORMATTING ONLY — nothing here edits text or enables
 * contentEditable.</p>
 */
export function FormatToolbar({
  target,
  canvas,
  version,
  disabled,
  onSetParagraph,
  onSetFontSize,
  onSetPageMargins,
  onClearPageMargins,
  onReset,
  onSectionFocus,
}: {
  target: FormatTarget;
  canvas: HTMLElement | null;
  /** Bumped by the parent after every write so this component re-reads
   *  the DOM. Not otherwise used. */
  version: number;
  disabled: boolean;
  onSetParagraph: (prop: string, value: string | null) => void;
  onSetFontSize: (value: string | null, wholeParagraph: boolean) => void;
  onSetPageMargins: (margins: PageMargins) => void;
  onClearPageMargins: () => void;
  onReset: () => void;
  /** Tells the parent to move the highlight onto the first page section
   *  while the margins popover is open, then back again. */
  onSectionFocus: (focused: boolean) => void;
}) {
  const hasTarget = Boolean(target.paragraph);
  const active = !disabled && hasTarget;

  // Current values, read fresh from the DOM. `version` participates so
  // the read re-runs after every write even though the DOM mutation
  // itself isn't React state.
  void version;
  const p = target.paragraph;
  const align = readInlineDeclaration(p, 'text-align') || readComputed(p, 'text-align');
  const lineInline = readInlineDeclaration(p, 'line-height');
  const lineRatio = lineInline || (computedLineRatio(p)?.toString() ?? '');

  return (
    <div
      className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-slate-100 bg-slate-50/70 px-4 py-2"
      onMouseDown={(e) => {
        // Keep the canvas text selection alive when the admin clicks a
        // toolbar button. Browsers collapse the selection on mousedown
        // elsewhere, which would make the "Make field" floater vanish
        // mid-interaction and drop the range wrapSelection() needs.
        //
        // Form controls are EXEMPT: preventDefault on their mousedown
        // blocks focus outright, which would leave the page-margin
        // inputs untypeable and stop the line-spacing select from
        // opening.
        const el = e.target as HTMLElement | null;
        if (el?.closest('input, select, textarea')) return;
        e.preventDefault();
      }}
    >
      {/* Active target — so the admin can confirm WHAT is about to
          change before they change it. */}
      <div className="inline-flex min-w-0 items-center gap-1.5 text-xs">
        <Pilcrow className="h-3.5 w-3.5 shrink-0 text-slate-400" />
        <span
          className={`truncate ${active ? 'text-slate-700' : 'text-slate-400'}`}
          title={active ? describeTarget(target) : undefined}
          style={{ maxWidth: '13rem' }}
        >
          {disabled
            ? 'Switch to Edit mode to format'
            : hasTarget
              ? describeTarget(target)
              : 'Click a paragraph to format it'}
        </span>
      </div>

      <Divider />

      {/* Line spacing — unitless ratio on the paragraph. */}
      <Group label="Line">
        <select
          value={LINE_SPACING_PRESETS.includes(lineRatio as never) ? lineRatio : ''}
          disabled={!active}
          onChange={(e) =>
            onSetParagraph('line-height', e.target.value || null)
          }
          className={selectClass}
          title="Line spacing (line-height) on the selected paragraph"
        >
          <option value="">
            {lineRatio ? `${lineRatio}×` : 'from source'}
          </option>
          {LINE_SPACING_PRESETS.map((v) => (
            <option key={v} value={v}>
              {v}
            </option>
          ))}
        </select>
      </Group>

      <Divider />

      {/* Paragraph spacing — pt on margin-top / margin-bottom. */}
      <Group label="Space">
        <Stepper
          ariaLabel="Space before paragraph"
          title="Space BEFORE the paragraph (margin-top, in points)"
          value={toPoints(readInlineDeclaration(p, 'margin-top'))}
          fallback={toPoints(readComputed(p, 'margin-top'))}
          suffix="pt"
          step={2}
          min={0}
          max={144}
          disabled={!active}
          onChange={(n) =>
            onSetParagraph('margin-top', n === null ? null : formatLength(n, 'pt'))
          }
        />
        <Stepper
          ariaLabel="Space after paragraph"
          title="Space AFTER the paragraph (margin-bottom, in points)"
          value={toPoints(readInlineDeclaration(p, 'margin-bottom'))}
          fallback={toPoints(readComputed(p, 'margin-bottom'))}
          suffix="pt"
          step={2}
          min={0}
          max={144}
          disabled={!active}
          onChange={(n) =>
            onSetParagraph('margin-bottom', n === null ? null : formatLength(n, 'pt'))
          }
        />
      </Group>

      <Divider />

      {/* Alignment — text-align on the paragraph. */}
      <Group label="Align">
        <div className="inline-flex overflow-hidden rounded border border-slate-200 bg-white">
          {(
            [
              ['left', AlignLeft, 'Align left'],
              ['center', AlignCenter, 'Center'],
              ['right', AlignRight, 'Align right'],
              ['justify', AlignJustify, 'Justify'],
            ] as const
          ).map(([value, Icon, label]) => (
            <button
              key={value}
              type="button"
              disabled={!active}
              title={label}
              aria-label={label}
              aria-pressed={align === value}
              onClick={() =>
                // Clicking the active alignment clears it, returning the
                // paragraph to whatever the source document said.
                onSetParagraph('text-align', align === value ? null : value)
              }
              className={`px-1.5 py-1 transition disabled:opacity-40 ${
                align === value
                  ? 'bg-brand-700 text-white'
                  : 'text-slate-600 hover:bg-slate-100'
              }`}
            >
              <Icon className="h-3.5 w-3.5" />
            </button>
          ))}
        </div>
      </Group>

      <Divider />

      {/* Indentation — margin-left (whole block) + text-indent (first
          line only; a negative value is Word's hanging indent). */}
      <Group label="Indent">
        <Stepper
          ariaLabel="Left indent"
          title="Left indent for the whole paragraph (margin-left, in inches)"
          value={toInches(readInlineDeclaration(p, 'margin-left'))}
          fallback={toInches(readComputed(p, 'margin-left'))}
          suffix={'"'}
          step={0.25}
          min={-2}
          max={6}
          decimals={2}
          disabled={!active}
          onChange={(n) =>
            onSetParagraph('margin-left', n === null ? null : formatLength(n, 'in'))
          }
        />
        <Stepper
          ariaLabel="First-line indent"
          title="First-line indent (text-indent, in inches). Negative = hanging indent."
          value={toInches(readInlineDeclaration(p, 'text-indent'))}
          fallback={toInches(readComputed(p, 'text-indent'))}
          suffix={'"'}
          step={0.25}
          min={-2}
          max={6}
          decimals={2}
          disabled={!active}
          onChange={(n) =>
            onSetParagraph('text-indent', n === null ? null : formatLength(n, 'in'))
          }
        />
      </Group>

      <Divider />

      {/* Font size — on the resolved run, or the whole paragraph. */}
      <FontSizeControl
        target={target}
        active={active}
        onSetFontSize={onSetFontSize}
      />

      <Divider />

      <PageMarginsControl
        canvas={canvas}
        disabled={disabled}
        onApply={onSetPageMargins}
        onClear={onClearPageMargins}
        onOpenChange={onSectionFocus}
      />

      <div className="ml-auto">
        <button
          type="button"
          disabled={!active}
          onClick={onReset}
          title="Clear the formatting this toolbar applied to the selected paragraph — line spacing, spacing, alignment, indent and font size. Page margins are not affected."
          className="inline-flex items-center gap-1 rounded border border-slate-200 bg-white px-2 py-1 text-xs font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-40"
        >
          <RotateCcw className="h-3.5 w-3.5" />
          Reset
        </button>
      </div>
    </div>
  );
}

// ── Font size ─────────────────────────────────────────────────────────

function FontSizeControl({
  target,
  active,
  onSetFontSize,
}: {
  target: FormatTarget;
  active: boolean;
  onSetFontSize: (value: string | null, wholeParagraph: boolean) => void;
}) {
  // "Whole ¶" is meaningful only when the resolved run is narrower than
  // the paragraph; when they're the same element the write is already
  // paragraph-wide.
  const runIsParagraph = target.run === target.paragraph;
  const [whole, setWhole] = useState(false);
  const el = whole || runIsParagraph ? target.paragraph : target.run;
  const current =
    toPoints(readInlineDeclaration(el, 'font-size')) ??
    toPoints(readComputed(el, 'font-size'));

  return (
    <Group label="Size">
      <Stepper
        ariaLabel="Font size"
        title={
          whole || runIsParagraph
            ? 'Font size for the whole paragraph (font-size, in points)'
            : 'Font size for the selected run (font-size, in points)'
        }
        value={toPoints(readInlineDeclaration(el, 'font-size'))}
        fallback={current}
        suffix="pt"
        step={1}
        min={6}
        max={96}
        disabled={!active}
        icon={<Type className="h-3 w-3 text-slate-400" />}
        onChange={(n) =>
          onSetFontSize(
            n === null ? null : formatLength(n, 'pt'),
            whole || runIsParagraph,
          )
        }
      />
      {!runIsParagraph && (
        <button
          type="button"
          disabled={!active}
          aria-pressed={whole}
          onClick={() => setWhole((v) => !v)}
          title="Apply the size to every run in the paragraph instead of just the selected one"
          className={`rounded border px-1.5 py-1 text-[10px] font-semibold transition disabled:opacity-40 ${
            whole
              ? 'border-brand-700 bg-brand-700 text-white'
              : 'border-slate-200 bg-white text-slate-500 hover:bg-slate-100'
          }`}
        >
          whole ¶
        </button>
      )}
    </Group>
  );
}

// ── Page margins ──────────────────────────────────────────────────────

/**
 * Page margins write the FIRST section.docx's four padding longhands —
 * that is exactly what {@code preparePageGeometry} scrapes into the
 * PDF's {@code @page margin}. All four are always written together and
 * each must be a SIMPLE_LENGTH token, because a partial or unparseable
 * set makes the renderer discard the whole thing and fall back to its
 * defaults (i.e. the admin's edit would silently not appear).
 */
function PageMarginsControl({
  canvas,
  disabled,
  onApply,
  onClear,
  onOpenChange,
}: {
  canvas: HTMLElement | null;
  disabled: boolean;
  onApply: (m: PageMargins) => void;
  onClear: () => void;
  onOpenChange: (open: boolean) => void;
}) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const section = canvas?.querySelector('section.docx') as HTMLElement | null;
  const current = readPageMargins(section);
  const hasSection = Boolean(section);

  // Draft is local so a half-typed value never reaches the DOM — only a
  // complete, valid four-value set is ever written.
  const [draft, setDraft] = useState<PageMargins>(
    current ?? { top: '1in', right: '1in', bottom: '1in', left: '1in' },
  );
  useEffect(() => {
    if (open) {
      setDraft(current ?? { top: '1in', right: '1in', bottom: '1in', left: '1in' });
    }
    // Re-seed from the DOM each time the popover opens.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    onOpenChange(open);
  }, [open, onOpenChange]);

  useEffect(() => {
    if (!open) return;
    function onDocMouseDown(e: MouseEvent) {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false);
    }
    function onEsc(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false);
    }
    document.addEventListener('mousedown', onDocMouseDown);
    document.addEventListener('keydown', onEsc);
    return () => {
      document.removeEventListener('mousedown', onDocMouseDown);
      document.removeEventListener('keydown', onEsc);
    };
  }, [open]);

  const allValid = (['top', 'right', 'bottom', 'left'] as const).every((k) =>
    isSimpleLength(draft[k]),
  );

  return (
    <div className="relative" ref={wrapRef}>
      <button
        type="button"
        disabled={disabled || !hasSection}
        onClick={() => setOpen((v) => !v)}
        title={
          hasSection
            ? 'Page margins for the whole document (@page margin in the executed PDF)'
            : 'This template has no page section — margins fall back to the renderer default'
        }
        className="inline-flex items-center gap-1 rounded border border-slate-200 bg-white px-2 py-1 text-xs font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-40"
      >
        <Ruler className="h-3.5 w-3.5" />
        Page margins
        <ChevronDown className="h-3 w-3" />
      </button>

      {open && (
        <div className="absolute left-0 top-full z-30 mt-1 w-72 rounded-md border border-slate-200 bg-white p-3 shadow-lg">
          <p className="text-[11px] leading-snug text-slate-500">
            Applies to the whole document. These four values become the
            executed PDF&apos;s page margin.
            {!current && (
              <span className="mt-1 block font-medium text-amber-700">
                Currently using the renderer&apos;s default margins.
              </span>
            )}
          </p>

          <div className="mt-2 grid grid-cols-2 gap-2">
            {(
              [
                ['top', 'Top'],
                ['right', 'Right'],
                ['bottom', 'Bottom'],
                ['left', 'Left'],
              ] as const
            ).map(([k, label]) => {
              const valid = isSimpleLength(draft[k]);
              return (
                <label key={k} className="block">
                  <span className="text-[10px] font-medium uppercase tracking-wide text-slate-500">
                    {label}
                  </span>
                  <input
                    value={draft[k]}
                    onChange={(e) =>
                      setDraft((d) => ({ ...d, [k]: e.target.value }))
                    }
                    className={`mt-0.5 w-full rounded border px-1.5 py-1 text-xs ${
                      valid
                        ? 'border-slate-200'
                        : 'border-red-300 bg-red-50 text-red-800'
                    }`}
                    placeholder="1in"
                  />
                </label>
              );
            })}
          </div>

          {!allValid && (
            <p className="mt-1.5 text-[10px] leading-snug text-red-700">
              Each value must be a number plus in / cm / mm / pt / px
              (e.g. <code>1in</code>, <code>0.75in</code>, <code>72pt</code>).
              The PDF renderer ignores anything else and reverts to its
              defaults, so the toolbar won&apos;t write an invalid set.
            </p>
          )}

          <div className="mt-2 flex flex-wrap gap-1">
            {PAGE_MARGIN_PRESETS.map((preset) => (
              <button
                key={preset.label}
                type="button"
                onClick={() => setDraft(preset.margins)}
                className="rounded border border-slate-200 px-1.5 py-0.5 text-[10px] font-medium text-slate-600 hover:bg-slate-50"
              >
                {preset.label}
              </button>
            ))}
          </div>

          <div className="mt-3 flex items-center justify-between gap-2">
            <button
              type="button"
              onClick={() => {
                onClear();
                setOpen(false);
              }}
              className="text-[11px] font-medium text-slate-500 hover:text-slate-800"
              title="Remove the page-margin override and use the renderer default"
            >
              Reset to default
            </button>
            <button
              type="button"
              disabled={!allValid}
              onClick={() => {
                onApply(draft);
                setOpen(false);
              }}
              className="rounded bg-brand-700 px-3 py-1 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-50"
            >
              Apply
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

// ── Primitives ────────────────────────────────────────────────────────

function Group({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="inline-flex items-center gap-1.5">
      <span className="text-[10px] font-semibold uppercase tracking-wide text-slate-400">
        {label}
      </span>
      {children}
    </div>
  );
}

function Divider() {
  return <span aria-hidden className="h-5 w-px bg-slate-200" />;
}

/**
 * Numeric stepper. `value` is the INLINE value (null when the element
 * carries none); `fallback` is the computed value used only as a muted
 * placeholder, so an untouched paragraph shows what it currently
 * renders at without the toolbar claiming to have set it. Stepping from
 * an unset value starts at the fallback, which is what an admin
 * expects — click + on a 6pt-computed margin and you get 8pt, not 2pt.
 */
function Stepper({
  ariaLabel,
  title,
  value,
  fallback,
  suffix,
  step,
  min,
  max,
  decimals = 0,
  disabled,
  icon,
  onChange,
}: {
  ariaLabel: string;
  title: string;
  value: number | null;
  fallback: number | null;
  suffix: string;
  step: number;
  min: number;
  max: number;
  decimals?: number;
  disabled: boolean;
  icon?: React.ReactNode;
  onChange: (n: number | null) => void;
}) {
  const shown = value ?? fallback;
  const isSet = value !== null;
  const round = (n: number) => {
    const f = 10 ** decimals;
    return Math.round(n * f) / f;
  };
  const bump = (delta: number) => {
    const base = shown ?? 0;
    const next = Math.min(max, Math.max(min, round(base + delta)));
    onChange(next);
  };
  return (
    <span
      className="inline-flex items-center overflow-hidden rounded border border-slate-200 bg-white"
      title={title}
    >
      <button
        type="button"
        disabled={disabled}
        aria-label={`${ariaLabel} decrease`}
        onClick={() => bump(-step)}
        className="px-1 py-1 text-slate-500 hover:bg-slate-100 disabled:opacity-40"
      >
        <Minus className="h-3 w-3" />
      </button>
      <span
        aria-label={ariaLabel}
        className={`inline-flex min-w-[3.2rem] items-center justify-center gap-0.5 px-1 text-xs tabular-nums ${
          isSet ? 'font-medium text-slate-800' : 'text-slate-400'
        }`}
      >
        {icon}
        {shown === null ? '—' : `${round(shown)}${suffix}`}
      </span>
      <button
        type="button"
        disabled={disabled}
        aria-label={`${ariaLabel} increase`}
        onClick={() => bump(step)}
        className="px-1 py-1 text-slate-500 hover:bg-slate-100 disabled:opacity-40"
      >
        <Plus className="h-3 w-3" />
      </button>
      {isSet && (
        <button
          type="button"
          disabled={disabled}
          aria-label={`${ariaLabel} clear`}
          title="Clear this value — revert to the source document's"
          onClick={() => onChange(null)}
          className="border-l border-slate-200 px-1 py-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700 disabled:opacity-40"
        >
          <RotateCcw className="h-3 w-3" />
        </button>
      )}
    </span>
  );
}

const selectClass =
  'rounded border border-slate-200 bg-white px-1.5 py-1 text-xs text-slate-700 disabled:opacity-40';
