package com.anvicorp.api.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Fail-closed verification: with MAIL_JWT_SECRET UNSET (blank) the app still
 * BOOTS, but the mail auth chain mints/accepts NO tokens under any fallback key.
 * Login (correct credentials) returns 503 MAIL_NOT_CONFIGURED and protected
 * endpoints reject with 401.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:anvi_a1_fc;DB_CLOSE_DELAY=-1",
        "app.webmail.jwt.secret="   // unset → fail closed
})
class MailFailClosedIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void loginIs503AndProtectedEndpointsReject() throws Exception {
        // Correct credentials, but no signing key → minting fails closed (503),
        // never a token under a default key.
        mvc.perform(post("/api/mail/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("email", "admin@anvicorp.com", "password", "ChangeMe-Seed-123"))))
                .andExpect(r -> assertEquals(503, r.getResponse().getStatus()))
                .andExpect(r -> assertTrue(r.getResponse().getContentAsString().contains("MAIL_NOT_CONFIGURED")));

        // A bearer token cannot be validated (no key) → unauthenticated → 401.
        mvc.perform(get("/api/mail/me").header("Authorization", "Bearer any.bogus.token"))
                .andExpect(r -> assertEquals(401, r.getResponse().getStatus()));
    }
}
