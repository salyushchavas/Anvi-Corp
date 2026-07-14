'use client';

import { useCallback, useEffect, useState } from 'react';
import { Building2, Plus } from 'lucide-react';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import type { Uuid } from '@/types';

interface AdminEntityResponse {
  id: Uuid;
  name: string;
  address: string | null;
  country: string | null;
  isActive: boolean;
  createdAt: string;
}

export default function AdminEntitiesPage() {
  return (
    <ProtectedRoute requiredRoles={['SUPER_ADMIN']}>
      <DashboardLayout title="Entities">
        <EntitiesList />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function EntitiesList() {
  const [entities, setEntities] = useState<AdminEntityResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminEntityResponse | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const res = await api.get<AdminEntityResponse[]>('/api/v1/admin/entities');
      setEntities(res.data ?? []);
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't load entities.");
      setEntities([]);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!toast) return;
    const t = window.setTimeout(() => setToast(null), 3000);
    return () => window.clearTimeout(t);
  }, [toast]);

  return (
    <section>
      {toast && (
        <div className="mb-4 rounded border border-green-200 bg-green-50 px-4 py-2 text-sm text-green-800">
          {toast}
        </div>
      )}

      <div className="mb-4 flex items-center justify-between">
        <p className="text-sm text-gray-600">Staffing entities (companies) that host postings.</p>
        <button
          type="button"
          onClick={() => setCreating(true)}
          className="inline-flex items-center gap-1.5 rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-white hover:bg-accent/90"
        >
          <Plus className="h-4 w-4" />
          New entity
        </button>
      </div>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {entities === null ? (
        <div className="py-10 text-center text-sm text-gray-500">Loading…</div>
      ) : entities.length === 0 ? (
        <div className="rounded-lg border border-dashed border-gray-300 bg-white p-10 text-center">
          <Building2 className="mx-auto mb-3 h-8 w-8 text-gray-400" strokeWidth={1.5} />
          <p className="text-sm text-gray-600">No entities yet.</p>
        </div>
      ) : (
        <ul className="space-y-3">
          {entities.map((e) => (
            <li
              key={e.id}
              className="flex items-start justify-between gap-4 rounded-lg border border-gray-200 bg-white p-4"
            >
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <div className="font-semibold text-gray-900">{e.name}</div>
                  <span
                    className={
                      'inline-flex items-center gap-1.5 text-xs ' +
                      (e.isActive ? 'text-green-700' : 'text-gray-500')
                    }
                  >
                    <span
                      className={
                        'h-2 w-2 rounded-full ' +
                        (e.isActive ? 'bg-green-500' : 'bg-gray-400')
                      }
                    />
                    {e.isActive ? 'Active' : 'Inactive'}
                  </span>
                </div>
                <div className="mt-1 text-sm text-gray-600">
                  {e.country ?? '—'}
                  {e.address ? <> · {e.address}</> : null}
                </div>
              </div>
              <button
                type="button"
                onClick={() => setEditing(e)}
                className="shrink-0 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
              >
                Edit
              </button>
            </li>
          ))}
        </ul>
      )}

      {creating && (
        <EntityModal
          mode="create"
          onClose={() => setCreating(false)}
          onSaved={() => {
            setCreating(false);
            setToast('Entity created.');
            void load();
          }}
        />
      )}

      {editing && (
        <EntityModal
          mode="edit"
          entity={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            setToast('Entity updated.');
            void load();
          }}
        />
      )}
    </section>
  );
}

function EntityModal({
  mode,
  entity,
  onClose,
  onSaved,
}: {
  mode: 'create' | 'edit';
  entity?: AdminEntityResponse;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(entity?.name ?? '');
  const [address, setAddress] = useState(entity?.address ?? '');
  const [country, setCountry] = useState(entity?.country ?? '');
  const [isActive, setIsActive] = useState<boolean>(entity?.isActive ?? true);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    if (!name.trim()) {
      setError('Name is required.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const body = {
        name: name.trim(),
        address: address.trim() || null,
        country: country.trim() || null,
        isActive,
      };
      if (mode === 'create') {
        await api.post('/api/v1/admin/entities', body);
      } else if (entity) {
        await api.put(`/api/v1/admin/entities/${entity.id}`, body);
      }
      onSaved();
    } catch (err: any) {
      setError(err?.response?.data?.error ?? 'Could not save the entity.');
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
        <h3 className="mb-4 text-lg font-semibold text-gray-900">
          {mode === 'create' ? 'New entity' : 'Edit entity'}
        </h3>
        <div className="space-y-3">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Name <span className="text-red-500">*</span>
            </label>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">Address</label>
            <input
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">Country</label>
            <input
              value={country}
              onChange={(e) => setCountry(e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <label className="flex items-center gap-2 text-sm text-gray-700">
            <input
              type="checkbox"
              checked={isActive}
              onChange={(e) => setIsActive(e.target.checked)}
              className="h-4 w-4 rounded border-gray-300 text-accent focus:ring-accent"
            />
            Active
          </label>
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
            {submitting ? 'Saving…' : mode === 'create' ? 'Create' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  );
}
