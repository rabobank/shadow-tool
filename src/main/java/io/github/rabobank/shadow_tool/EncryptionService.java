package io.github.rabobank.shadow_tool;

import org.bouncycastle.util.encoders.Base64;

import javax.crypto.Cipher;

class EncryptionService {
    private final Cipher cipher;

    EncryptionService(final Cipher cipher) {
        this.cipher = cipher;
    }

    String encrypt(final String value) {
        try {
            return Base64.toBase64String(cipher.doFinal(value.getBytes()));
        } catch (final Exception e) {
            throw new SecurityException(e);
        }
    }
}
