package io.github.rabobank.shadow_tool;

import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.io.pem.PemReader;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EncryptionServiceTest {

    @Test
    void encryptAndDecrypt() throws Exception {
        final var encryptCipher = Cipher.getInstance("RSA/ECB/OAEPWITHSHA-256ANDMGF1PADDING");
        encryptCipher.init(Cipher.ENCRYPT_MODE, publicKey());
        final var encryptionService = new EncryptionService(encryptCipher);
        final var plainDifferences = "'place' changed: 'Dintelooord' -> 'Dinteloord'\n" +
                "'madrigals' collection changes :\n" +
                "   1. 'Bruno' changed to 'Mirabel'\n" +
                "   0. 'Bruno' added";
        final var encryptedDifferences = encryptionService.encrypt(plainDifferences);

        //Decrypt and verify
        final var decryptCipher = Cipher.getInstance("RSA/ECB/OAEPWITHSHA-256ANDMGF1PADDING");
        decryptCipher.init(Cipher.DECRYPT_MODE, privateKey());
        final var cipherText = decryptCipher.doFinal(Base64.decode(encryptedDifferences));
        final var expectedUnencryptedResult = new String(cipherText, StandardCharsets.UTF_8);

        assertEquals(expectedUnencryptedResult, plainDifferences);
    }

    @Test
    void encryptAndForgotToInitCipher() throws Exception {
        final var encryptCipher = Cipher.getInstance("RSA/ECB/OAEPWITHSHA-256ANDMGF1PADDING");
        final var encryptionService = new EncryptionService(encryptCipher);
        final var plainDifferences = "'place' changed: 'Dintelooord' -> 'Dinteloord'\n" +
                "'madrigals' collection changes :\n" +
                "   1. 'Bruno' changed to 'Mirabel'\n" +
                "   0. 'Bruno' added";
        var exception = assertThrows(SecurityException.class, () -> encryptionService.encrypt(plainDifferences));
        assertEquals(exception.getMessage(), "java.lang.IllegalStateException: Cipher not initialized");
    }

    private static PrivateKey privateKey() throws Exception {
        final var privateKeyFile = new File(Objects.requireNonNull(EncryptionServiceTest.class.getClassLoader().getResource("private.key")).getFile());
        final var reader = new StringReader(Files.readString(privateKeyFile.toPath()));
        final var pemReader = new PemReader(reader);
        final var factory = KeyFactory.getInstance("RSA");
        final var pemObject = pemReader.readPemObject();
        final var keyContentAsBytesFromBC = pemObject.getContent();
        final var privKeySpec = new PKCS8EncodedKeySpec(keyContentAsBytesFromBC);
        return factory.generatePrivate(privKeySpec);
    }

    private static PublicKey publicKey() throws Exception {
        final var publicKeyFile = new File(Objects.requireNonNull(EncryptionServiceTest.class.getClassLoader().getResource("public.key")).getFile());
        final var reader = new StringReader(Files.readString(publicKeyFile.toPath()));
        final var pemReader = new PemReader(reader);
        final var factory = KeyFactory.getInstance("RSA");
        final var pemObject = pemReader.readPemObject();
        final var keyContentAsBytesFromBC = pemObject.getContent();
        final var pubKeySpec = new X509EncodedKeySpec(keyContentAsBytesFromBC);
        return factory.generatePublic(pubKeySpec);
    }
}