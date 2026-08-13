import Image from "next/image";
import Link from "next/link";
import { BRAND } from "@/lib/careers/brand";

export function Logo({ className = "" }: { className?: string }) {
  return (
    <Link
      href="/"
      className={`inline-flex items-center ${className}`}
      aria-label={`${BRAND.legalName} — home`}
    >
      <Image
        src={BRAND.logoUrl}
        alt={BRAND.legalName}
        width={220}
        height={64}
        priority
        className="h-12 lg:h-14 w-auto"
      />
    </Link>
  );
}
