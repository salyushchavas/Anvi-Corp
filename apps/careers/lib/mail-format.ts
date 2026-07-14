// Pure presentation helpers for the mail client (S8 redesign). No I/O, no data
// shapes — formatting + avatar derivation only, so they never affect data flow.

/** Up-to-two-letter initials from a display name (falls back to "?"). */
export function initials(name?: string | null): string {
  const s = (name ?? '').trim();
  if (!s) return '?';
  const parts = s.split(/\s+/);
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/** Deterministic on-brand avatar tint (brand/slate tokens only — no new colors). */
export function avatarTint(name?: string | null): string {
  const TINTS = [
    'bg-brand-100 text-brand-800',
    'bg-slate-200 text-slate-700',
    'bg-brand-50 text-brand-700',
    'bg-slate-100 text-slate-600',
  ];
  const s = (name ?? '').trim();
  if (!s) return TINTS[1];
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return TINTS[h % TINTS.length];
}

/** Gmail-style list date: today → time, this year → "Mon D", older → M/D/YY. */
export function listDate(iso: string): string {
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '';
    const now = new Date();
    if (d.toDateString() === now.toDateString()) {
      return d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
    }
    if (d.getFullYear() === now.getFullYear()) {
      return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
    }
    return d.toLocaleDateString(undefined, { year: '2-digit', month: 'numeric', day: 'numeric' });
  } catch {
    return '';
  }
}

/** Full timestamp for the reading-pane header. */
export function fullDateTime(iso: string): string {
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '';
    return d.toLocaleString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
    });
  } catch {
    return '';
  }
}
