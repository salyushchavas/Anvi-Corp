package com.anvicorp.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * Single source of truth for CORS allowed-origins across BOTH security chains:
 * the careers chain ({@code SecurityConfig}) which relies on the global
 * {@link CorsFilter} servlet filter, AND the mail chain
 * ({@code com.anvicorp.api.mail.config.MailSecurityConfig}) which wires the
 * exposed {@link CorsConfigurationSource} bean into its Spring Security
 * pipeline via {@code http.cors(c -> c.configurationSource(...))}.
 *
 * <p>Origins are read from the {@code cors.allowed-origins} property
 * (env var {@code CORS_ORIGINS} per {@code application.properties}). Adding a
 * domain to that one env var now covers both chains — the mail chain can no
 * longer drift away from the careers chain because there is only one
 * configuration to keep up to date.</p>
 */
@Configuration
public class CorsConfig {

    @Value("${cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();

        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource) {
        return new CorsFilter(corsConfigurationSource);
    }
}
