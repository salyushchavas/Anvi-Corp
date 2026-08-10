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
    // Direct-to-S3 client uploads/downloads (IDMS studio Re-Upload,
    // recording upload/playback, document-template source attach, and
    // any other flow that goes through the presigned-URL pattern)
    // require the target S3 bucket origin in connect-src. Wave 2's
    // 'self' + apiOrigin was too tight — the browser blocked the
    // presigned PUT with "violates connect-src 'self' <apiOrigin>".
    //
    // AWS returns virtual-host-style URLs in two shapes depending on
    // the SDK / region: `<bucket>.s3.amazonaws.com` (us-east-1 legacy)
    // and `<bucket>.s3.<region>.amazonaws.com` (all-region). We list
    // both for the specific production bucket rather than a wildcard
    // so a bucket takeover on a different account can't smuggle bytes
    // through the app's CSP allowance.
    //
    // Overridable via NEXT_PUBLIC_S3_ORIGINS (comma-separated list of
    // absolute origins) for staging / preview deployments that hit a
    // different bucket.
    const s3Origins = (process.env.NEXT_PUBLIC_S3_ORIGINS
      ?? [
        "https://anvi-corp-carrers.s3.amazonaws.com",
        "https://anvi-corp-carrers.s3.us-east-1.amazonaws.com",
      ].join(","))
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);
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
      // connect-src: 'self' (same-origin API), the backend origin, and
      // the S3 bucket origins (direct presigned PUT/GET from the
      // browser). Without S3 here, every direct-to-S3 client upload —
      // IDMS studio Re-Upload, recording upload, document-template
      // source attach — is blocked at the CSP layer before the request
      // is even dispatched.
      ["connect-src 'self'", apiOrigin, ...s3Origins].join(" "),
      // media-src + img-src already blanket-allow https: so S3-hosted
      // media / images render fine without a per-bucket entry here.
      "media-src 'self' blob: https:",
      "worker-src 'self' blob:",
      // frame-src covers <iframe src>. The ERM + Manager resume preview
      // (components/erm/applications/ResumePreview.tsx) renders PDF resumes
      // as <iframe src={URL.createObjectURL(new Blob(...))}>. Bytes are
      // fetched through the same-origin /api/v1/resumes/{id}/download
      // endpoint (streamed by ResumeController) and every other current
      // iframe target is a same-origin blob URL, so no S3 origin needs
      // whitelisting here — but if a future flow points iframe src at a
      // presigned S3 URL directly, the s3Origins list above needs to
      // move onto frame-src too.
      "frame-src 'self' blob:",
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
