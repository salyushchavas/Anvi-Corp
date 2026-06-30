import Image from "next/image";
import Link from "next/link";

export function Logo({ className = "" }: { className?: string }) {
  return (
    <Link href="/" className={`inline-flex items-center ${className}`} aria-label="Anvi Corp USA — home">
      <Image
        src="/logo.png"
        alt="Anvi Corp USA"
        width={220}
        height={64}
        priority
        className="h-12 lg:h-14 w-auto"
      />
    </Link>
  );
}
