package com.anvicorp.api.config;

import com.anvicorp.api.auth.CsrfDoubleSubmitFilter;
import com.anvicorp.api.auth.CustomAccessDeniedHandler;
import com.anvicorp.api.auth.CustomAuthenticationEntryPoint;
import com.anvicorp.api.auth.ForcePasswordChangeFilter;
import com.anvicorp.api.auth.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ForcePasswordChangeFilter forcePasswordChangeFilter;
    private final CsrfDoubleSubmitFilter csrfDoubleSubmitFilter;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;

    /**
     * Security Wave 2 — Content-Security-Policy sent on every backend
     * response. The backend serves JSON only, so the policy is tight by
     * design: no scripts/styles/inline at all, images/fonts denied.
     * Frame-ancestors 'none' complements X-Frame-Options: DENY.
     */
    private static final String BACKEND_CSP =
            "default-src 'none'; "
            + "frame-ancestors 'none'; "
            + "base-uri 'none'; "
            + "form-action 'none'";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Security Wave 2 — Spring's built-in CSRF stays disabled
                // because we use a stateless (JWT) session and enforce
                // double-submit via {@link CsrfDoubleSubmitFilter} below.
                // Spring's default CookieCsrfTokenRepository would collide
                // with the double-submit cookie and confuse the client.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Security Wave 2 (S2) — hardening headers on every response.
                // HSTS with 1yr max-age + subdomains is the industry default;
                // preload is intentionally left off until an operator explicitly
                // registers the domain with the browser preload list.
                // frame-ancestors 'none' in the CSP + X-Frame-Options: DENY
                // provides belt-and-suspenders clickjacking cover.
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .httpStrictTransportSecurity(h -> h
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000))
                        .xssProtection(x -> x.headerValue(
                                XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(BACKEND_CSP))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/auth/register",
                                "/auth/login",
                                // Security Wave 2 — logout must be reachable
                                // without a valid session so a client with a
                                // torn cookie can still clear its bits and get
                                // a clean 200.
                                "/auth/logout",
                                // /auth/refresh MUST be reachable without
                                // a valid access token — the whole point
                                // is to redeem a refresh token AFTER the
                                // access token expired. JwtAuthenticationFilter
                                // already SKIP_PATHS this URL, so without
                                // permitAll the request falls through to
                                // .anyRequest().authenticated() and 401s
                                // before AuthService.refresh runs. The
                                // refresh-token credential itself is
                                // validated (existence + ownership + not
                                // revoked + not expired) inside
                                // SessionTokenService.rotate.
                                "/auth/refresh",
                                "/auth/forgot-password",
                                "/auth/reset-password",
                                "/auth/verify-email",
                                "/auth/resend-verification",
                                // Admin-invite activation flow. Both
                                // endpoints validate the raw token
                                // internally (hash + lookup + expiry +
                                // single-use); no authenticated session
                                // exists yet for an invited staff user.
                                "/auth/activate/validate",
                                "/auth/activate"
                        ).permitAll()
                        .requestMatchers("/health", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/job-postings").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/job-postings/*").permitAll()
                        // Phase 2 — public read of the doc-spec /jobs endpoints.
                        .requestMatchers(HttpMethod.GET, "/api/v1/jobs").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/jobs/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/jobs/featured").permitAll()
                        // Signing is now IDMS (in-house) — no inbound
                        // third-party webhook endpoint to permit. The
                        // applicant signs at /careers/intern/offer/sign/{id}.
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                // Security Wave 2 — CSRF double-submit runs BEFORE the JWT
                // filter so an invalid CSRF header short-circuits before we
                // burn a JWT parse. Skips GET/HEAD/OPTIONS and the auth
                // endpoints (login/logout/refresh/register/reset/verify/
                // resend/activate) — see CsrfDoubleSubmitFilter for the
                // exempt list.
                .addFilterBefore(csrfDoubleSubmitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // ForcePasswordChangeFilter must run AFTER jwtAuthenticationFilter
                // so SecurityContext already holds the authenticated User — that's
                // where the must_change_password flag lives. Anonymous requests
                // fall through this filter untouched.
                .addFilterAfter(forcePasswordChangeFilter, JwtAuthenticationFilter.class);
        return http.build();
    }
}
