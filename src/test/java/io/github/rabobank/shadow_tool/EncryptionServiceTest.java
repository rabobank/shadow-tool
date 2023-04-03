package io.github.rabobank.shadow_tool;

import org.bouncycastle.util.encoders.DecoderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EncryptionServiceTest {

    public static final String CORRECT_KEY = "2d4a75512e73b8761400b49aff747af368a18de82d3865fe597efaf6d11053f9";
    public static final String CORRECT_IV = "ebc3a59998fe444066b5fd819578d564";

    @Test
    void encryptAndDecrypt() {
        final var encryptionService = new EncryptionService(CORRECT_KEY, CORRECT_IV);
        final var plainDifferences = "'place' changed: 'Dintelooord' -> 'Dinteloord'\n" +
            "'madrigals' collection changes :\n" +
            "   1. 'Bruno' changed to 'Mirabel'\n" +
            "   0. 'Bruno' added";

        final var encryptedDifferences = encryptionService.encrypt(plainDifferences);

        assertEquals("JJgCpPVpuiETp+OTtVh/QfMH71M/e5w3aQ4V0hNEDUx3yVxikTRuLvKl+EQNzQZIpwyvuNnmPputpDash0RQf8SS4n9lyzU3If6UbEa0Rlx1IgG7OO5NHp+Mjg6MaQq2cXsR579oM7lkfiEwRIFqRfsReaNqfsMxwwNhIUp/nCVQd6HextJMtqPpZcht+OGL",
            encryptedDifferences);
    }

    @ParameterizedTest
    @MethodSource
    void wrongParametersFailsConstructor(final Class<IllegalArgumentException> expectedException,
                                         final String key,
                                         final String iv) {
        assertThrows(expectedException, () -> new EncryptionService(key, iv));
    }

    static Stream<Arguments> wrongParametersFailsConstructor() {
        return Stream.of(
            Arguments.of(IllegalArgumentException.class, "123", CORRECT_IV),
            Arguments.of(IllegalArgumentException.class, CORRECT_KEY, "234"),
            Arguments.of(IllegalArgumentException.class, null, CORRECT_IV),
            Arguments.of(IllegalArgumentException.class, CORRECT_KEY, null),
            Arguments.of(DecoderException.class, corruptHex(CORRECT_KEY), CORRECT_IV),
            Arguments.of(DecoderException.class, CORRECT_KEY, corruptHex(CORRECT_IV)),
            Arguments.of(DecoderException.class, corruptHex(CORRECT_KEY), corruptHex(CORRECT_IV))
        );
    }

    private static String corruptHex(final String hex) {
        return hex.substring(0, hex.length() - 1) + "G";
    }
}
