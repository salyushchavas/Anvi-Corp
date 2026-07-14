'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import { ChevronLeft } from 'lucide-react';
import api from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import InternPageShell from '@/components/intern/InternPageShell';

interface Reply {
  id: string;
  authorUserId: string;
  body: string;
  createdAt: string;
}

interface Ticket {
  id: string;
  openerUserId: string;
  subject: string;
  body: string;
  category: string;
  priority: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  replies?: Reply[];
}

const STATUS_PILL: Record<string, string> = {
  OPEN: 'bg-amber-100 text-amber-800',
  IN_PROGRESS: 'bg-slate-100 text-slate-700',
  RESOLVED: 'bg-green-100 text-green-800',
  CLOSED: 'bg-slate-100 text-slate-600',
};

export default function TicketThreadPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const { user } = useAuth();
  const [ticket, setTicket] = useState<Ticket | null>(null);
  const [body, setBody] = useState('');
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<Ticket>(`/api/v1/support/tickets/${id}`);
      setTicket(res.data);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load ticket');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  async function sendReply() {
    if (body.trim().length < 5) { setErr('Reply must be at least 5 characters'); return; }
    setSubmitting(true);
    setErr(null);
    try {
      await api.post(`/api/v1/support/tickets/${id}/reply`, { body: body.trim() });
      setBody('');
      await load();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string; message?: string } } };
      setErr(ax.response?.data?.error
        ?? ax.response?.data?.message
        ?? (e instanceof Error ? e.message : 'Reply failed'));
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) {
    return (
      <InternPageShell title="Ticket">
        <div className="h-32 animate-pulse rounded-lg bg-slate-50" aria-hidden />
      </InternPageShell>
    );
  }
  if (err || !ticket) {
    return (
      <InternPageShell title="Ticket">
        <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          {err ?? 'Ticket not found'}
        </p>
      </InternPageShell>
    );
  }

  const closed = ticket.status === 'RESOLVED' || ticket.status === 'CLOSED';

  return (
    <InternPageShell
      title={ticket.subject}
      subtitle={
        <span className={'inline-flex rounded-full px-2 py-0.5 text-xs font-semibold ' + (STATUS_PILL[ticket.status] ?? 'bg-slate-100 text-slate-700')}>
          {ticket.status.replaceAll('_', ' ')}
        </span>
      }
    >
      <Link
        href="/careers/intern/help"
        className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
      >
        <ChevronLeft className="h-4 w-4" /> Back to Help
      </Link>

      <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <div className="text-xs text-slate-500">
          {ticket.category} · {ticket.priority} · Opened {new Date(ticket.createdAt).toLocaleString()}
        </div>
        <p className="mt-2 whitespace-pre-wrap text-sm text-slate-800">{ticket.body}</p>
      </section>

      {ticket.replies && ticket.replies.length > 0 && (
        <ul className="mt-4 space-y-3">
          {ticket.replies.map((r) => {
            const isMe = user && r.authorUserId === user.userId;
            return (
              <li key={r.id} className={'rounded-lg border p-4 ' + (isMe ? 'border-brand-200 bg-brand-50' : 'border-slate-200 bg-white')}>
                <div className="text-[11px] text-slate-500">
                  {isMe ? 'You' : 'Support'} · {new Date(r.createdAt).toLocaleString()}
                </div>
                <p className="mt-2 whitespace-pre-wrap text-sm text-slate-800">{r.body}</p>
              </li>
            );
          })}
        </ul>
      )}

      {!closed && (
        <section className="mt-6 rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
          <h3 className="text-sm font-semibold text-slate-900">Reply</h3>
          <textarea
            value={body}
            onChange={(e) => setBody(e.target.value)}
            rows={4}
            maxLength={5000}
            placeholder="Add to the conversation…"
            className="mt-2 w-full resize-y rounded-md border border-slate-200 bg-white px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
          />
          <div className="mt-3 flex justify-end">
            <button type="button" onClick={sendReply} disabled={submitting}
              className="rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60">
              {submitting ? 'Sending…' : 'Send reply'}
            </button>
          </div>
        </section>
      )}
    </InternPageShell>
  );
}
