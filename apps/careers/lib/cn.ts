/**
 * Tiny class-name joiner. Filters out falsy values (false / null /
 * undefined) so callers can write conditional classes inline without a
 * runtime dependency on clsx.
 */
export function cn(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ');
}
