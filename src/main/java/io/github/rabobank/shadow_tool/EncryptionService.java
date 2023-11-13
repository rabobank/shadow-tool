package io.github.rabobank.shadow_tool;

@FunctionalInterface
public interface EncryptionService {
    /**
     * Encrypts the given data for logging.
     * *Note* The result of this is directly logged and should
     * therefore be in a printable format which meets the minimum encryption requirements of
     * the specific data.
     *
     * @param value the data to encrypt
     * @return encrypted data
     */
    String encrypt(final String value);
}
