package com.anvicorp.api.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * GAP_REPORT C7 — String<->String JPA AttributeConverter that delegates to
 * {@link AesGcmCipher}. Encrypts on write, decrypts on read — transparent to
 * service / controller / DTO layers.
 *
 * Registered as a Spring {@code @Component} so Hibernate's SpringBeanContainer
 * (wired by Spring Boot 3 by default) can inject {@link AesGcmCipher} via
 * constructor instead of falling back to no-arg + static-field tricks.
 *
 * {@code autoApply = false}: only the I-9 fields annotated with
 * {@code @Convert(converter = AesGcmCryptoConverter.class)} get encrypted.
 * Every other String column on every other entity is untouched.
 */
@Component
@Converter(autoApply = false)
public class AesGcmCryptoConverter implements AttributeConverter<String, String> {

    private final AesGcmCipher cipher;

    public AesGcmCryptoConverter(AesGcmCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher.decrypt(dbData);
    }
}
