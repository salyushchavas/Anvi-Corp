import type { User, UserRole } from '@/types';

// Seven-role landing map. Each role lands on its own dashboard. Page-level
// ProtectedRoute gates which screens a role can open from there.
export const ROLE_DASHBOARDS: Record<UserRole, string> = {
  INTERN: '/careers/intern',
  TRAINER: '/careers/trainer',
  EVALUATOR: '/careers/evaluator',
  REPORTING_MANAGER: '/careers/reporting-manager',
  MANAGER: '/careers/manager',
  ERM: '/careers/erm',
  SUPER_ADMIN: '/careers/admin',
};

// Priority order for picking the landing when a user carries multiple roles.
// SUPER_ADMIN wins (god-mode lands on admin); MANAGER next (oversight);
// then operational/staff roles; INTERN last as the candidate face.
const ROLE_LANDING_PRIORITY: UserRole[] = [
  'SUPER_ADMIN',
  'MANAGER',
  'ERM',
  'TRAINER',
  'EVALUATOR',
  'REPORTING_MANAGER',
  'INTERN',
];

export function getDashboardForUser(user: User): string {
  if (!user.roles?.length) return '/careers/login';
  const ordered = ROLE_LANDING_PRIORITY.find((r) => user.roles.includes(r));
  if (ordered) return ROLE_DASHBOARDS[ordered];
  return ROLE_DASHBOARDS[user.roles[0]] ?? '/careers/login';
}

/**
 * Role required to access a given /careers/* sub-path. Used to validate
 * a {@code returnTo} hint at login time — if the freshly authenticated
 * user has no role for the requested path, we drop the returnTo and
 * land them on their own dashboard instead. Belt-and-braces for the
 * sign-out race that previously stamped {@code ?returnTo=<old-role-page>}
 * onto the login URL.
 */
const PATH_PREFIX_ROLES: ReadonlyArray<[string, UserRole]> = [
  ['/careers/admin',            'SUPER_ADMIN'],
  ['/careers/erm',              'ERM'],
  ['/careers/manager',          'MANAGER'],
  ['/careers/reporting-manager','REPORTING_MANAGER'],
  ['/careers/trainer',          'TRAINER'],
  ['/careers/evaluator',        'EVALUATOR'],
  ['/careers/intern',           'INTERN'],
];

/**
 * True when the path is safe to redirect the freshly authenticated user
 * to. Falls back to a permissive {@code true} for paths that don't map
 * to a known role prefix (e.g. {@code /careers/jobs}).
 */
export function returnToIsAllowedForUser(returnTo: string, user: User): boolean {
  for (const [prefix, role] of PATH_PREFIX_ROLES) {
    if (returnTo === prefix
        || returnTo.startsWith(prefix + '/')
        || returnTo.startsWith(prefix + '?')) {
      return user.roles?.includes(role) ?? false;
    }
  }
  return true;
}
