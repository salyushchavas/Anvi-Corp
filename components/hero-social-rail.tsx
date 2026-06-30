import Link from "next/link";
import { ChevronDown } from "lucide-react";
import { FacebookIcon, InstagramIcon, LinkedInIcon, TwitterIcon } from "./social-icons";

/** Vertical social rail + scroll-down arrow, mirrors the legacy `.left_side` block. */
export function HeroSocialRail() {
  const items = [
    { href: "#", label: "Instagram", Icon: InstagramIcon },
    { href: "#", label: "Facebook",  Icon: FacebookIcon },
    { href: "#", label: "Twitter",   Icon: TwitterIcon },
    { href: "#", label: "LinkedIn",  Icon: LinkedInIcon },
  ];
  return (
    <div className="hidden md:flex absolute right-6 top-1/2 -translate-y-1/2 z-10 flex-col items-center gap-5">
      {items.map(({ href, label, Icon }) => (
        <Link
          key={label}
          href={href}
          aria-label={label}
          className="text-white/80 hover:text-brand transition-colors"
        >
          <Icon className="h-5 w-5" />
        </Link>
      ))}
      <Link
        href="#services"
        aria-label="Scroll to services"
        className="mt-6 inline-flex h-9 w-9 items-center justify-center rounded-full border border-white/30 text-white/80 hover:border-brand hover:text-brand animate-bounce"
      >
        <ChevronDown className="h-4 w-4" />
      </Link>
    </div>
  );
}
