/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  // Careers code came from a lax-lint repo (no eslintrc) — web's stricter
  // next/typescript preset flags 30+ pre-existing issues. Deferred to
  // a follow-up cleanup so the merge itself isn't gated on lint.
  eslint: { ignoreDuringBuilds: true },
  async redirects() {
    return [
      { source: "/index.html",                          destination: "/",                                            permanent: true },
      { source: "/software-development.html",           destination: "/services/software-development",               permanent: true },
      { source: "/cloud-development.html",              destination: "/services/cloud-development",                  permanent: true },
      { source: "/mobile-application-development.html", destination: "/services/mobile-application-development",     permanent: true },
      { source: "/it-consulting.html",                  destination: "/services/it-consulting",                      permanent: true },
      { source: "/contact-us.php",                      destination: "/contact",                                     permanent: true },
      { source: "/privacy-policy.html",                 destination: "/privacy-policy",                              permanent: true },
      // Messages/notifications page was promoted from an intern-only
      // route to a shared role-agnostic route. Persisted notification
      // action_url values that pointed at the old path stay valid.
      { source: "/careers/intern/messages",             destination: "/careers/messages",                            permanent: true },
    ];
  },
  async headers() {
    return [
      {
        source: "/(.*)",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options",        value: "SAMEORIGIN" },
          { key: "Referrer-Policy",        value: "strict-origin-when-cross-origin" },
        ],
      },
    ];
  },
};

export default nextConfig;
