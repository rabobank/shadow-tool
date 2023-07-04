package io.github.rabobank.shadow_tool;

import org.bouncycastle.util.encoders.Base64;

import javax.crypto.Cipher;

class DefaultEncryptionService implements EncryptionService {
    private final Cipher cipher;

    DefaultEncryptionService(final Cipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String encrypt(final String value) {
        try {
            return Base64.toBase64String(cipher.doFinal(value.getBytes()));
        } catch (final Exception e) {
            throw new SecurityException(e);
        }
    }
}
