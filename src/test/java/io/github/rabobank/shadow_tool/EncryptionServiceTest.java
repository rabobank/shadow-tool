package io.github.rabobank.shadow_tool;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.crypto.Cipher.DECRYPT_MODE;
import static java.util.concurrent.TimeUnit.SECONDS;
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
        final var plainDifferences = """
                'place' changed: 'Dintelooord' -> 'Dinteloord'
                'madrigals' collection changes :
                   1. 'Bruno' changed to 'Mirabel'
                   0. 'Bruno' added""";
        final var encryptedDifferences = encryptionService.encrypt(plainDifferences);
        //Decrypt and verify
        final var decryptCipher = Cipher.getInstance("RSA/ECB/OAEPWITHSHA-256ANDMGF1PADDING");
        decryptCipher.init(DECRYPT_MODE, PRIVATE_KEY);
        final var cipherText = decryptCipher.doFinal(Base64.getDecoder().decode(encryptedDifferences));
        final var result = new String(cipherText, UTF_8);

        assertEquals(plainDifferences, result);
    }

    @Test
    void encryptAndForgotToInitCipher() throws Exception {
        final var encryptCipher = Cipher.getInstance("RSA/ECB/OAEPWITHSHA-256ANDMGF1PADDING");
        final var encryptionService = new DefaultEncryptionService(encryptCipher);
        final var plainDifferences = """
                'place' changed: 'Dintelooord' -> 'Dinteloord'
                'madrigals' collection changes :
                   1. 'Bruno' changed to 'Mirabel'
                   0. 'Bruno' added""";
        final var exception = assertThrows(IllegalStateException.class, () -> encryptionService.encrypt(plainDifferences));
        assertEquals("Cipher not initialized", exception.getMessage());
    }

    @Test
    void shouldEncryptConcurrentMessagesWithoutCorruption() throws Exception {
        final var encryptionService = new PublicKeyEncryptionService(PUBLIC_KEY);
        final var start = new CountDownLatch(1);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var messages = new ArrayList<Future<String>>();
            try {
                for (int i = 0; i < 64; i++) {
                    final var message = "difference-" + i;
                    messages.add(executor.submit(() -> {
                        if (!start.await(5, SECONDS)) {
                            throw new IllegalStateException("Timed out waiting to start encryption");
                        }
                        return encryptionService.encrypt(message);
                    }));
                }
            } finally {
                start.countDown();
            }

            final var decryptCipher = Cipher.getInstance("RSA/ECB/OAEPWITHSHA-256ANDMGF1PADDING");
            decryptCipher.init(DECRYPT_MODE, PRIVATE_KEY);
            for (int i = 0; i < messages.size(); i++) {
                final var decrypted = decryptCipher.doFinal(Base64.getDecoder().decode(messages.get(i).get(5, SECONDS)));
                assertEquals("difference-" + i, new String(decrypted, UTF_8));
            }
        }
    }
}
