import Image from "next/image";
import Link from "next/link";

export function Logo({ className = "" }: { className?: string }) {
  return (
    <Link href="/" className={`inline-flex items-center ${className}`} aria-label="Anvi Corp USA — home">
      <Image
        src="/logo.png"
        alt="Anvi Corp USA"
        width={160}
        height={48}
        priority
        className="h-10 w-auto"
      />
    </Link>
  );
}
