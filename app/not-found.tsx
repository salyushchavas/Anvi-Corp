import Link from "next/link";
import { ArrowLeft } from "lucide-react";

export default function NotFound() {
  return (
    <section className="container py-24 lg:py-32 text-center">
      <p className="text-sm font-semibold uppercase tracking-[0.2em] text-brand mb-4">404 — Not found</p>
      <h1 className="mb-4">We couldn&apos;t find that page.</h1>
      <p className="text-lg text-ink-400 max-w-md mx-auto mb-10">
        The page you&apos;re looking for may have moved or no longer exists.
      </p>
      <Link
        href="/"
        className="inline-flex items-center gap-2 rounded-full bg-brand px-7 py-3.5 font-semibold text-white shadow-card hover:bg-brand-600 hover:shadow-cardHover transition"
      >
        <ArrowLeft className="h-4 w-4" /> Back to home
      </Link>
    </section>
  );
}
