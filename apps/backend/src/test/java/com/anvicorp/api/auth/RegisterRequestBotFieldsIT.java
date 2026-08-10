package com.anvicorp.api.auth;

import com.anvicorp.api.auth.dto.RegisterRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bean-Validation coverage for the two bot-mitigation fields on
 * {@link RegisterRequest} — {@code captchaToken} and {@code
 * companyWebsite} (honeypot). Neither is REQUIRED at the DTO level;
 * the enforcement happens in {@link AuthController#register}. What we
 * pin here is that the fields exist, are size-capped, and don't
 * accidentally acquire a {@code @NotBlank} that would break older
 * clients rolling out.
 */
class RegisterRequestBotFieldsIT {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) factory.close();
    }

    private static Set<ConstraintViolation<RegisterRequest>>
            violate(String captchaToken, String companyWebsite) {
        return validator.validate(new RegisterRequest(
                "user@example.com", "very-strong-password", "Real User",
                "Real Legal Name", true, captchaToken, companyWebsite));
    }

    @Test
    void blank_captcha_token_is_valid_at_dto_level() {
        // Enforcement is in the controller (TurnstileVerifier). The DTO
        // stays permissive so older clients that haven't shipped the
        // widget yet don't 400 on the DTO layer.
        assertTrue(violate(null, null).isEmpty());
        assertTrue(violate("", null).isEmpty());
    }

    @Test
    void blank_honeypot_is_valid() {
        // Real users leave the honeypot blank — this is the happy path.
        assertTrue(violate(null, null).isEmpty());
        assertTrue(violate(null, "").isEmpty());
    }

    @Test
    void filled_honeypot_is_still_valid_at_dto_level_but_rejected_in_controller() {
        // The DTO tolerates a value up to 200 chars — the controller
        // gate is what rejects. This split lets us log a distinct
        // "honeypot tripped" line without conflating with generic
        // validation errors.
        assertTrue(violate(null, "https://spammy.example.com").isEmpty());
    }

    @Test
    void oversized_captcha_token_rejected() {
        String huge = "a".repeat(4001);
        Set<ConstraintViolation<RegisterRequest>> v = violate(huge, null);
        assertEquals(1, v.size(),
                "captchaToken over 4000 chars should be rejected — "
                        + "Turnstile tokens are ~200-400 chars; anything past 4kB "
                        + "is definitively junk.");
    }

    @Test
    void oversized_honeypot_rejected() {
        String huge = "a".repeat(201);
        Set<ConstraintViolation<RegisterRequest>> v = violate(null, huge);
        assertEquals(1, v.size(),
                "companyWebsite honeypot capped at 200 chars — bounds "
                        + "the payload a bot can jam through before the "
                        + "controller drops it.");
    }
}
