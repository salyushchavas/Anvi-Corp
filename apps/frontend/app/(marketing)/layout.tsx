import { SiteHeader } from "@/components/site-header";
import { SiteFooter } from "@/components/site-footer";
import { BackToTop } from "@/components/back-to-top";

// Marketing chrome, moved out of the root layout so the mail app can opt out of
// it. Every marketing page (home, services/*, contact, privacy-policy, careers)
// lives under this group; route groups do not change their URLs.
export default function MarketingLayout({ children }: { children: React.ReactNode }) {
  return (
    <>
      <SiteHeader />
      <main>{children}</main>
      <SiteFooter />
      <BackToTop />
    </>
  );
}
