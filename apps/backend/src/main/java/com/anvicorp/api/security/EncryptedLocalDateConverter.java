package com.anvicorp.api.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * GAP_REPORT C7 — LocalDate<->String AttributeConverter for I-9 date_of_birth.
 * DOB is a DATE column in the original schema; once attached, this converter
 * stores the ISO-8601 date as encrypted text. The companion column-widening
 * DDL in {@code SchemaFixupRunner} changes the DB column type from
 * {@code DATE} to {@code TEXT} so the encrypted envelope fits.
 *
 * Plain-text DOB → ISO yyyy-MM-dd → AES-256-GCM encrypt → base64.
 * Reverse on read. Null in → null out.
 *
 * Only attached to {@code I9Form.dateOfBirth}. {@code workAuthExpirationDate},
 * {@code firstDayOfEmployment}, and the List A/B/C expiration dates stay as
 * unencrypted DATE — those are not direct PII identifiers, just timing data.
 */
@Component
@Converter(autoApply = false)
public class EncryptedLocalDateConverter implements AttributeConverter<LocalDate, String> {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final AesGcmCipher cipher;

    public EncryptedLocalDateConverter(AesGcmCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(LocalDate attribute) {
        if (attribute == null) return null;
        return cipher.encrypt(attribute.format(ISO));
    }

    @Override
    public LocalDate convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        String plain = cipher.decrypt(dbData);
        return plain == null ? null : LocalDate.parse(plain, ISO);
    }
}
