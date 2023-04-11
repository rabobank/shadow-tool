# Shadow Tool

This library allows you to safely test your migration from one back-end to another in production!  
## Installation
### Maven
```xml
<dependency>
   <groupId>io.github.rabobank</groupId>
   <artifactId>shadow-tool</artifactId>
   <version>${shadow-tool.version}</version>
</dependency>
```
### Gradle
```kotlin
implementation("io.github.rabobank:shadow-tool:$version")
```

The Shadow Tool can be easily integrated in your Java/Kotlin project and allows you to compare the current back-end service your application is using against the new back-end you plan on using.
Since it actually runs on production (in the background), it gives you trust in:
1. the connection towards your new back-end,
2. the data quality coming from the new back-end,
3. whether your code correctly maps the data of the new back-end to your existing domain.

The tool is designed to be a plug-and-play solution which runs without functional impact in your current production app.
When activated, when your app fetches data from your current back-end it will additionally call the new back-end and compare the data in parallel.
This will be sampled based on a configured percentage as to not overload your application.
The findings are reported using log statements.

## Getting started
1. Build the library locally and add it as a dependency to your project (**We are still working on deploying this to Maven Central**)
2. In order to see the differences, the library expects the `slf4j-api` library to be provided by the using application.
3. Optional: To be able to inspect the values of the differences, it is required to set up encryption. Not setting up encryption allows you to see the different keys only, so no values.
   To begin, an RSA (at least) 2048 bit public and private key are required. Generate as follows (for both the public and private key):
   ```bash
   openssl genrsa -out pair.pem 2048 && openssl rsa -in pair.pem -pubout -out public.key && openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt -in pair.pem -out private.key && rm -rf pair.pem
   ```
   Keep the private key secret. When the data is sensitive, nobody other than you should be able to inspect these values.
   To create a `java.security.PublicKey`, you can use below code (add dependency `org.bouncycastle:bcprov-jdk15on`):
   ```java
   import java.io.File;
   import java.io.StringReader;
   import java.nio.file.Files;
   import java.security.KeyFactory;
   import java.security.PublicKey;
   import java.security.spec.X509EncodedKeySpec;
   import java.util.Objects;
   import org.bouncycastle.util.io.pem.PemReader;
   
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
   ```
## How to use?

```java
ShadowFlow<AccountInfo> shadowFlow = new ShadowFlowBuilder<AccountInfo>(10)
        .withInstanceName("account-service") # Optional. Default value is 'default'
        .withEncryption(<java.security.PublicKey>) # Optional. See configuration above for generating these secrets.
        .build();

AccountInfo result = shadowFlow.compare(
        () -> yourCurrentBackend.getAccountInfo(),
        () -> yourNewBackend.getAccountInfo()
);
```

The result is always from the first supplier. So in this case, `result` always yields the response of the `yourCurrentBackend` service.

The 10 means that for 10% of all requests, the `yourNewBackend` is invoked as well and its response is compared against the `yourCurrentBackend` response.
This happens asynchronously, so it will not have impact on the main flow performance-wise.
Be aware that the more often the Shadow Tool runs, the more resources your application uses and back-ends are called.
Take care to not set this number too high for high-traffic applications.

To be able to compare apples with apples, both services are required to return the same domain classes.
In the example above, we called it `AccountInfo`.
Also, since the secondary call is already mapped to the correct domain, it is super easy to finish the migration: just replace the first call with the secondary call and remove the Shadow Tool code.

You are able to distinguish the results of multiple shadow flows running in your application by setting an instance name. 
This will be part of the log messages.

#### Reactive
The Shadow Tool also provides a reactive API based on Project Reactor.

```java
class AccountInfoService {
    // fields and constructor
    
    public Mono<AccountInfo> getAccountInfo() {
        return shadowFlow.compare(
                getAccountInfoFromCurrent(),
                getAccountInfoFromNew()
        );
    }
    
    private Mono<AccountInfo> getAccountInfoFromCurrent() {
        return yourCurrentBackend.getAccountInfoMono()
                .map(...);
    }

    private Mono<AccountInfo> getAccountInfoFromNew() {
        return yourNewBackend.getAccountInfoMono()
                .map(...);
    }
} 
```

## Logs
The Shadow Tool logs whenever it finds differences between the two flows.
It will always log the field names of the objects containing the differences, and it can also log the values when encryption is set up.
Something like the following can be expected:

```
# Without Encryption enabled
The following differences were found: firstName, lastName

# With Encryption enabled
The following differences were found: firstName, lastName. Encrypted values: 6U8H2WSpEoXY1cFDS2Ze/63ohRVIS4t3A4I5E3RJeemrqXTWEUN6BlTawMVgyjQri9t8l6t9jotJmIEQOoc++C9W38Z8mYEAzU2UzvGm50AMcFqEXheSBEw7c3LZFRoE
```

## Inspecting the values of differences
Values are encrypted using the public key which is set up during the configuration.
The algorithm used is RSA with Electronic Codeblock mode (CBC) and `OAEPWITHSHA-256ANDMGF1PADDING` padding.
You can create a runnable jar with the following code to decrypt the values. Continuing the example above (explaining how to enable encrypting data):

### Example decrypting values of differences
This can easily be a runnable jar that takes a file or a single line as an argument, when you want to inspect values.
```java
import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.io.pem.PemReader;
import javax.crypto.Cipher;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Objects;

void decrypt() throws Exception {
    final var encryptedDifferences = "fr1vTtM0wM91neX0Fl+Owq6fuTgkRD0CRPGBwDKftV1rBCPmzpLtQDMSV6sAw89M+YKOqLTQGBYckj6ZUVG/TTQqcoNx8BThAA2GQAvnAWBDSOEykpWf39Dp7L1rqZUbNqmf/DCxY45MdSutjde+DVwtpdRjJHcF4BELfQS+dG5TscXfEyQ75HIdBqWhpdaTh2My+7BOzo88zZKVqQwdDBymW78SkJ3Ez3X9kNjxlTI7w4LR5y3Cis5rIEfBnoMz1YMilx+5s0Ku9flzciFxr81czIImTmpBmvAscmtOB8ABfdDcPVvAEZlDzHktIHpH2pQ0QLnvVum43QLCfyezDg==";
    //Decrypt and verify
    var privateKey = privateKey();
    final var cipher = Cipher.getInstance("RSA/ECB/OAEPWITHSHA-256ANDMGF1PADDING");
    cipher.init(Cipher.DECRYPT_MODE, privateKey);
    final var cipherText = cipher.doFinal(Base64.decode(encryptedDifferences));
    final var expectedUnencryptedResult = new String(cipherText, StandardCharsets.UTF_8);
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
```
