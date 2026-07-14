import Link from 'next/link';
import SiteFooter from '@/components/SiteFooter';
import { BRAND } from '@/lib/brand';

interface Props {
  children: React.ReactNode;
  title?: string;
  subtitle?: string;
  /**
   * Set to true for forms that need more horizontal room (e.g. the multi-card
   * registration page). Default narrow width keeps login / forgot / verify
   * uncluttered.
   */
  wide?: boolean;
}

export default function AuthLayout({ children, title, subtitle, wide }: Props) {
  const width = wide ? 'max-w-5xl' : 'max-w-md';
  return (
    <div className="flex min-h-screen flex-col bg-gradient-to-br from-gray-50 to-gray-100">
      <main className="flex flex-1 items-center justify-center p-4 py-8">
        <div className={`mx-auto w-full ${width}`}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={BRAND.logoUrl}
            alt={BRAND.name}
            className="mx-auto mb-6 h-10 w-auto"
          />
          {title && (
            <h1 className="text-center text-2xl font-semibold text-gray-900">
              {title}
            </h1>
          )}
          {subtitle && (
            <p className="mt-1 text-center text-sm text-gray-500">{subtitle}</p>
          )}
          <div
            className={
              'mt-6 rounded-xl border border-gray-200 bg-white shadow-sm ' +
              (wide ? 'p-6 sm:p-8' : 'p-8')
            }
          >
            {children}
          </div>
          <p className="mt-6 text-center text-sm text-gray-500">
            <Link href="/" className="hover:text-gray-700">
              &larr; Back to main site
            </Link>
          </p>
        </div>
      </main>
      <SiteFooter />
    </div>
  );
}
