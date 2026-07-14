package com.anvicorp.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Single source of truth for CORS allowed-origins. The mail chain
 * ({@link MailSecurityConfig}) wires this {@link CorsConfigurationSource} into
 * its Spring Security pipeline via {@code http.cors(c -> c.configurationSource(
 * ...))}, so cross-origin callers (the Vercel-hosted web app hitting the
 * Railway-hosted API) get the right {@code Access-Control-Allow-Origin}.
 *
 * <p>Origins come from the {@code cors.allowed-origins} property (env var
 * {@code CORS_ORIGINS}), comma-separated. Empty/unset → no cross-origin
 * permitted (the app still boots).</p>
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
}
