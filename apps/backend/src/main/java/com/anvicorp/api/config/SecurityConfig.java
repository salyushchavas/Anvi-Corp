package com.anvicorp.api.config;

import com.anvicorp.api.auth.CustomAccessDeniedHandler;
import com.anvicorp.api.auth.CustomAuthenticationEntryPoint;
import com.anvicorp.api.auth.ForcePasswordChangeFilter;
import com.anvicorp.api.auth.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ForcePasswordChangeFilter forcePasswordChangeFilter;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/auth/register",
                                "/auth/login",
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
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // ForcePasswordChangeFilter must run AFTER jwtAuthenticationFilter
                // so SecurityContext already holds the authenticated User — that's
                // where the must_change_password flag lives. Anonymous requests
                // fall through this filter untouched.
                .addFilterAfter(forcePasswordChangeFilter, JwtAuthenticationFilter.class);
        return http.build();
    }
}
