package io.github.rabobank.shadow_tool;

import org.bouncycastle.util.encoders.Base64;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EncryptionServiceTest {
    private static final PrivateKey PRIVATE_KEY;
    private static final PublicKey PUBLIC_KEY;

    static {
        try {
            KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("RSA");
            keyPairGen.initialize(2048);
            KeyPair pair = keyPairGen.generateKeyPair();
            PRIVATE_KEY = pair.getPrivate();
            PUBLIC_KEY = pair.getPublic();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void encryptAndDecrypt() throws Exception {
        final var encryptCipher = Cipher.getInstance("RSA/ECB/OAEPWITHSHA-256ANDMGF1PADDING");
        encryptCipher.init(Cipher.ENCRYPT_MODE, PUBLIC_KEY);
        final var encryptionService = new DefaultEncryptionService(encryptCipher);
        final var plainDifferences = "'place' changed: 'Dintelooord' -> 'Dinteloord'\n" +
                                     "'madrigals' collection changes :\n" +
                                     "   1. 'Bruno' changed to 'Mirabel'\n" +
                                     "   0. 'Bruno' added";
        final var encryptedDifferences = encryptionService.encrypt(plainDifferences);
        //Decrypt and verify
        final var decryptCipher = Cipher.getInstance("RSA/ECB/OAEPWITHSHA-256ANDMGF1PADDING");
        decryptCipher.init(Cipher.DECRYPT_MODE, PRIVATE_KEY);
        final var cipherText = decryptCipher.doFinal(Base64.decode(encryptedDifferences));
        final var expectedUnencryptedResult = new String(cipherText, StandardCharsets.UTF_8);

        assertEquals(expectedUnencryptedResult, plainDifferences);
    }

    @Test
    void encryptAndForgotToInitCipher() throws Exception {
        final var encryptCipher = Cipher.getInstance("RSA/ECB/OAEPWITHSHA-256ANDMGF1PADDING");
        final var encryptionService = new DefaultEncryptionService(encryptCipher);
        final var plainDifferences = "'place' changed: 'Dintelooord' -> 'Dinteloord'\n" +
                                     "'madrigals' collection changes :\n" +
                                     "   1. 'Bruno' changed to 'Mirabel'\n" +
                                     "   0. 'Bruno' added";
        final var exception = assertThrows(SecurityException.class, () -> encryptionService.encrypt(plainDifferences));
        assertEquals("java.lang.IllegalStateException: Cipher not initialized", exception.getMessage());
    }
}
