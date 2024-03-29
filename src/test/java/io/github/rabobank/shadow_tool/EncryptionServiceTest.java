package io.github.rabobank.shadow_tool;

import org.bouncycastle.util.encoders.Base64;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;

import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.crypto.Cipher.DECRYPT_MODE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EncryptionServiceTest {
    private static final PrivateKey PRIVATE_KEY;
    private static final PublicKey PUBLIC_KEY;

    static {
        try {
            final var keyPairGen = KeyPairGenerator.getInstance("RSA");
            keyPairGen.initialize(2048);
            final var pair = keyPairGen.generateKeyPair();
            PRIVATE_KEY = pair.getPrivate();
            PUBLIC_KEY = pair.getPublic();
        } catch (final GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void encryptAndDecrypt() throws Exception {
        final var encryptionService = new PublicKeyEncryptionService(PUBLIC_KEY);
        final var plainDifferences = "'place' changed: 'Dintelooord' -> 'Dinteloord'\n" +
                                     "'madrigals' collection changes :\n" +
                                     "   1. 'Bruno' changed to 'Mirabel'\n" +
                                     "   0. 'Bruno' added";
        final var encryptedDifferences = encryptionService.encrypt(plainDifferences);
        //Decrypt and verify
        final var decryptCipher = Cipher.getInstance("RSA/ECB/OAEPWITHSHA-256ANDMGF1PADDING");
        decryptCipher.init(DECRYPT_MODE, PRIVATE_KEY);
        final var cipherText = decryptCipher.doFinal(Base64.decode(encryptedDifferences));
        final var result = new String(cipherText, UTF_8);

        assertEquals(plainDifferences, result);
    }

    @Test
    void encryptAndForgotToInitCipher() throws Exception {
        final var encryptCipher = Cipher.getInstance("RSA/ECB/OAEPWITHSHA-256ANDMGF1PADDING");
        final var encryptionService = new DefaultEncryptionService(encryptCipher);
        final var plainDifferences = "'place' changed: 'Dintelooord' -> 'Dinteloord'\n" +
                                     "'madrigals' collection changes :\n" +
                                     "   1. 'Bruno' changed to 'Mirabel'\n" +
                                     "   0. 'Bruno' added";
        final var exception = assertThrows(IllegalStateException.class, () -> encryptionService.encrypt(plainDifferences));
        assertEquals("Cipher not initialized", exception.getMessage());
    }
}
