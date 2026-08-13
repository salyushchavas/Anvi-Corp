import type { MetadataRoute } from "next";
import { BRAND } from "@/lib/careers/brand";

// Phase-0 batch 2 — the sitemap base URL flows from BRAND.siteBaseUrl
// (env-driven at build time). Default preserves the pre-migration
// bare canonical (no `www.` prefix). Route list unchanged.
const BASE = BRAND.siteBaseUrl;

export default function sitemap(): MetadataRoute.Sitemap {
  const now = new Date();
  const routes = [
    { url: "/",                                                 priority: 1.0,  changeFrequency: "monthly" as const },
    { url: "/services/software-development",                    priority: 0.9,  changeFrequency: "monthly" as const },
    { url: "/services/cloud-development",                       priority: 0.9,  changeFrequency: "monthly" as const },
    { url: "/services/mobile-application-development",          priority: 0.9,  changeFrequency: "monthly" as const },
    { url: "/services/it-consulting",                           priority: 0.9,  changeFrequency: "monthly" as const },
    { url: "/careers",                                          priority: 0.7,  changeFrequency: "weekly"  as const },
    { url: "/contact",                                          priority: 0.8,  changeFrequency: "yearly"  as const },
    { url: "/privacy",                                          priority: 0.3,  changeFrequency: "yearly"  as const },
  ];
  return routes.map(r => ({ ...r, url: `${BASE}${r.url}`, lastModified: now }));
}
