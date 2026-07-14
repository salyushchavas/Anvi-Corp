import Link from 'next/link';
import { type LucideIcon } from 'lucide-react';

interface Props {
  icon: LucideIcon;
  heading: string;
  /** Where the "Back to dashboard" link points (e.g. /careers/admin). */
  backHref: string;
  message?: string;
}

export default function ComingSoon({
  icon: Icon,
  heading,
  backHref,
  message = "Coming soon. We're actively building this feature.",
}: Props) {
  return (
    <div className="mx-auto max-w-md py-16 text-center">
      <Icon className="mx-auto h-16 w-16 text-gray-300" strokeWidth={1.5} />
      <h2 className="mt-4 text-xl font-semibold text-gray-900">{heading}</h2>
      <p className="mt-2 text-sm text-gray-500">{message}</p>
      <Link
        href={backHref}
        className="mt-6 inline-block text-sm text-primary-700 transition-colors hover:text-primary-800 hover:underline"
      >
        &larr; Back to dashboard
      </Link>
    </div>
  );
}
