'use client';

import Link from 'next/link';
import { Video, Users } from 'lucide-react';
import type { TodayMeetingRow } from './types';

export default function TodayMeetingsCard({
  meetings,
}: {
  meetings: TodayMeetingRow[];
}) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white shadow-sm">
      <header className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
        <h3 className="text-sm font-semibold text-slate-900">
          Today's meetings
        </h3>
        <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-medium text-slate-600">
          {meetings.length}
        </span>
      </header>
      {meetings.length === 0 ? (
        <div className="px-4 py-8 text-center text-xs text-slate-500">
          No meetings scheduled today.
        </div>
      ) : (
        <ul className="divide-y divide-slate-100">
          {meetings.map((m) => {
            const t = m.scheduledFor
              ? new Date(m.scheduledFor).toLocaleTimeString([], {
                  hour: '2-digit',
                  minute: '2-digit',
                })
              : '—';
            const joinUrl = m.zoomStartUrl ?? m.zoomJoinUrl;
            return (
              <li
                key={m.meetingId}
                className="flex flex-wrap items-center gap-3 px-4 py-3"
              >
                <div className="flex flex-1 min-w-0 items-center gap-3">
                  <Users className="h-4 w-4 shrink-0 text-slate-400" strokeWidth={2} />
                  <div className="min-w-0">
                    <div className="text-sm font-medium text-slate-900">
                      {m.internName ?? '(unknown intern)'}
                    </div>
                    <div className="text-[11px] text-slate-500">
                      {t}
                      {m.durationMinutes ? ' · ' + m.durationMinutes + 'min' : ''}
                      {m.topic ? ' · ' + m.topic : ''}
                    </div>
                  </div>
                </div>
                {joinUrl ? (
                  <a
                    href={joinUrl}
                    target="_blank"
                    rel="noreferrer"
                    className="inline-flex items-center gap-1 rounded-md border border-brand-200 bg-brand-50 px-2.5 py-1 text-[11px] font-semibold text-brand-700 hover:bg-brand-100"
                  >
                    <Video className="h-3 w-3" strokeWidth={2} />
                    {m.zoomStartUrl ? 'Join (host)' : 'Join'}
                  </a>
                ) : (
                  <span className="text-[10px] text-slate-400">no link</span>
                )}
                <Link
                  href={`/careers/trainer/active-interns/${m.internLifecycleId}`}
                  className="text-[11px] text-slate-500 hover:text-slate-700"
                >
                  View →
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
