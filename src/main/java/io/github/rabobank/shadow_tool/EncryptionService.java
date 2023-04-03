package io.github.rabobank.shadow_tool;

import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.encoders.Hex;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;


class EncryptionService {
    public static final String ALGORITHM = "AES";
    public static final String ALGORITHM_MODE_PADDING = ALGORITHM + "/CBC/PKCS5Padding";
    public static final int KEY_SIZE = 64;
    public static final int IV_SIZE = 32;
    private final SecretKey key;
    private final IvParameterSpec iv;

    EncryptionService(final String keyInHex, final String initializationVectorInHex) {
        if (!(hasSize(keyInHex, KEY_SIZE) && hasSize(initializationVectorInHex, IV_SIZE))) {
            final var errorMessage = String.format("Invalid key and IV spec. Expected key to be %d characters (%d bytes), but got %s characters. Expected IV to be %d characters (%d bytes), but got %s characters.",
                    KEY_SIZE, KEY_SIZE / 2, keyInHex != null ? keyInHex.length() : "'null'",
                    IV_SIZE, IV_SIZE / 2, initializationVectorInHex != null ? initializationVectorInHex.length() : "'null'");
            throw new IllegalArgumentException(errorMessage);
        }

        final var keyBytes = Hex.decodeStrict(keyInHex);
        this.key = new SecretKeySpec(keyBytes, ALGORITHM);

        final var ivBytes = Hex.decodeStrict(initializationVectorInHex);
        this.iv = new IvParameterSpec(ivBytes);
    }

    private static boolean hasSize(final String value, final int expectedSize) {
        return value != null && value.length() == expectedSize;
    }

    String encrypt(final String value) {
        try {
            final var cipher = Cipher.getInstance(ALGORITHM_MODE_PADDING);
            cipher.init(Cipher.ENCRYPT_MODE, key, iv);
            final var cipherText = cipher.doFinal(value.getBytes());
            return Base64.toBase64String(cipherText);
        } catch (final Exception e) {
            throw new SecurityException(e);
        }
    }
}
