package io.github.rabobank.shadow_tool;

import org.bouncycastle.util.encoders.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A version of the Encryption Service that doesn't perform any encryption,
 * it only encodes the differences as a Base 64 String.
 * <p>
 * This might be useful for simple use-case where encryption of differences is not required,
 * for example with public data.
 *
 * @see Base64#toBase64String(byte[])
 */
public class NoopEncryptionService implements EncryptionService {
    public static final NoopEncryptionService INSTANCE = new NoopEncryptionService();

    NoopEncryptionService() {
    }

    @Override
    public String encrypt(final String value) {
        return Base64.toBase64String(value.getBytes(UTF_8));
    }
}
