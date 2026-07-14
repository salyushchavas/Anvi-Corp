'use client';

import { useEffect, useRef, useState } from 'react';
import { formatFull } from '@/lib/format-date';

interface Props {
  expiresAt: string | Date;
  variant?: 'inline' | 'large';
  onExpired?: () => void;
}

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

function urgencyClass(remaining: number, variant: 'inline' | 'large'): string {
  if (variant === 'large') {
    if (remaining <= 0) return 'border-amber-200 bg-amber-50 text-amber-900';
    if (remaining < HOUR) return 'border-red-200 bg-red-50 text-red-900';
    if (remaining < 2 * DAY) return 'border-amber-200 bg-amber-50 text-amber-900';
    return 'border-slate-300 bg-slate-100 text-slate-700';
  }
  if (remaining <= 0) return 'text-amber-700';
  if (remaining < HOUR) return 'text-red-700';
  if (remaining < 2 * DAY) return 'text-amber-700';
  return 'text-green-700';
}

function inlineLabel(remaining: number): string {
  if (remaining <= 0) return 'Expired';
  const days = Math.floor(remaining / DAY);
  const hours = Math.floor((remaining % DAY) / HOUR);
  const minutes = Math.floor((remaining % HOUR) / MINUTE);
  const seconds = Math.floor((remaining % MINUTE) / SECOND);
  if (days >= 1) return `${days}d ${hours}h left`;
  if (hours >= 1) return `${hours}h ${minutes}m left`;
  return `${minutes}m ${pad2(seconds)}s left`;
}

function largeLabel(remaining: number): { line: string; suffix: string } {
  if (remaining <= 0) return { line: 'Expired', suffix: '' };
  const days = Math.floor(remaining / DAY);
  const hours = Math.floor((remaining % DAY) / HOUR);
  const minutes = Math.floor((remaining % HOUR) / MINUTE);
  const seconds = Math.floor((remaining % MINUTE) / SECOND);
  if (days >= 1) {
    return {
      line: `${days} ${days === 1 ? 'DAY' : 'DAYS'} ${hours} ${hours === 1 ? 'HOUR' : 'HOURS'}`,
      suffix: '',
    };
  }
  if (hours >= 1) {
    return {
      line: `${hours} ${hours === 1 ? 'HOUR' : 'HOURS'} ${minutes} MIN`,
      suffix: '',
    };
  }
  return {
    line: `${minutes} MIN ${pad2(seconds)} SEC`,
    suffix: '',
  };
}

export default function OfferCountdown({
  expiresAt,
  variant = 'inline',
  onExpired,
}: Props) {
  const target = useRef(new Date(expiresAt).getTime());
  const firedExpired = useRef(false);
  const [remaining, setRemaining] = useState<number>(
    () => target.current - Date.now()
  );

  useEffect(() => {
    target.current = new Date(expiresAt).getTime();
    firedExpired.current = false;
    setRemaining(target.current - Date.now());
  }, [expiresAt]);

  useEffect(() => {
    const id = setInterval(() => {
      setRemaining(target.current - Date.now());
    }, 1000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    if (remaining <= 0 && !firedExpired.current) {
      firedExpired.current = true;
      onExpired?.();
    }
  }, [remaining, onExpired]);

  if (variant === 'large') {
    const { line } = largeLabel(remaining);
    return (
      <div
        className={
          'rounded-lg border p-4 ' + urgencyClass(remaining, 'large')
        }
      >
        <div className="text-xs font-semibold uppercase tracking-wide opacity-75">
          Time to respond
        </div>
        <div className="mt-1 text-2xl font-bold tabular-nums">{line}</div>
        {remaining > 0 && (
          <div className="mt-2 text-xs text-gray-500">
            until offer expires {formatFull(expiresAt)}
          </div>
        )}
        {remaining <= 0 && (
          <div className="mt-2 text-xs text-gray-500">
            Once expired, the offer can no longer be accepted.
          </div>
        )}
      </div>
    );
  }

  return (
    <span
      className={
        'inline-block text-sm font-medium tabular-nums ' +
        urgencyClass(remaining, 'inline')
      }
    >
      {inlineLabel(remaining)}
    </span>
  );
}
