'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { AlertCircle, MoreHorizontal, Plus, Search, ShieldAlert, Trash2 } from 'lucide-react';
import api from '@/lib/careers/api';
import { useAuth } from '@/lib/careers/auth-context';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import { formatDateOnly } from '@/lib/careers/format-date';
import type { Uuid, UserRole } from '@/types';

interface AdminUserResponse {
  id: Uuid;
  name: string;
  email: string;
  roles: UserRole[];
  active: boolean;
  createdAt: string;
  applicantId?: string | null;
  /** Whether the user completed the /auth/verify-email 6-digit code.
   *  Unverified INTERN rows are hidden from the default list (bot-signup
   *  mitigation) — flip the "Show unverified" toggle to see them. */
  emailVerified?: boolean;
}

/** Matches the backend's LAST_SUPER_ADMIN_MSG so we can render this as the
 *  blocked-state banner instead of a passing toast. */
const LAST_SUPER_ADMIN_FRAGMENT = 'last active SUPER_ADMIN';

// Roles the SUPER_ADMIN can assign via the CHANGE-ROLE flow. SUPER_ADMIN is
// here so promotion stays possible (gated by the backend's last-SA guards).
// INTERN is NOT in the picker — that role is set by candidate registration,
// not by admin-side assignment.
const STAFF_ROLES: UserRole[] = [
  'SUPER_ADMIN',
  'MANAGER',
  'ERM',
  'JOBS_ADMIN',
  'TRAINER',
  'EVALUATOR',
  'REPORTING_MANAGER',
];

// Roles the SUPER_ADMIN can pick when CREATING a brand-new account.
// Intentionally narrower than STAFF_ROLES: SUPER_ADMIN is excluded so a
// new admin can only come into existence via promotion of an existing
// account (kept honest by the backend STAFF_CREATABLE_ROLES set).
const STAFF_CREATABLE_ROLES: UserRole[] = [
  'MANAGER',
  'ERM',
  'JOBS_ADMIN',
  'TRAINER',
  'EVALUATOR',
  'REPORTING_MANAGER',
];

// FILTER_ROLES are what the list filter dropdown offers — STAFF_ROLES plus
// INTERN so the operator can narrow down to candidate users (e.g. to find
// the one they want to hard-delete). INTERN is NOT added to the create-user
// picker for the reason called out above.
const FILTER_ROLES: UserRole[] = [...STAFF_ROLES, 'INTERN'];

const ROLE_LABEL: Record<UserRole, string> = {
  INTERN: 'Intern',
  TRAINER: 'Trainer',
  EVALUATOR: 'Evaluator',
  REPORTING_MANAGER: 'Reporting Manager',
  MANAGER: 'Manager',
  ERM: 'ERM',
  JOBS_ADMIN: 'Jobs Admin',
  SUPER_ADMIN: 'Super admin',
};

// role-badge palette deferred to M2 role-consolidation
// (TRAINER/RM/MANAGER all share amber today — fold or split lands with M2).
// INTERN uses light slate, SUPER_ADMIN uses dark slate, to differentiate.
const ROLE_COLOR: Record<UserRole, string> = {
  INTERN: 'bg-slate-100 text-slate-700',
  TRAINER: 'bg-amber-100 text-amber-800',
  EVALUATOR: 'bg-brand-100 text-brand-800',
  REPORTING_MANAGER: 'bg-amber-100 text-amber-800',
  MANAGER: 'bg-amber-100 text-amber-800',
  ERM: 'bg-green-100 text-green-800',
  JOBS_ADMIN: 'bg-sky-100 text-sky-800',
  SUPER_ADMIN: 'bg-slate-200 text-slate-900',
};

// Show SUPER_ADMIN first if present (god-mode); then MANAGER (oversight);
// then ERM; then any other non-INTERN role; otherwise just the first.
const CANDIDATE_SIDE: UserRole[] = ['INTERN'];

function primaryRole(roles: UserRole[] | null | undefined): UserRole {
  if (!roles || roles.length === 0) return 'INTERN';
  if (roles.includes('SUPER_ADMIN')) return 'SUPER_ADMIN';
  if (roles.includes('MANAGER')) return 'MANAGER';
  if (roles.includes('ERM')) return 'ERM';
  if (roles.includes('JOBS_ADMIN')) return 'JOBS_ADMIN';
  const staff = roles.find((r) => !CANDIDATE_SIDE.includes(r));
  return staff ?? roles[0];
}

function RoleBadge({ role }: { role: UserRole }) {
  return (
    <span
      className={
        'inline-block whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium ' +
        ROLE_COLOR[role]
      }
    >
      {ROLE_LABEL[role]}
    </span>
  );
}

export default function AdminUsersPage() {
  return (
    <ProtectedRoute requiredRoles={['SUPER_ADMIN']}>
      <DashboardLayout title="Users">
        <UsersTable />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function UsersTable() {
  const { user: me } = useAuth();
  const [users, setUsers] = useState<AdminUserResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [roleFilter, setRoleFilter] = useState<UserRole | 'ALL'>('ALL');
  const [showCreate, setShowCreate] = useState(false);
  const [menuFor, setMenuFor] = useState<Uuid | null>(null);
  const [changingRoleFor, setChangingRoleFor] = useState<AdminUserResponse | null>(null);
  const [editingCredentialsFor, setEditingCredentialsFor] =
    useState<AdminUserResponse | null>(null);
  const [confirmingDeactivateFor, setConfirmingDeactivateFor] =
    useState<AdminUserResponse | null>(null);
  // Hard-delete is restricted to candidate users (roles == {INTERN}) and
  // gated behind a typed-confirm modal — much higher stakes than the
  // reversible Deactivate flow.
  const [confirmingDeleteFor, setConfirmingDeleteFor] =
    useState<AdminUserResponse | null>(null);
  // Surfaces the last-active-SUPER_ADMIN refusal as a persistent banner the
  // operator has to dismiss — not a passing toast.
  const [blockedMessage, setBlockedMessage] = useState<string | null>(null);
  // Bot-signup mitigation: unverified INTERN rows (created but never
  // completed the email code) are hidden by default so the list stays
  // clean. Flip this to review + purge suspected bot signups.
  const [showUnverified, setShowUnverified] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      const res = await api.get<AdminUserResponse[]>(
        `/api/v1/admin/users?includeUnverified=${showUnverified}`);
      setUsers(res.data ?? []);
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't load users.");
      setUsers([]);
    }
  }, [showUnverified]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!toast) return;
    const t = window.setTimeout(() => setToast(null), 3000);
    return () => window.clearTimeout(t);
  }, [toast]);

  const filtered = useMemo(() => {
    if (!users) return [];
    const q = search.trim().toLowerCase();
    return users.filter((u) => {
      const role = primaryRole(u.roles);
      if (roleFilter !== 'ALL' && role !== roleFilter) return false;
      if (!q) return true;
      return (
        (u.name ?? '').toLowerCase().includes(q) ||
        (u.email ?? '').toLowerCase().includes(q)
      );
    });
  }, [users, search, roleFilter]);

  const setStatus = useCallback(
    async (u: AdminUserResponse, nextActive: boolean) => {
      // Optimistic update — rolled back on error.
      setUsers((curr) =>
        curr ? curr.map((x) => (x.id === u.id ? { ...x, active: nextActive } : x)) : curr,
      );
      setMenuFor(null);
      try {
        await api.put(`/api/v1/admin/users/${u.id}/status`, { active: nextActive });
        setToast(nextActive ? 'User activated.' : 'User deactivated.');
      } catch (err: any) {
        // Rollback optimistic state.
        setUsers((curr) =>
          curr ? curr.map((x) => (x.id === u.id ? { ...x, active: u.active } : x)) : curr,
        );
        const status = err?.response?.status;
        const msg = err?.response?.data?.error ?? 'Could not update status.';
        if (status === 409 && msg.includes(LAST_SUPER_ADMIN_FRAGMENT)) {
          // The brief's "clear blocked state, not a server error" — render
          // as a persistent banner the SA has to acknowledge.
          setBlockedMessage(msg);
        } else {
          setToast(msg);
        }
      }
    },
    [],
  );

  // Activate is fire-and-forget; deactivate gates through a confirm modal.
  const activate = (u: AdminUserResponse) => void setStatus(u, true);
  const askDeactivate = (u: AdminUserResponse) => {
    setMenuFor(null);
    setConfirmingDeactivateFor(u);
  };
  const askDelete = (u: AdminUserResponse) => {
    setMenuFor(null);
    setConfirmingDeleteFor(u);
  };
  const deleteUser = useCallback(async (u: AdminUserResponse) => {
    try {
      await api.delete(`/api/v1/admin/users/${u.id}`);
      // Drop the row optimistically; the typed-confirm modal already
      // walled this off so a stale list is fine.
      setUsers((curr) => (curr ? curr.filter((x) => x.id !== u.id) : curr));
      setToast(`Deleted ${u.email}.`);
    } catch (err: any) {
      const status = err?.response?.status;
      const msg = err?.response?.data?.error ?? 'Could not delete user.';
      if (status === 409 && msg.includes(LAST_SUPER_ADMIN_FRAGMENT)) {
        setBlockedMessage(msg);
      } else {
        setToast(msg);
      }
    }
  }, []);

  return (
    <section>
      {blockedMessage && (
        <div className="mb-4 flex items-start gap-3 rounded border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
          <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" strokeWidth={2} />
          <div className="flex-1">
            <div className="font-semibold">Blocked by policy</div>
            <div className="mt-0.5 text-red-700">{blockedMessage}</div>
          </div>
          <button
            type="button"
            onClick={() => setBlockedMessage(null)}
            className="rounded border border-red-300 px-2 py-0.5 text-xs font-medium text-red-700 hover:bg-red-100"
          >
            Dismiss
          </button>
        </div>
      )}

      {toast && (
        <div className="mb-4 rounded border border-green-200 bg-green-50 px-4 py-2 text-sm text-green-800">
          {toast}
        </div>
      )}

      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <label className="relative block w-full sm:max-w-xs">
            <span className="sr-only">Search</span>
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
            <input
              type="search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search"
              className="w-full rounded-md border border-gray-300 bg-white py-2 pl-9 pr-3 text-sm placeholder:text-gray-400 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </label>
          <label className="flex items-center gap-2 text-sm text-gray-600">
            Filter:
            <select
              value={roleFilter}
              onChange={(e) => setRoleFilter(e.target.value as UserRole | 'ALL')}
              className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            >
              <option value="ALL">All roles</option>
              {FILTER_ROLES.map((r) => (
                <option key={r} value={r}>
                  {ROLE_LABEL[r]}
                </option>
              ))}
            </select>
          </label>
          <label
            className="inline-flex cursor-pointer items-center gap-2 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
            title="Unverified intern accounts are hidden from the default list to keep bot signups out of the roster."
          >
            <input
              type="checkbox"
              checked={showUnverified}
              onChange={(e) => setShowUnverified(e.target.checked)}
              className="h-4 w-4"
            />
            Show unverified interns
          </label>
        </div>
        <button
          type="button"
          onClick={() => setShowCreate(true)}
          className="inline-flex items-center gap-1.5 rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-white hover:bg-accent/90"
        >
          <Plus className="h-4 w-4" />
          New user
        </button>
      </div>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {users === null ? (
        <div className="py-10 text-center text-sm text-gray-500">Loading…</div>
      ) : filtered.length === 0 ? (
        <div className="rounded-lg border border-dashed border-gray-300 bg-white p-10 text-center text-sm text-gray-600">
          {users.length === 0 ? 'No users found.' : 'No users match those filters.'}
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white">
          <table className="min-w-full text-sm">
            <thead className="border-b border-gray-200 bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
              <tr>
                <th className="px-4 py-3 font-medium">Name</th>
                <th className="px-4 py-3 font-medium">Email</th>
                <th className="px-4 py-3 font-medium">Role</th>
                <th className="px-4 py-3 font-medium">Status</th>
                <th className="px-4 py-3 font-medium">Applicant ID</th>
                <th className="px-4 py-3 font-medium">Created</th>
                <th className="px-4 py-3 font-medium text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((u) => {
                const role = primaryRole(u.roles);
                const isSelf = me?.userId === u.id;
                return (
                  <tr key={u.id} className="border-b border-gray-100 last:border-0">
                    <td className="px-4 py-3 font-medium text-gray-900">
                      <Link
                        href={`/careers/admin/users/${u.id}/supervision`}
                        className="hover:text-accent-dark hover:underline"
                      >
                        {u.name ?? '—'}
                      </Link>
                      {isSelf && (
                        <span className="ml-2 text-xs font-normal text-gray-500">(you)</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-gray-700">{u.email}</td>
                    <td className="px-4 py-3">
                      <RoleBadge role={role} />
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={
                          'inline-flex items-center gap-1.5 text-sm ' +
                          (u.active ? 'text-green-700' : 'text-gray-500')
                        }
                      >
                        <span
                          className={
                            'h-2 w-2 rounded-full ' +
                            (u.active ? 'bg-green-500' : 'bg-gray-400')
                          }
                        />
                        {u.active ? 'Active' : 'Inactive'}
                      </span>
                    </td>
                    <td className="px-4 py-3 font-mono text-xs text-gray-600">
                      {u.applicantId ?? '—'}
                    </td>
                    <td className="px-4 py-3 text-xs text-gray-600">
                      {u.createdAt ? formatDateOnly(u.createdAt) : '—'}
                    </td>
                    <td className="relative px-4 py-3 text-right">
                      <button
                        type="button"
                        aria-label="Row actions"
                        onClick={() => setMenuFor((curr) => (curr === u.id ? null : u.id))}
                        className="inline-flex h-8 w-8 items-center justify-center rounded hover:bg-gray-100"
                      >
                        <MoreHorizontal className="h-4 w-4 text-gray-600" />
                      </button>
                      {menuFor === u.id && (
                        <div className="absolute right-4 top-full z-20 mt-1 w-44 overflow-hidden rounded-md border border-gray-200 bg-white text-left shadow-lg">
                          <button
                            type="button"
                            onClick={() => {
                              setMenuFor(null);
                              setChangingRoleFor(u);
                            }}
                            disabled={isSelf}
                            className="block w-full px-3 py-2 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-50"
                          >
                            Change role
                          </button>
                          <button
                            type="button"
                            onClick={() => {
                              setMenuFor(null);
                              setEditingCredentialsFor(u);
                            }}
                            className="block w-full px-3 py-2 text-sm text-gray-700 hover:bg-gray-50"
                          >
                            Edit credentials
                          </button>
                          <button
                            type="button"
                            onClick={() => (u.active ? askDeactivate(u) : activate(u))}
                            disabled={isSelf}
                            className="block w-full px-3 py-2 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-50"
                          >
                            {u.active ? 'Deactivate' : 'Activate'}
                          </button>
                          {!isSelf && (
                            <button
                              type="button"
                              onClick={() => askDelete(u)}
                              className="flex w-full items-center gap-2 border-t border-gray-100 px-3 py-2 text-sm font-medium text-red-700 hover:bg-red-50"
                            >
                              <Trash2 className="h-3.5 w-3.5" strokeWidth={2} />
                              Delete permanently
                            </button>
                          )}
                        </div>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {showCreate && (
        <NewUserModal
          onClose={() => setShowCreate(false)}
          onCreated={() => {
            setShowCreate(false);
            setToast('User created.');
            void load();
          }}
        />
      )}

      {changingRoleFor && (
        <ChangeRoleModal
          target={changingRoleFor}
          onClose={() => setChangingRoleFor(null)}
          onSaved={() => {
            setChangingRoleFor(null);
            setToast('Role updated.');
            void load();
          }}
          onBlocked={(msg) => {
            setChangingRoleFor(null);
            setBlockedMessage(msg);
          }}
        />
      )}

      {editingCredentialsFor && (
        <EditCredentialsModal
          target={editingCredentialsFor}
          onClose={() => setEditingCredentialsFor(null)}
          onSaved={() => {
            setEditingCredentialsFor(null);
            setToast('Credentials updated.');
            void load();
          }}
        />
      )}

      {confirmingDeactivateFor && (
        <ConfirmDeactivateModal
          target={confirmingDeactivateFor}
          onCancel={() => setConfirmingDeactivateFor(null)}
          onConfirm={async () => {
            const u = confirmingDeactivateFor;
            setConfirmingDeactivateFor(null);
            await setStatus(u, false);
          }}
        />
      )}

      {confirmingDeleteFor && (
        <ConfirmDeleteModal
          target={confirmingDeleteFor}
          onCancel={() => setConfirmingDeleteFor(null)}
          onConfirm={async () => {
            const u = confirmingDeleteFor;
            setConfirmingDeleteFor(null);
            await deleteUser(u);
          }}
        />
      )}
    </section>
  );
}

interface CreatedStaffAccount {
  email: string;
  password: string;
  role: UserRole;
  mailboxAddress: string;
  deliveryEmail: string | null;
  credentialsEmailSent: boolean | null;
}

function NewUserModal({
  onClose,
  onCreated,
}: {
  onClose: () => void;
  onCreated: () => void;
}) {
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [deliveryEmail, setDeliveryEmail] = useState('');
  const [role, setRole] = useState<UserRole>('ERM');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // Post-success: hold email + password so the copy panel can render
  // them. The password never round-trips through the server response —
  // we hold onto the raw value the admin just typed.
  const [created, setCreated] = useState<CreatedStaffAccount | null>(null);

  const submit = async () => {
    if (!email.trim()) {
      setError('Email is required.');
      return;
    }
    if (!password || password.length < 8) {
      setError('Password must be at least 8 characters.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const res = await api.post<{
        email: string;
        roles: UserRole[];
        mailboxProvisioned: boolean;
        mailboxAddress: string;
        credentialsEmailSent: boolean | null;
      }>('/api/v1/admin/users', {
        name: name.trim() || undefined,
        email: email.trim(),
        password,
        role,
        deliveryEmail: deliveryEmail.trim() || undefined,
      });
      const primaryRoleOnRow =
        res.data.roles && res.data.roles.length > 0 ? res.data.roles[0] : role;
      setCreated({
        email: res.data.email,
        password,
        role: primaryRoleOnRow,
        mailboxAddress: res.data.mailboxAddress ?? res.data.email,
        deliveryEmail: deliveryEmail.trim() || null,
        credentialsEmailSent: res.data.credentialsEmailSent ?? null,
      });
    } catch (err: any) {
      const status = err?.response?.status;
      const msg = err?.response?.data?.error;
      if (status === 409) {
        setError(msg ?? 'A user with that email already exists.');
      } else if (status === 400) {
        setError(msg ?? 'Some fields are invalid.');
      } else {
        setError(msg ?? 'Could not create user.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  if (created) {
    return (
      <CredentialsHandoffModal
        created={created}
        onDone={() => {
          setCreated(null);
          onCreated();
        }}
      />
    );
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
        <h3 className="mb-1 text-lg font-semibold text-gray-900">Create staff user</h3>
        <p className="mb-4 text-xs text-gray-500">
          Staff only — intern accounts are created by candidate registration,
          not from here. One action creates the careers login AND provisions
          the paired mailbox — the same email + password signs the user in
          to both.
        </p>
        <div className="space-y-3">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Email <span className="text-red-500">*</span>
            </label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="jane@anvicorp.com"
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
            <p className="mt-1 text-xs text-gray-500">
              Must be on a provisioned internal domain (e.g.{' '}
              <span className="font-mono">@anvicorp.com</span>) — this is
              the mailbox address too.
            </p>
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Password <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="new-password"
              placeholder="Min 8 characters"
              className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
            <p className="mt-1 text-xs text-gray-500">
              Shared credential — signs into careers AND the internal /mail
              inbox. You&apos;ll be able to copy it on the next screen.
            </p>
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Role <span className="text-red-500">*</span>
            </label>
            <select
              value={role}
              onChange={(e) => setRole(e.target.value as UserRole)}
              className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            >
              {STAFF_CREATABLE_ROLES.map((r) => (
                <option key={r} value={r}>
                  {ROLE_LABEL[r]}
                </option>
              ))}
            </select>
            <p className="mt-1 text-xs text-gray-500">
              To grant SUPER_ADMIN, create the user with a different staff
              role first, then use Change role to promote.
            </p>
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Display name (optional)
            </label>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Defaults to the local-part of the email"
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Delivery email (optional)
            </label>
            <input
              type="email"
              value={deliveryEmail}
              onChange={(e) => setDeliveryEmail(e.target.value)}
              placeholder="user's personal inbox for the credentials handoff"
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
            <p className="mt-1 text-xs text-gray-500">
              We&apos;ll email the credentials here (typically the user&apos;s
              personal inbox — their new mailbox can&apos;t receive its own
              welcome mail). Leave blank to share them via the copy panel only.
            </p>
          </div>
          {error && <div className="text-sm text-red-600">{error}</div>}
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={submitting}
            className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent/90 disabled:opacity-60"
          >
            {submitting ? 'Creating…' : 'Create user'}
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * Post-create handoff. The admin sees the credentials one last time and
 * gets a Copy-to-clipboard affordance so they can paste them into Slack
 * / SMS / wherever. Closing this triggers the table reload.
 */
function CredentialsHandoffModal({
  created,
  onDone,
}: {
  created: CreatedStaffAccount;
  onDone: () => void;
}) {
  const [copied, setCopied] = useState(false);

  const credentialsBlock = `${created.email}\n${created.password}`;

  function copyCredentials() {
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      navigator.clipboard.writeText(credentialsBlock).then(() => {
        setCopied(true);
        window.setTimeout(() => setCopied(false), 2000);
      });
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div className="w-full max-w-lg rounded-lg bg-white p-6 shadow-xl">
        <div className="mb-3 flex items-start gap-3">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-700">
            <Plus className="h-5 w-5 rotate-45" strokeWidth={2} />
          </span>
          <div className="flex-1">
            <h3 className="text-lg font-semibold text-gray-900">User created</h3>
            <p className="mt-1 text-xs text-gray-600">
              {created.deliveryEmail == null
                ? <>Both the careers login and the internal mailbox for <strong>{created.email}</strong> are provisioned. Share the credentials below.</>
                : created.credentialsEmailSent
                ? <>Credentials were emailed to <strong>{created.deliveryEmail}</strong>. Copy panel below as a fallback.</>
                : <>We couldn&apos;t send the credentials email to <strong>{created.deliveryEmail}</strong> (check SMTP). Share the credentials below out-of-band.</>}
            </p>
          </div>
        </div>

        <dl className="space-y-2 rounded-md border border-gray-200 bg-gray-50 p-3 text-sm">
          <Row k="Role" v={ROLE_LABEL[created.role]} />
          <Row k="Careers login" v={created.email} />
          <Row k="Mailbox" v={created.mailboxAddress} />
        </dl>

        <div className="mt-3">
          <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-gray-500">
            Credentials (email · password)
          </label>
          <div className="flex items-stretch gap-2">
            <textarea
              readOnly
              value={credentialsBlock}
              rows={2}
              onFocus={(e) => e.currentTarget.select()}
              className="min-w-0 flex-1 resize-none rounded-md border border-gray-300 bg-white px-3 py-2 font-mono text-xs text-gray-800"
            />
            <button
              type="button"
              onClick={copyCredentials}
              className="shrink-0 rounded-md border border-gray-300 bg-white px-3 py-2 text-xs font-medium text-gray-700 hover:bg-gray-50"
            >
              {copied ? 'Copied!' : 'Copy'}
            </button>
          </div>
        </div>

        <div className="mt-3 rounded-md border border-amber-200 bg-amber-50 p-2.5 text-[11px] text-amber-900">
          The password is displayed here ONE TIME. Share it now — after
          you close this dialog, it&apos;s gone (the server only stores
          the BCrypt hash).
        </div>

        <div className="mt-5 flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onDone}
            className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent/90"
          >
            Done
          </button>
        </div>
      </div>
    </div>
  );
}

function Row({ k, v, mono }: { k: string; v: string; mono?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="text-[11px] font-semibold uppercase tracking-wide text-gray-500">{k}</dt>
      <dd className={'truncate text-sm text-gray-800 ' + (mono ? 'font-mono' : '')}>{v}</dd>
    </div>
  );
}

function ChangeRoleModal({
  target,
  onClose,
  onSaved,
  onBlocked,
}: {
  target: AdminUserResponse;
  onClose: () => void;
  onSaved: () => void;
  onBlocked: (msg: string) => void;
}) {
  const current = primaryRole(target.roles);
  const [role, setRole] = useState<UserRole>(STAFF_ROLES.includes(current) ? current : 'ERM');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await api.put(`/api/v1/admin/users/${target.id}/role`, { role });
      onSaved();
    } catch (err: any) {
      const status = err?.response?.status;
      const msg = err?.response?.data?.error ?? 'Could not update role.';
      if (status === 409 && msg.includes(LAST_SUPER_ADMIN_FRAGMENT)) {
        // Escalate to the parent's blocked-state banner and close the modal —
        // the action isn't a "retryable" inline error, it's a policy refusal.
        onBlocked(msg);
        return;
      }
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
        <h3 className="mb-1 text-lg font-semibold text-gray-900">Change role</h3>
        <p className="mb-4 text-xs text-gray-500">
          {target.name} · {target.email}
        </p>
        <label className="mb-1 block text-sm font-medium text-gray-700">Role</label>
        <select
          value={role}
          onChange={(e) => setRole(e.target.value as UserRole)}
          className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
        >
          {STAFF_ROLES.map((r) => (
            <option key={r} value={r}>
              {ROLE_LABEL[r]}
            </option>
          ))}
        </select>
        {error && <div className="mt-2 text-sm text-red-600">{error}</div>}
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={submitting}
            className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent/90 disabled:opacity-60"
          >
            {submitting ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * Update an existing user's email and/or password. Both fields are
 * optional; at least one must be filled. Server dual-writes to the
 * paired mailbox (email move + password sync) so careers login + mail
 * login stay unified. 409 collisions from either side (careers email
 * uniqueness OR mailbox address collision) render inline.
 */
function EditCredentialsModal({
  target,
  onClose,
  onSaved,
}: {
  target: AdminUserResponse;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [email, setEmail] = useState(target.email);
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const emailChanging = email.trim().length > 0 && email.trim().toLowerCase() !== target.email.toLowerCase();
  const passwordChanging = password.length > 0;
  const canSubmit = (emailChanging || passwordChanging) && !submitting;

  const submit = async () => {
    setError(null);
    if (passwordChanging && password.length < 8) {
      setError('Password must be at least 8 characters.');
      return;
    }
    if (!emailChanging && !passwordChanging) {
      setError('Change the email, the password, or both.');
      return;
    }
    setSubmitting(true);
    try {
      await api.put(`/api/v1/admin/users/${target.id}/credentials`, {
        email: emailChanging ? email.trim() : undefined,
        password: passwordChanging ? password : undefined,
      });
      onSaved();
    } catch (err: any) {
      const status = err?.response?.status;
      const msg = err?.response?.data?.error;
      if (status === 409) {
        setError(msg ?? 'That email or mailbox address is already taken.');
      } else if (status === 400) {
        setError(msg ?? 'Invalid input.');
      } else {
        setError(msg ?? 'Could not update credentials.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
        <h3 className="mb-1 text-lg font-semibold text-gray-900">Edit credentials</h3>
        <p className="mb-4 text-xs text-gray-500">
          {target.name} · <span className="font-mono">{target.email}</span>
        </p>
        <div className="space-y-3">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">Login email</label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
            <p className="mt-1 text-xs text-gray-500">
              If the new domain is a provisioned internal domain
              (e.g. <span className="font-mono">@anvicorp.com</span>), the
              paired mailbox moves with it. Otherwise only the careers login
              changes and the mailbox stays where it is.
            </p>
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              New password <span className="text-xs text-gray-500">(leave blank to keep current)</span>
            </label>
            <input
              type="text"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="new-password"
              placeholder="Min 8 characters"
              className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
            <p className="mt-1 text-xs text-gray-500">
              Same password authenticates against careers AND the paired
              /mail inbox. Share it out-of-band after saving — the server
              only stores the BCrypt hash.
            </p>
          </div>
          {error && <div className="text-sm text-red-600">{error}</div>}
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={!canSubmit}
            className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent/90 disabled:opacity-60"
          >
            {submitting ? 'Saving…' : 'Save changes'}
          </button>
        </div>
      </div>
    </div>
  );
}

function ConfirmDeactivateModal({
  target,
  onCancel,
  onConfirm,
}: {
  target: AdminUserResponse;
  onCancel: () => void;
  onConfirm: () => void | Promise<void>;
}) {
  const [submitting, setSubmitting] = useState(false);
  const isSuperAdmin = target.roles?.includes('SUPER_ADMIN');
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
        <div className="mb-3 flex items-start gap-3">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-700">
            <AlertCircle className="h-5 w-5" strokeWidth={2} />
          </span>
          <div className="flex-1">
            <h3 className="text-lg font-semibold text-gray-900">Deactivate user</h3>
            <p className="mt-1 text-sm text-gray-600">
              {target.name} <span className="text-gray-400">·</span> {target.email}
            </p>
          </div>
        </div>
        <p className="text-sm text-gray-700">
          Deactivated users can&apos;t sign in. You can reactivate the account later;
          their data isn&apos;t deleted.
        </p>
        {isSuperAdmin && (
          <p className="mt-2 rounded-md border border-amber-200 bg-amber-50 p-2 text-xs text-amber-900">
            This is a SUPER_ADMIN account — if they&apos;re the only active one,
            this will be blocked.
          </p>
        )}
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            disabled={submitting}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-60"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={async () => {
              setSubmitting(true);
              await onConfirm();
              // Parent unmounts this modal; no need to reset state.
            }}
            disabled={submitting}
            className="rounded-md bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-60"
          >
            {submitting ? 'Deactivating…' : 'Deactivate'}
          </button>
        </div>
      </div>
    </div>
  );
}

function ConfirmDeleteModal({
  target,
  onCancel,
  onConfirm,
}: {
  target: AdminUserResponse;
  onCancel: () => void;
  onConfirm: () => Promise<void>;
}) {
  const [typed, setTyped] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const canDelete = typed === 'DELETE';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
        <div className="mb-3 flex items-start gap-3">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-red-100 text-red-700">
            <Trash2 className="h-5 w-5" strokeWidth={2} />
          </span>
          <div className="flex-1">
            <h3 className="text-lg font-semibold text-gray-900">Delete user permanently</h3>
            <p className="mt-1 text-sm text-gray-600">
              {target.name} <span className="text-gray-400">·</span> {target.email}
            </p>
          </div>
        </div>
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-900">
          <p className="font-semibold">This cannot be undone.</p>
          <p className="mt-1">
            The user account and every record scoped to them will be permanently
            deleted — for candidates this covers applications, interviews, offers,
            document packets, projects, evaluations, timesheets, and exit records;
            for staff it removes their identity, sessions, and role bindings. Audit
            log entries are preserved for the forensic trail.
          </p>
        </div>
        <label className="mt-4 block text-sm">
          <span className="font-medium text-gray-800">
            Type <span className="font-mono text-red-700">DELETE</span> to confirm
          </span>
          <input
            type="text"
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            autoFocus
            autoComplete="off"
            spellCheck={false}
            className="mt-1 w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm focus:border-red-500 focus:outline-none focus:ring-1 focus:ring-red-500"
          />
        </label>
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            disabled={submitting}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-60"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={async () => {
              if (!canDelete) return;
              setSubmitting(true);
              await onConfirm();
            }}
            disabled={!canDelete || submitting}
            className="rounded-md bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-40 disabled:cursor-not-allowed"
          >
            {submitting ? 'Deleting…' : 'Delete permanently'}
          </button>
        </div>
      </div>
    </div>
  );
}
