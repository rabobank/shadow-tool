package io.github.rabobank.shadow_tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NoopEncryptionServiceTest {
    @Test
    void valuesAreNotEncrypted() {
        final var encryptionService = NoopEncryptionService.INSTANCE;
        final var plainDifferences = "'place' changed: 'Dintelooord' -> 'Dinteloord'\n" +
                                     "'madrigals' collection changes :\n" +
                                     "   1. 'Bruno' changed to 'Mirabel'\n" +
                                     "   0. 'Bruno' added";
        final var encryptedDifferences = encryptionService.encrypt(plainDifferences);

        assertEquals(plainDifferences, encryptedDifferences);
    }
}
