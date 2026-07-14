'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { ChevronDown, ChevronUp, Plus } from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';

interface SupportTicket {
  id: string;
  subject: string;
  category: string;
  priority: string;
  status: 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED';
  createdAt: string;
  updatedAt: string;
}

const STATUS_PILL: Record<string, string> = {
  OPEN: 'bg-amber-100 text-amber-800',
  IN_PROGRESS: 'bg-slate-100 text-slate-700',
  RESOLVED: 'bg-green-100 text-green-800',
  CLOSED: 'bg-slate-100 text-slate-600',
};

const FAQS: { q: string; a: string }[] = [
  { q: 'How do I verify my email?',
    a: 'Open Home and click Resend verification on the Next Action card. The link in the email expires in 24 hours.' },
  { q: 'When can I apply to a job?',
    a: 'Job Postings is open as soon as your email is verified. You can apply to multiple roles.' },
  { q: 'How do I prepare for an interview?',
    a: 'Test mic + camera 5 min before, join from a quiet space, and bring 2-3 questions about the role.' },
  { q: 'When does my offer expire?',
    a: 'Most offers expire 7 days from sending. Open the Offer Letter page to see your exact deadline.' },
  { q: 'How do I complete my onboarding?',
    a: 'Open Onboarding and complete each required item (W-4, I-9, ACH, Emergency Contact, Handbook). ERM reviews each.' },
  { q: 'How do I link my GitHub for projects?',
    a: 'Add your GitHub username in Profile settings. Your Trainer will invite you when they assign a project.' },
  { q: 'When do I submit my timesheet?',
    a: 'By end of day Sunday for the week that just ended. Evaluator or ERM approves; you can resubmit if rejected.' },
  { q: 'What if I miss a weekly meeting?',
    a: 'Message your Trainer in advance. Past meetings show up in Weekly Meetings; recordings (if any) come from your Trainer.' },
  { q: 'How are evaluations scored?',
    a: 'Five dimensions, 1-10 each: overall, technical, communication, professionalism, learning. Published evaluations are sent for your review.' },
  { q: 'How do I get support?',
    a: 'Open a support ticket below. We respond within 1 business day; URGENT tickets are escalated.' },
];

export default function InternHelpPage() {
  const [tickets, setTickets] = useState<SupportTicket[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [openFaq, setOpenFaq] = useState<number | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<SupportTicket[]>('/api/v1/support/tickets/mine');
      setTickets(res.data ?? []);
    } catch {
      // silent — empty state covers it
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  return (
    <InternPageShell title="Help & Support" subtitle="FAQs and a direct line to our team.">
      {/* FAQ */}
      <section className="mb-8 rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">Frequently asked questions</h2>
        <ul className="mt-3 space-y-1">
          {FAQS.map((f, i) => (
            <li key={i} className="border-t border-slate-100 first:border-t-0">
              <button
                type="button"
                onClick={() => setOpenFaq(openFaq === i ? null : i)}
                className="flex w-full items-center justify-between gap-3 py-3 text-left text-sm font-medium text-slate-800 hover:text-slate-900"
              >
                <span>{f.q}</span>
                {openFaq === i
                  ? <ChevronUp className="h-4 w-4 text-slate-400" />
                  : <ChevronDown className="h-4 w-4 text-slate-400" />}
              </button>
              {openFaq === i && (
                <p className="pb-3 text-sm text-slate-600">{f.a}</p>
              )}
            </li>
          ))}
        </ul>
      </section>

      {/* Tickets */}
      <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-semibold text-slate-900">Your support tickets</h2>
          <button
            type="button"
            onClick={() => setShowModal(true)}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800"
          >
            <Plus className="h-3 w-3" /> Open ticket
          </button>
        </div>
        {loading && (
          <div className="mt-4 h-16 animate-pulse rounded-md bg-slate-50" aria-hidden />
        )}
        {!loading && tickets.length === 0 && (
          <p className="mt-3 rounded-md border border-dashed border-slate-300 bg-slate-50 p-6 text-center text-sm text-slate-500">
            You haven't opened a support ticket yet.
          </p>
        )}
        {!loading && tickets.length > 0 && (
          <ul className="mt-3 space-y-2">
            {tickets.map((t) => (
              <li key={t.id}>
                <Link
                  href={`/careers/intern/help/tickets/${t.id}`}
                  className="flex items-center gap-3 rounded-md border border-slate-200 p-3 hover:bg-slate-50"
                >
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-medium text-slate-900">{t.subject}</div>
                    <div className="mt-0.5 text-[11px] text-slate-500">
                      {t.category} · {t.priority} · Updated {new Date(t.updatedAt).toLocaleDateString()}
                    </div>
                  </div>
                  <span className={'rounded-full px-2 py-0.5 text-[10px] font-semibold ' + (STATUS_PILL[t.status] ?? 'bg-slate-100 text-slate-700')}>
                    {t.status.replaceAll('_', ' ')}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>

      {showModal && (
        <NewTicketModal
          onClose={() => setShowModal(false)}
          onCreated={(id) => {
            setShowModal(false);
            void load();
            window.location.href = `/careers/intern/help/tickets/${id}`;
          }}
        />
      )}
    </InternPageShell>
  );
}

function NewTicketModal({ onClose, onCreated }: { onClose: () => void; onCreated: (id: string) => void }) {
  const [subject, setSubject] = useState('');
  const [category, setCategory] = useState('TECHNICAL');
  const [priority, setPriority] = useState('NORMAL');
  const [body, setBody] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    if (subject.trim().length < 5) { setErr('Subject must be at least 5 characters'); return; }
    if (body.trim().length < 30) { setErr('Description must be at least 30 characters'); return; }
    setSubmitting(true);
    setErr(null);
    try {
      const res = await api.post<{ id: string }>('/api/v1/support/tickets', {
        subject: subject.trim(),
        body: body.trim(),
        category,
        priority,
      });
      onCreated(res.data.id);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string; message?: string } } };
      setErr(ax.response?.data?.error
        ?? ax.response?.data?.message
        ?? (e instanceof Error ? e.message : 'Failed to open ticket'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="w-full max-w-lg rounded-lg bg-white p-6 shadow-xl">
        <h2 className="text-lg font-semibold text-slate-900">Open a support ticket</h2>
        <div className="mt-4 space-y-3">
          <label className="block">
            <span className="mb-1 block text-xs font-medium text-slate-700">Subject</span>
            <input
              type="text"
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
              maxLength={200}
              className="w-full rounded-md border border-slate-200 bg-white px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
            />
          </label>
          <div className="grid grid-cols-2 gap-3">
            <label className="block">
              <span className="mb-1 block text-xs font-medium text-slate-700">Category</span>
              <select value={category} onChange={(e) => setCategory(e.target.value)}
                className="w-full rounded-md border border-slate-200 bg-white px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
              >
                <option value="TECHNICAL">Technical</option>
                <option value="ACCOUNT">Account</option>
                <option value="ONBOARDING">Onboarding</option>
                <option value="PAYROLL">Payroll</option>
                <option value="OTHER">Other</option>
              </select>
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-medium text-slate-700">Priority</span>
              <select value={priority} onChange={(e) => setPriority(e.target.value)}
                className="w-full rounded-md border border-slate-200 bg-white px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
              >
                <option value="LOW">Low</option>
                <option value="NORMAL">Normal</option>
                <option value="HIGH">High</option>
                <option value="URGENT">Urgent</option>
              </select>
            </label>
          </div>
          <label className="block">
            <span className="mb-1 block text-xs font-medium text-slate-700">Describe the issue</span>
            <textarea
              value={body}
              onChange={(e) => setBody(e.target.value)}
              rows={5}
              maxLength={5000}
              className="w-full resize-y rounded-md border border-slate-200 bg-white px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
            />
          </label>
        </div>
        {err && <p className="mt-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err}</p>}
        <div className="mt-5 flex justify-end gap-2">
          <button type="button" onClick={onClose}
            className="rounded-md px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-100">
            Cancel
          </button>
          <button type="button" onClick={submit} disabled={submitting}
            className="rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60">
            {submitting ? 'Opening…' : 'Open ticket'}
          </button>
        </div>
      </div>
    </div>
  );
}
