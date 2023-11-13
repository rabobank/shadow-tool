package io.github.rabobank.shadow_tool;

/**
 * A version of the Encryption Service that doesn't perform any encryption.
 * This might be useful for simple use-case where encryption of differences is not required,
 * for example with public data.
 */
class NoopEncryptionService implements EncryptionService {
    public static final NoopEncryptionService INSTANCE = new NoopEncryptionService();

    NoopEncryptionService() {
    }

    @Override
    public String encrypt(final String value) {
        return value;
    }
}
