package io.github.rabobank.shadow_tool;

import org.bouncycastle.util.encoders.Base64;

import javax.crypto.Cipher;
import java.security.GeneralSecurityException;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The default Encryption Service used by Shadow Tool. It uses a {@link Cipher} to encrypt values.
 *
 * @see Cipher
 */
public class DefaultEncryptionService implements EncryptionService {
    private final Cipher cipher;

    public DefaultEncryptionService(final Cipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String encrypt(final String value) {
        try {
            return Base64.toBase64String(cipher.doFinal(value.getBytes(UTF_8)));
        } catch (final GeneralSecurityException e) {
            throw new SecurityException(e);
        }
    }
}
