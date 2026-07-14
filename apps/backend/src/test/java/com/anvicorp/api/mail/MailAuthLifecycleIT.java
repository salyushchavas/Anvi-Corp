package com.anvicorp.api.mail;

import com.anvicorp.api.bootstrap.MailAdminSeeder;
import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End-to-end Phase A1 verification against in-memory H2: the seeder, the full
 * mail auth lifecycle, and the must-change gate. With a valid MAIL_JWT_SECRET
 * configured (test profile), this is the "configured" path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:anvi_a1_life;DB_CLOSE_DELAY=-1")
class MailAuthLifecycleIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MailAccountRepository accounts;
    @Autowired MailAdminSeeder seeder;

    private static final String EMAIL = "admin@anvicorp.com";
    private static final String SEED_PW = "ChangeMe-Seed-123";

    @Test
    void mailIdentityAuthAndMustChangeGate() throws Exception {
        // ── A. Seeder created tables + a hashed, must-change super-admin ──────
        MailAccount seeded = accounts.findByLocalPartAndDomain_Name("admin", "anvicorp.com")
                .orElseThrow(() -> new AssertionError("seeder did not create the super-admin"));
        assertTrue(seeded.getPasswordHash().startsWith("$2"), "password must be BCrypt-hashed");
        assertNotEquals(SEED_PW, seeded.getPasswordHash(), "password hash must not be plaintext");
        assertTrue(seeded.getMustChangePassword(), "seeded account must require a password change");
        assertEquals("SUPER_ADMIN", seeded.getRole().name());

        // Idempotent: re-running the seeder does not create a duplicate.
        long before = accounts.count();
        seeder.run();
        assertEquals(before, accounts.count(), "seeder must be idempotent");

        // ── B. Wrong password → generic 401 (no enumeration) ─────────────────
        mvc.perform(post("/api/mail/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(login(EMAIL, "totally-wrong")))
                .andExpect(r -> assertEquals(401, r.getResponse().getStatus()))
                .andExpect(r -> assertTrue(r.getResponse().getContentAsString().contains("MAIL_LOGIN_FAILED")));

        // ── C. Login → 200 + tokens + mustChangePassword=true ────────────────
        JsonNode login = body(mvc.perform(post("/api/mail/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(login(EMAIL, SEED_PW)))
                .andReturn());
        String preToken = login.get("token").asText();
        String preRefresh = login.get("refreshToken").asText();
        assertNotNull(preToken);
        assertTrue(login.get("mustChangePassword").asBoolean(), "login must report must-change");

        // ── D. Must-change gate: pre-change token reaches ONLY /me ───────────
        mvc.perform(get("/api/mail/me").header("Authorization", "Bearer " + preToken))
                .andExpect(r -> assertEquals(200, r.getResponse().getStatus()));
        // Any non-/me mail route is 403 for a pre-change principal.
        mvc.perform(get("/api/mail/admin/anything").header("Authorization", "Bearer " + preToken))
                .andExpect(r -> assertEquals(403, r.getResponse().getStatus()));

        // ── E. Change password → fresh ungated token, flag cleared ───────────
        JsonNode changed = body(mvc.perform(post("/api/mail/me/change-password")
                .header("Authorization", "Bearer " + preToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword", SEED_PW,
                        "newPassword", "BrandNew-Pw-456"))))
                .andExpect(r -> assertEquals(200, r.getResponse().getStatus()))
                .andReturn());
        String newToken = changed.get("token").asText();
        String newRefresh = changed.get("refreshToken").asText();
        assertFalse(changed.get("mustChangePassword").asBoolean(), "flag must be cleared after change");

        // ── F. Gate lifted: the fresh super-admin token clears authz ─────────
        // (admin route now passes authorization → 404 no-handler, NOT 403).
        mvc.perform(get("/api/mail/admin/anything").header("Authorization", "Bearer " + newToken))
                .andExpect(r -> assertEquals(404, r.getResponse().getStatus()));
        JsonNode me = body(mvc.perform(get("/api/mail/me").header("Authorization", "Bearer " + newToken))
                .andReturn());
        assertFalse(me.get("mustChangePassword").asBoolean());

        // Old pre-change refresh token was revoked by the password change.
        mvc.perform(post("/api/mail/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refresh(preRefresh)))
                .andExpect(r -> assertEquals(401, r.getResponse().getStatus()));

        // ── G. Refresh rotation is single-use ────────────────────────────────
        JsonNode rotated = body(mvc.perform(post("/api/mail/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON).content(refresh(newRefresh)))
                .andExpect(r -> assertEquals(200, r.getResponse().getStatus()))
                .andReturn());
        String refresh2 = rotated.get("refreshToken").asText();
        // Re-presenting the already-rotated token fails.
        mvc.perform(post("/api/mail/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refresh(newRefresh)))
                .andExpect(r -> assertEquals(401, r.getResponse().getStatus()));

        // ── H. Logout revokes the refresh token ──────────────────────────────
        mvc.perform(post("/api/mail/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content(refresh(refresh2)))
                .andExpect(r -> assertEquals(204, r.getResponse().getStatus()));
        mvc.perform(post("/api/mail/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refresh(refresh2)))
                .andExpect(r -> assertEquals(401, r.getResponse().getStatus()));
    }

    private String login(String email, String pw) throws Exception {
        return json.writeValueAsString(Map.of("email", email, "password", pw));
    }

    private String refresh(String token) throws Exception {
        return json.writeValueAsString(Map.of("refreshToken", token));
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }
}
