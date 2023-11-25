package io.github.rabobank.shadow_tool;

import javax.crypto.Cipher;
import java.security.GeneralSecurityException;
import java.security.PublicKey;

import static javax.crypto.Cipher.ENCRYPT_MODE;

/**
 * A version of the Encryption Service that uses a {@link PublicKey} to encrypt the values.
 */
public class PublicKeyEncryptionService extends DefaultEncryptionService {
    private static final String DEFAULT_ALGORITHM = "RSA";
    private static final String DEFAULT_ALGORITHM_MODE_PADDING =
            DEFAULT_ALGORITHM + "/ECB/OAEPWITHSHA-256ANDMGF1PADDING";

    public PublicKeyEncryptionService(final PublicKey publicKey) {
        super(createCipher(publicKey));
    }

    private static Cipher createCipher(final PublicKey publicKey) {
        try {
            final var cipher = Cipher.getInstance(DEFAULT_ALGORITHM_MODE_PADDING);
            cipher.init(ENCRYPT_MODE, publicKey);
            return cipher;
        } catch (final GeneralSecurityException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
