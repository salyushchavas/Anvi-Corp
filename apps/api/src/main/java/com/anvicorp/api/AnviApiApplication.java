package com.anvicorp.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Anvi Corp backend entry point.
 *
 * <p>The first (and currently only) feature is the internal web-mail module
 * under {@code com.anvicorp.api.mail} — a self-contained mail identity
 * population with its own JWT secret and security chain ({@code /api/mail/**}).
 * A future careers backend will live alongside it with its own independent
 * identity; nothing here couples mail to that future work.</p>
 */
@SpringBootApplication
public class AnviApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnviApiApplication.class, args);
    }
}
