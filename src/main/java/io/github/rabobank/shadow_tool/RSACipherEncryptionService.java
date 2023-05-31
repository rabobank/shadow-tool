package io.github.rabobank.shadow_tool;

import org.bouncycastle.util.encoders.Base64;

import javax.crypto.Cipher;
import java.security.PublicKey;


class RSACipherEncryptionService implements IEncryptionService {
    public static final String ALGORITHM = "RSA";
    public static final String ALGORITHM_MODE_PADDING = ALGORITHM + "/ECB/OAEPWITHSHA-256ANDMGF1PADDING";
    private final PublicKey publicKey;

    RSACipherEncryptionService(final PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    public String encrypt(final String value) {
        try {
            final var cipher = Cipher.getInstance(ALGORITHM_MODE_PADDING);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            return Base64.toBase64String(cipher.doFinal(value.getBytes()));
        } catch (final Exception e) {
            throw new SecurityException(e);
        }
    }
}
