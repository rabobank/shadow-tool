package io.github.rabobank.shadow_tool;

import org.bouncycastle.util.encoders.Base64;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NoopEncryptionServiceTest {
    @Test
    void encryptAndDecrypt() {
        final var encryptionService = NoopEncryptionService.INSTANCE;
        final var plainDifferences = "'place' changed: 'Dintelooord' -> 'Dinteloord'\n" +
                                     "'madrigals' collection changes :\n" +
                                     "   1. 'Bruno' changed to 'Mirabel'\n" +
                                     "   0. 'Bruno' added";
        final var encryptedDifferences = encryptionService.encrypt(plainDifferences);
        //Decrypt and verify
        final var decoded = Base64.decode(encryptedDifferences);
        final var result = new String(decoded, UTF_8);

        assertEquals(plainDifferences, result);
    }
}
