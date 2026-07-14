package com.anvicorp.api.mail.config;

import com.anvicorp.api.mail.auth.MailAccessDeniedHandler;
import com.anvicorp.api.mail.auth.MailAuthenticationEntryPoint;
import com.anvicorp.api.mail.auth.MailJwtAuthenticationFilter;
import com.anvicorp.api.mail.auth.MailJwtUtil;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Security chain for the mail module — the FIRST and (for now) ONLY
 * {@link SecurityFilterChain} in this backend.
 *
 * <ul>
 *   <li>{@code securityMatcher("/api/mail/**")} + {@code @Order(1)} scope it to
 *       mail routes. There is nothing else to coexist with today; the matcher +
 *       order are hygiene so a future careers chain (its own {@code @Order} /
 *       matcher) slots in WITHOUT touching this one.</li>
 *   <li>{@link MailJwtAuthenticationFilter} is instantiated here as a plain
 *       object (not a bean) so servlet auto-registration never makes it global —
 *       it runs only within this chain.</li>
 *   <li>Authorities are checked with {@code hasAuthority("MAIL_*")} only — never
 *       {@code hasRole}/{@code ROLE_} — so there is zero overlap with any future
 *       role world.</li>
 * </ul>
 *
 * <p><b>Must-change gate:</b> a pre-change principal carries only
 * {@code MAIL_PRECHANGE}, so the {@code authenticated()} routes ({@code /me} +
 * change-password) admit it while every {@code hasAuthority(MAIL_USER|ADMIN|
 * SUPER_ADMIN)} route rejects it with 403 — forcing the password change before
 * any real mail access.</p>
 */
@Configuration
@RequiredArgsConstructor
public class MailSecurityConfig {

    private final MailJwtUtil mailJwtUtil;
    private final MailAccountRepository mailAccountRepository;
    private final MailAuthenticationEntryPoint mailAuthenticationEntryPoint;
    private final MailAccessDeniedHandler mailAccessDeniedHandler;
    private final CorsConfigurationSource corsConfigurationSource;

    /** Shared BCrypt encoder for mail account password hashing/verification. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain mailFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/mail/**")
                // CORS wired explicitly into THIS chain via the shared
                // CorsConfigurationSource bean (single allowed-origins list).
                .cors(c -> c.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Authorization runs only on the initial REQUEST dispatch
                        // (not ASYNC/ERROR/FORWARD re-dispatches) — avoids spurious
                        // re-checks on async responses (e.g. a future SSE endpoint)
                        // without weakening any real authorization boundary.
                        .shouldFilterAllDispatcherTypes(false)
                        // CORS preflight.
                        .requestMatchers(HttpMethod.OPTIONS, "/api/mail/**").permitAll()
                        // Auth endpoints are open (login / refresh / logout).
                        .requestMatchers("/api/mail/auth/**").permitAll()
                        // Any authenticated principal (incl. pre-change) may read
                        // /me and change their password — the way OUT of the gate.
                        .requestMatchers(HttpMethod.GET, "/api/mail/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/mail/me/change-password").authenticated()
                        // Admin surface (controllers land in a later phase).
                        .requestMatchers("/api/mail/admin/**")
                            .hasAnyAuthority("MAIL_ADMIN", "MAIL_SUPER_ADMIN")
                        // Everything else under /api/mail/** needs a full (non
                        // pre-change) role authority.
                        .requestMatchers("/api/mail/**")
                            .hasAnyAuthority("MAIL_USER", "MAIL_ADMIN", "MAIL_SUPER_ADMIN")
                        .anyRequest().denyAll()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(mailAuthenticationEntryPoint)
                        .accessDeniedHandler(mailAccessDeniedHandler)
                )
                .addFilterBefore(
                        new MailJwtAuthenticationFilter(mailJwtUtil, mailAccountRepository),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
