import type { MetadataRoute } from "next";
import { BRAND } from "@/lib/careers/brand";

// Phase-0 batch 2 — the sitemap URL flows from BRAND.siteBaseUrl. The
// robots rules are brand-agnostic and kept as-is.
export default function robots(): MetadataRoute.Robots {
  return {
    rules: [
      { userAgent: "*", allow: "/", disallow: ["/api/"] },
    ],
    sitemap: `${BRAND.siteBaseUrl}/sitemap.xml`,
  };
}
