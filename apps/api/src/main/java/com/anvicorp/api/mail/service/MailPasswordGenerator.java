package com.anvicorp.api.mail.service;

import java.security.SecureRandom;

/**
 * Generates strong, copy-friendly one-time passwords for admin provisioning.
 * Unambiguous alphabet — no 0/O/o, 1/l/I — so a password read aloud or copied
 * from the show-once response isn't mistyped. 16 alphanumeric chars from a
 * 55-symbol alphabet ≈ 92 bits of entropy.
 *
 * <p>Reserved for the admin-provisioning phase; nothing in Phase A1 calls it
 * (the bootstrap seeder uses an operator-supplied password), but it is brought
 * in now so the provisioning surface has it ready.</p>
 */
public final class MailPasswordGenerator {

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".toCharArray();

    private MailPasswordGenerator() {
    }

    public static String generate(int length) {
        int len = Math.max(length, 12);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET[RNG.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
