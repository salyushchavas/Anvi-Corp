'use client';

import toast from 'react-hot-toast';
import api from '@/lib/careers/api';
import { setMailAuth } from '@/lib/careers/mail-auth-storage';

/**
 * Careers → mail SSO handoff. Called when a staff user clicks any of
 * the /mail entry points (top-bar icon, sidebar "Mail" item, or the
 * mailbox-peek deep-links). Flow:
 *
 * <ol>
 *   <li>POST /api/v1/mail/sso-token with the careers JWT (attached by
 *       the shared careers axios instance).</li>
 *   <li>On success, persist the returned mail token + refresh token +
 *       account into the mail.* localStorage keys the /mail app reads
 *       via {@link setMailAuth}.</li>
 *   <li>Navigate to {@code /mail} — the mail app's guard now sees an
 *       authenticated session and renders the inbox directly, no
 *       login form.</li>
 * </ol>
 *
 * On 404 (no paired mailbox), we surface a clean "No mailbox
 * provisioned" toast and stay put. On any other error, we surface a
 * generic error toast. The caller is responsible for locking a button
 * or flag while the promise is in flight.
 */
interface MailSsoTokenResponse {
  token: string;
  refreshToken: string;
  accessTokenExpiresInSeconds: number;
  accountId: string;
  email: string;
  displayName: string | null;
  role: string;
  mustChangePassword: boolean;
  domainId: string | null;
}

export async function openMailWithSso(): Promise<void> {
  try {
    const res = await api.post<MailSsoTokenResponse>('/api/v1/mail/sso-token');
    const data = res.data;
    // Mirror the shape mail-auth-storage.setMailAuth expects.
    setMailAuth({
      token: data.token,
      refreshToken: data.refreshToken,
      account: {
        accountId: data.accountId,
        email: data.email,
        displayName: data.displayName ?? null,
        role: data.role,
        mustChangePassword: Boolean(data.mustChangePassword),
        domainId: data.domainId ?? null,
      },
    });
    // Full navigation (not next/router) so the mail app boots with
    // fresh module state and its own auth-context provider picks up
    // the freshly-written localStorage on first render.
    if (typeof window !== 'undefined') {
      window.location.href = '/mail';
    }
  } catch (err) {
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    const status = ax.response?.status;
    if (status === 404) {
      toast.error(
        ax.response?.data?.error ?? 'No mailbox provisioned for your account.',
      );
      return;
    }
    if (status === 503) {
      toast.error(
        ax.response?.data?.error ?? 'Mail is not configured on this environment.',
      );
      return;
    }
    toast.error(
      ax.response?.data?.error ?? 'Could not open mail. Please try again.',
    );
  }
}
