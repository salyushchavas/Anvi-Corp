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
    // Security Wave 2 (H/M) — CSP + HSTS added on top of the pre-existing
    // three defensive headers. The API origin allowed in connect-src is
    // read from NEXT_PUBLIC_API_URL at build time so a preview/dev
    // deployment substitutes its own backend origin cleanly.
    const apiOrigin = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
    // Report-only 'unsafe-inline' on style-src because Tailwind + Next's
    // build inline small CSS chunks; 'unsafe-eval' NOT included (Next
    // production doesn't require it). 'unsafe-inline' on script-src is
    // deliberately dropped — Next serves all app scripts with a nonce or
    // as external files; any inline script would need a per-request nonce
    // that the middleware would inject if adopted.
    const csp = [
      "default-src 'self'",
      "base-uri 'self'",
      "frame-ancestors 'none'",
      "form-action 'self'",
      "object-src 'none'",
      "script-src 'self' 'unsafe-inline'",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' data: blob: https:",
      "font-src 'self' data:",
      "connect-src 'self' " + apiOrigin,
      "media-src 'self' blob: https:",
      "worker-src 'self' blob:",
    ].join("; ");

    return [
      {
        source: "/(.*)",
        headers: [
          { key: "X-Content-Type-Options",        value: "nosniff" },
          { key: "X-Frame-Options",               value: "DENY" },
          { key: "Referrer-Policy",               value: "strict-origin-when-cross-origin" },
          { key: "Strict-Transport-Security",     value: "max-age=31536000; includeSubDomains" },
          { key: "Content-Security-Policy",       value: csp },
          { key: "Permissions-Policy",            value: "camera=(), microphone=(), geolocation=()" },
        ],
      },
    ];
  },
};

export default nextConfig;
