'use client';

import { useMemo } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import {
  AlertTriangle,
  BarChart3,
  Briefcase,
  ChevronRight,
  ClipboardCheck,
  FileSignature,
  FileText,
  FolderArchive,
  Gavel,
  Hammer,
  HelpCircle,
  Home,
  Inbox,
  LayoutDashboard,
  LifeBuoy,
  ListChecks,
  Lock,
  LogOut,
  MessagesSquare,
  Package,
  Settings,
  ShieldCheck,
  Star,
  UserPlus,
  Users,
  Video,
  type LucideIcon,
} from 'lucide-react';
import { useAuth } from '@/lib/auth-context';
import {
  useInternDashboardOptional,
  type InternModulesMap,
} from '@/components/intern/InternDashboardContext';
import SignOutButton from '@/components/auth/SignOutButton';
import { BRAND } from '@/lib/brand';
import type { UserRole } from '@/types';

interface StaffLink {
  icon: LucideIcon;
  label: string;
  href: string;
  /**
   * Key into {@link InternModulesMap}. Only INTERN links carry this; staff
   * links omit it and always render plain.
   */
  moduleKey?: keyof InternModulesMap;
  /**
   * Optional nested sub-items. Rendered indented under the parent; the
   * parent auto-expands when any child route is active. Children are
   * not expected to carry their own children (one level of nesting).
   */
  children?: StaffLink[];
}

// Six-role taxonomy. INTERN's 12-item sidebar is the doc-specified order from
// the Applicant-to-Intern Lifecycle spec. The other five roles will get their
// sub-nav filled in by per-role prompts; each is a single landing entry today.
const STAFF_ROLE_LINKS: Record<UserRole, StaffLink[]> = {
  INTERN: [
    { icon: Home,           label: 'Home',              href: '/careers/intern',             moduleKey: 'home' },
    { icon: Briefcase,      label: 'Job Postings',      href: '/careers/intern/jobs',        moduleKey: 'jobPostings' },
    { icon: ListChecks,     label: 'My Applications',   href: '/careers/intern/applications', moduleKey: 'myApplications' },
    { icon: Video,          label: 'Interview Center',  href: '/careers/intern/interviews',  moduleKey: 'interviewCenter' },
    { icon: FileSignature,  label: 'Offer Letter',      href: '/careers/intern/offer',       moduleKey: 'offerLetter' },
    { icon: FolderArchive,  label: 'My Projects',       href: '/careers/intern/projects',    moduleKey: 'myProjects' },
    { icon: Hammer,         label: 'Timesheets',        href: '/careers/intern/timesheets',  moduleKey: 'timesheets' },
    { icon: Star,           label: 'Evaluations',       href: '/careers/intern/evaluations', moduleKey: 'evaluations' },
    { icon: FileText,       label: 'Documents',         href: '/careers/intern/documents',   moduleKey: 'documents' },
    { icon: MessagesSquare, label: 'Messages',          href: '/careers/intern/messages',    moduleKey: 'messages' },
    { icon: LifeBuoy,       label: 'Doubts',            href: '/careers/intern/doubts',      moduleKey: 'doubts' },
    { icon: HelpCircle,     label: 'Help',              href: '/careers/intern/help',        moduleKey: 'help' },
  ],
  TRAINER: [
    { icon: LayoutDashboard, label: 'Trainer Dashboard', href: '/careers/trainer' },
  ],
  EVALUATOR: [
    { icon: LayoutDashboard, label: 'Evaluator Dashboard', href: '/careers/evaluator' },
  ],
  REPORTING_MANAGER: [
    { icon: LayoutDashboard, label: 'Reporting Manager Dashboard', href: '/careers/reporting-manager' },
  ],
  MANAGER: [
    { icon: LayoutDashboard, label: 'Manager Dashboard', href: '/careers/manager' },
  ],
  // ERM Control Dashboard. "New Hire List" is now a parent with three
  // onboarding-doc sub-items (Document Packets, Review Queue, I-9/E-Verify);
  // the parent route stays /careers/erm/new-hire.
  ERM: [
    { icon: Home,           label: 'Home',                 href: '/careers/erm' },
    { icon: Inbox,          label: 'Application Inbox',    href: '/careers/erm/applications' },
    { icon: ListChecks,     label: 'Shortlist Queue',      href: '/careers/erm/shortlist' },
    { icon: Video,          label: 'Interviews',           href: '/careers/erm/interviews' },
    { icon: Gavel,          label: 'Decision Center',      href: '/careers/erm/decision-center' },
    { icon: FileSignature,  label: 'Offers / IDMS',        href: '/careers/erm/offers' },
    {
      icon: UserPlus, label: 'New Hire List', href: '/careers/erm/new-hire',
      children: [
        { icon: Package,        label: 'Document Packets',   href: '/careers/erm/document-packets' },
        { icon: ClipboardCheck, label: 'Review Queue',       href: '/careers/erm/document-review' },
        { icon: ShieldCheck,    label: 'I-9 / E-Verify',     href: '/careers/erm/compliance' },
      ],
    },
    { icon: FolderArchive,  label: 'Document Gallery',     href: '/careers/erm/document-gallery' },
    { icon: Users,          label: 'Active Interns',       href: '/careers/erm/active-interns' },
    { icon: Hammer,         label: 'Timesheets',           href: '/careers/erm/timesheets' },
    { icon: AlertTriangle,  label: 'Escalations',          href: '/careers/erm/escalations' },
    { icon: LogOut,         label: 'Exit / Inactivate',    href: '/careers/erm/exits' },
    { icon: BarChart3,      label: 'Reports',              href: '/careers/erm/reports' },
    { icon: Settings,       label: 'Settings',             href: '/careers/erm/settings' },
  ],
  SUPER_ADMIN: [
    { icon: LayoutDashboard, label: 'Super Admin Dashboard', href: '/careers/admin' },
    { icon: Users,           label: 'Users',                 href: '/careers/admin/users' },
    { icon: Briefcase,       label: 'Job Postings',          href: '/careers/admin/postings' },
    { icon: Package,         label: 'Entities',              href: '/careers/admin/entities' },
    { icon: ShieldCheck,     label: 'Audit Log',             href: '/careers/admin/audit-log' },
  ],
};

// Mode that "unlocks" each module — used for the lock-tooltip copy. Falls
// back to a generic message for keys that have no single unlocking mode.
const MODULE_UNLOCK_MODE: Partial<Record<keyof InternModulesMap, string>> = {
  jobPostings: 'Applicant',
  interviewCenter: 'Interview',
  offerLetter: 'Offer',
  onboarding: 'New Hire',
  myProjects: 'Active Intern',
  timesheets: 'Active Intern',
  evaluations: 'Active Intern',
  doubts: 'Active Intern',
  messages: 'Applicant',
};

function pickActiveByRoute(pathname: string, routes: string[]): string | null {
  const matches = routes
    .filter((r) => pathname === r || pathname.startsWith(r + '/'))
    .sort((a, b) => b.length - a.length);
  return matches[0] ?? null;
}

/** Flatten a one-level-nested link list into the full route set used for
 *  pickActiveByRoute. Parent + every child are eligible to win the
 *  deepest-prefix match. */
function flattenRoutes(links: StaffLink[]): string[] {
  const out: string[] = [];
  for (const l of links) {
    out.push(l.href);
    if (l.children) for (const c of l.children) out.push(c.href);
  }
  return out;
}

interface Props {
  onNavigate?: () => void;
}

export default function DashboardSidebar({ onNavigate }: Props) {
  const pathname = usePathname() ?? '';
  const { user } = useAuth();
  const internDashboard = useInternDashboardOptional();
  const internModules = internDashboard?.data?.modules ?? null;

  // Pick the first role with a defined sidebar. Users with multiple roles
  // (rare) land on the first match in the declared role order.
  const activeRole = useMemo<UserRole | null>(() => {
    const roles = user?.roles ?? [];
    for (const r of roles) {
      if (r in STAFF_ROLE_LINKS) return r;
    }
    return null;
  }, [user?.roles]);

  const links = activeRole ? STAFF_ROLE_LINKS[activeRole] : null;

  return (
    <nav className="flex h-full w-60 shrink-0 flex-col border-r border-slate-200 bg-white">
      <Link
        href="/careers"
        onClick={onNavigate}
        className="flex h-14 shrink-0 items-center gap-2 border-b border-slate-200 px-4 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2"
      >
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={BRAND.logoUrl} alt={BRAND.name} className="h-7 w-auto" />
        <span className="text-sm font-semibold text-slate-900">Careers</span>
      </Link>

      <div className="flex-1 overflow-y-auto px-3 py-4">
        {links ? (
          <StaffNav
            links={links}
            pathname={pathname}
            onNavigate={onNavigate}
            internModules={internModules}
          />
        ) : (
          <SidebarSkeleton rows={4} />
        )}
      </div>

      <div className="space-y-1 border-t border-slate-200 px-3 py-3">
        {/* Mail bridge Phase 5 (revised) — the sidebar Mailbox link
            moved to a topbar peek (TopBarMailbox) beside the bell +
            profile, gated on the user actually having a linked
            mailbox. Sidebar no longer carries a Mailbox entry. */}
        {user && <SignOutButton variant="sidebar" onAfter={onNavigate} />}
        <Link
          href="/"
          onClick={onNavigate}
          className="block rounded-md px-3 py-2 text-xs text-slate-500 transition-colors hover:bg-slate-50 hover:text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2"
        >
          ← Back to main site
        </Link>
      </div>
    </nav>
  );
}

function StaffNav({
  links,
  pathname,
  onNavigate,
  internModules,
}: {
  links: StaffLink[];
  pathname: string;
  onNavigate?: () => void;
  internModules: InternModulesMap | null;
}) {
  const activeHref = pickActiveByRoute(pathname, flattenRoutes(links));
  return (
    <ul className="space-y-1">
      {links.map((link) => (
        <NavItem
          key={link.href}
          link={link}
          activeHref={activeHref}
          onNavigate={onNavigate}
          internModules={internModules}
        />
      ))}
    </ul>
  );
}

function NavItem({
  link,
  activeHref,
  onNavigate,
  internModules,
}: {
  link: StaffLink;
  activeHref: string | null;
  onNavigate?: () => void;
  internModules: InternModulesMap | null;
}) {
  const Icon = link.icon;
  const active = link.href === activeHref;
  const moduleState = link.moduleKey && internModules
    ? internModules[link.moduleKey]
    : null;

  if (moduleState && !moduleState.visible) return null;

  const locked = Boolean(moduleState?.locked);
  const readOnly = Boolean(moduleState?.readOnly);
  const hasChildren = !!link.children && link.children.length > 0;
  // Expand the section whenever the active route is the parent or any
  // of its children — keeps the user in context without an explicit
  // toggle. (We don't ship a manual collapse today; if needed later,
  // promote this to local state seeded from the same condition.)
  const sectionActive = hasChildren
    && (active || link.children!.some((c) => c.href === activeHref));

  if (locked) {
    const unlockMode = link.moduleKey
      ? MODULE_UNLOCK_MODE[link.moduleKey]
      : undefined;
    return (
      <li>
        <span
          title={unlockMode ? `Unlocks at ${unlockMode}` : 'Locked'}
          aria-disabled="true"
          className="relative flex cursor-not-allowed items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-slate-400 opacity-60"
        >
          <Icon className="h-[18px] w-[18px]" strokeWidth={2} />
          <span className="flex-1">{link.label}</span>
          <Lock className="h-3.5 w-3.5" strokeWidth={2} aria-hidden />
        </span>
      </li>
    );
  }

  return (
    <li>
      <Link
        href={link.href}
        onClick={onNavigate}
        className={
          'relative flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2 '
          + (active
            ? 'bg-slate-100 text-slate-900 before:absolute before:left-0 before:top-1/2 before:h-5 before:w-0.5 before:-translate-y-1/2 before:rounded-r before:bg-brand-700'
            : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900')
        }
        aria-current={active ? 'page' : undefined}
      >
        <Icon className="h-[18px] w-[18px]" strokeWidth={2} />
        <span className="flex-1">{link.label}</span>
        {hasChildren && (
          <ChevronRight
            aria-hidden
            className={
              'h-3.5 w-3.5 text-slate-400 transition-transform '
              + (sectionActive ? 'rotate-90' : '')
            }
            strokeWidth={2}
          />
        )}
        {readOnly && (
          <span className="text-[10px] uppercase tracking-wide text-slate-400">
            view only
          </span>
        )}
      </Link>
      {hasChildren && sectionActive && (
        <ul className="mt-1 ml-4 space-y-1 border-l border-slate-200 pl-2">
          {link.children!.map((child) => {
            const ChildIcon = child.icon;
            const childActive = child.href === activeHref;
            return (
              <li key={child.href}>
                <Link
                  href={child.href}
                  onClick={onNavigate}
                  className={
                    'relative flex items-center gap-2 rounded-md px-3 py-1.5 text-[13px] font-medium transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2 '
                    + (childActive
                      ? 'bg-slate-100 text-slate-900 before:absolute before:left-0 before:top-1/2 before:h-4 before:w-0.5 before:-translate-y-1/2 before:rounded-r before:bg-brand-700'
                      : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900')
                  }
                  aria-current={childActive ? 'page' : undefined}
                >
                  <ChildIcon className="h-4 w-4" strokeWidth={2} />
                  <span className="flex-1">{child.label}</span>
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </li>
  );
}

function SidebarSkeleton({ rows }: { rows: number }) {
  return (
    <ul className="space-y-1.5">
      {Array.from({ length: rows }).map((_, i) => (
        <li
          key={i}
          className="h-9 animate-pulse rounded-md bg-slate-100"
          aria-hidden
        />
      ))}
    </ul>
  );
}
