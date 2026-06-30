import type { MetadataRoute } from "next";

const BASE = "https://anvicorp.com";

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
    { url: "/privacy-policy",                                   priority: 0.3,  changeFrequency: "yearly"  as const },
  ];
  return routes.map(r => ({ ...r, url: `${BASE}${r.url}`, lastModified: now }));
}
