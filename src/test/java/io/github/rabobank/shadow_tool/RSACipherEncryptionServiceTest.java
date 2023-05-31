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

class RSACipherEncryptionServiceTest {

    @Test
    void encryptAndDecrypt() throws Exception {
        final var encryptionService = new RSACipherEncryptionService(publicKey());
        final var plainDifferences = "'place' changed: 'Dintelooord' -> 'Dinteloord'\n" +
                "'madrigals' collection changes :\n" +
                "   1. 'Bruno' changed to 'Mirabel'\n" +
                "   0. 'Bruno' added";

        final var encryptedDifferences = encryptionService.encrypt(plainDifferences);
        //Decrypt and verify
        var privateKey = privateKey();
        final var cipher = Cipher.getInstance("RSA/ECB/OAEPWITHSHA-256ANDMGF1PADDING");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        final var cipherText = cipher.doFinal(Base64.decode(encryptedDifferences));
        final var expectedUnencryptedResult = new String(cipherText, StandardCharsets.UTF_8);

        assertEquals(expectedUnencryptedResult, plainDifferences);
    }

    private static PrivateKey privateKey() throws Exception {
        final var privateKeyFile = new File(Objects.requireNonNull(RSACipherEncryptionServiceTest.class.getClassLoader().getResource("private.key")).getFile());
        final var reader = new StringReader(Files.readString(privateKeyFile.toPath()));
        final var pemReader = new PemReader(reader);
        final var factory = KeyFactory.getInstance("RSA");
        final var pemObject = pemReader.readPemObject();
        final var keyContentAsBytesFromBC = pemObject.getContent();
        final var privKeySpec = new PKCS8EncodedKeySpec(keyContentAsBytesFromBC);
        return factory.generatePrivate(privKeySpec);
    }

    private static PublicKey publicKey() throws Exception {
        final var publicKeyFile = new File(Objects.requireNonNull(RSACipherEncryptionServiceTest.class.getClassLoader().getResource("public.key")).getFile());
        final var reader = new StringReader(Files.readString(publicKeyFile.toPath()));
        final var pemReader = new PemReader(reader);
        final var factory = KeyFactory.getInstance("RSA");
        final var pemObject = pemReader.readPemObject();
        final var keyContentAsBytesFromBC = pemObject.getContent();
        final var pubKeySpec = new X509EncodedKeySpec(keyContentAsBytesFromBC);
        return factory.generatePublic(pubKeySpec);
    }
}