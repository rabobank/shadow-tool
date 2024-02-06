# Shadow Tool

This library allows you to safely test your migration from one back-end to another in production!

The Shadow Tool can be easily integrated into your Java/Kotlin project and allows you to compare the current back-end
service your application is using against the new back-end you plan on using.
Since it actually runs in the production environment (in the background), it helps to ensure that:

1. the connection towards your new back-end is working and gives you a response,
2. the data coming from the new back-end is equal to the data coming from the current back-end,
3. whether your code correctly maps the data of the new back-end to your existing domain.

The tool is designed to be a plug-and-play solution that runs without impacting the functionality of your current production app.
When activated, as your app fetches data from your current back-end, it will also call the new back-end and compare 
the data in parallel.
This will be sampled based on a configured percentage to prevent overloading your application.
The findings are reported using log statements.

## Installation

### Maven

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.rabobank/shadow-tool/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.github.rabobank/shadow-tool)

```xml
<dependency>
    <groupId>io.github.rabobank</groupId>
    <artifactId>shadow-tool</artifactId>
    <version>${shadow-tool.version}</version> <!-- Make sure to check the latest version in Maven Central (above) -->
</dependency>
```

### Gradle

```kotlin
implementation("io.github.rabobank:shadow-tool:$version") // Make sure to check the latest version in Maven Central (above)
```

## Getting started

1. **Important:** In order to see the differences in your logs, you have to add `slf4j-api` to your dependencies. By
   default, only fieldnames (keys) are logged when the values differ.
   To see the what exactly is different, encryption is required. Proceed to step 2 for setting up encryption.
2. You have 3 encryption options:
    1. **Noop encryption**: By setting up a `NoopEncryptionService`, the differences are logged as `Base64` encoded
       text. This is not recommended for sensitive data.  
       Example:
       ```java
       import io.github.rabobank.shadow_tool.ShadowFlow.ShadowFlowBuilder;

       import java.util.List;
       import java.util.function.Supplier;
       
       public class BackendService {
       
           public DummyObject callBackend() {
               // Create a ShadowFlow instance with NoopEncryptionService
               // The 10 means that for 10% of all requests, the `newBackend` is invoked as well and its response is compared against the `currentBackend` response.
               ShadowFlowBuilder<Dummy> builder = new ShadowFlowBuilder<>(10);
               ShadowFlow<Dummy> shadowFlow = builder.withEncryptionService(NoopEncryptionService.INSTANCE).build();
       
               // Define your current backend and new backend suppliers
               Supplier<Dummy> currentBackend = () -> {
                   // Your current backend logic here
                   return new Dummy("Bob", "Utrecht", List.of("Mirabel", "Bruno"));
               };
       
               Supplier<Dummy> newBackend = () -> {
                   // Your new backend logic here
                   return new Dummy("Bob", "Amsterdam", List.of("Bruno", "Mirabel", "Mirabel"));
               };
       
               // The result is always from the first supplier. So in this case, the return value always yields the response of the `currentBackend` service.
               return shadowFlow.compare(currentBackend, newBackend);
           }
       }
       ```
    2. **Cipher encryption**: The differences are logged as encrypted values. This is recommended for sensitive
       data.
       Example:
       ```java
       import io.github.rabobank.shadow_tool.ShadowFlow.ShadowFlowBuilder;

       import javax.crypto.Cipher;
       import java.security.GeneralSecurityException;
       import java.util.List;
       import java.util.function.Supplier;
   
       public class BackendService {
   
           public DummyObject callBackend() {
               // Create a Cipher instance
               Cipher cipher = null;
               try {
                   // The AES key (16, 24, or 32 bytes)
                   final var keyBytes = Hex.decodeStrict("3d7e0c4f8fbbd8d8a79e76cabc8f4e24");
                   final var secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
           
                   // Initialization Vector (IV) for GCM
                   final var iv = Hex.decodeStrict("3d7e0c4f8fbb"); // 96 bits IV
                   if (iv.length != GCM_SIV_IV_SIZE) {
                       throw new IllegalArgumentException("Initialization Vector should be 12 bytes / 96 bits");
                   }
           
                   // Create AEADParameterSpec
                   final var gcmParameterSpec = new GCMParameterSpec(MAC_SIZE_IN_BITS, iv);
                   // Create Cipher instance with the specified algorithm and provider
                   cipher = Cipher.getInstance(ALGORITHM_MODE);
           
                   // Initialize the Cipher for encryption or decryption
                   cipher.init(ENCRYPT_MODE, secretKey, gcmParameterSpec);
               } catch (GeneralSecurityException e) {
                   // Handle exception
               }
   
               // Create a ShadowFlow instance with DefaultEncryptionService
               // The 10 means that for 10% of all requests, the `newBackend` is invoked as well and its response is compared against the `currentBackend` response.
               ShadowFlow<Dummy> shadowFlow = new ShadowFlowBuilder<Dummy>(10).withCipher(cipher).build();
   
               // Define your current backend and new backend suppliers
               Supplier<Dummy> currentBackend = () -> {
                   // Your current backend logic here
                   return new Dummy("Bob", "Utrecht", List.of("Mirabel", "Bruno"));
               };
   
               Supplier<Dummy> newBackend = () -> {
                   // Your new backend logic here
                   return new Dummy("Bob", "Amsterdam", List.of("Bruno", "Mirabel", "Mirabel"));
               };
   
               // The result is always from the first supplier. So in this case, the return value always yields the response of the `currentBackend` service.
               return shadowFlow.compare(currentBackend, newBackend);
           }
       }
       ```
    3. **PublicKey encryption**: The differences are logged as encrypted values. This is recommended for sensitive data.
        Example:
        ```java
       import io.github.rabobank.shadow_tool.ShadowFlow.ShadowFlowBuilder;

       import java.security.KeyFactory;
       import java.security.PublicKey;
       import java.security.spec.X509EncodedKeySpec;
       import java.util.Base64;
       import java.util.List;
       import java.util.function.Supplier;
       
       public class BackendService {
       
           public DummyObject callBackend() {
               final PublicKey publicKey;
               try {
                   publicKey = KeyFactory.getInstance("RSA")
                           .generatePublic(new X509EncodedKeySpec(Base64.decode("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArmkP2CgDn3OsuIj1GxM3")));
               } catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
                   throw new RuntimeException(e);
               }
       
               // Create a ShadowFlow instance with PublicKeyEncryptionService
               // The 10 means that for 10% of all requests, the `newBackend` is invoked as well and its response is compared against the `currentBackend` response.
               ShadowFlowBuilder<Dummy> builder = new ShadowFlowBuilder<>(10);
               builder.withEncryption(publicKey);
       
               ShadowFlow<Dummy> shadowFlow = builder.build();
       
               // Define your current backend and new backend suppliers
               Supplier<Dummy> currentBackend = () -> {
                   // Your current backend logic here
                   return new Dummy("Bob", "Utrecht", List.of("Mirabel", "Bruno"));
               };
       
               Supplier<Dummy> newBackend = () -> {
                   // Your new backend logic here
                   return new Dummy("Bob", "Amsterdam", List.of("Bruno", "Mirabel", "Mirabel"));
               };
               // The result is always from the first supplier. So in this case, the return value always yields the response of the `currentBackend` service.
               return shadowFlow.compare(currentBackend, newBackend);
                  }
           }       
       ```
3. To create a public and private (to decrypt) key, run the following command:
   ```bash
   openssl genrsa -out pair.pem 2048 && openssl rsa -in pair.pem -pubout -out public.key && openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt -in pair.pem -out private.key && rm -rf pair.pem
   ```

The shadow tool invokes both services asynchronously, so it will not have impact on the main flow performance-wise.
Be aware that the more often the Shadow Tool runs, the more resources your application uses and back-ends are called.
Be careful not to set this number too high for high-traffic applications.

For a fair comparison, both services are required to return the same domain classes.
In the example above, we called it `Dummy`.
Also, since the secondary call is already mapped to the correct domain, completing the migration is straightforward: 
simply replace the first call with the secondary call and remove the Shadow Tool code.

You can distinguish the results of multiple shadow flows running in your application by setting an instance name.
This will be part of the log messages.

#### Reactive

The Shadow Tool also provides a reactive API based on Project Reactor.

```java
class MyService {
    // fields and constructor

    public Mono<Dummy> getDummy() {
        return shadowFlow.compare(
                getDummyFromCurrent(),
                getDummyFromNew()
        );
    }

    private Mono<Dummy> getDummyFromCurrent() {
        return yourCurrentBackend.getDummMono()
                .map(...);
    }

    private Mono<AccountInfo> getDummyFromNew() {
        return yourNewBackend.getDummyMono()
                .map(...);
    }
} 
```

## Logs

The Shadow Tool logs any differences it finds between the two flows.
It always logs the field names of the objects containing the differences, 
and it can also log the values when encryption is set up.
You can expect output similar to the following:

```
# Without Encryption enabled
The following differences were found: firstName, lastName

# With Encryption enabled
The following differences were found: firstName, lastName. Encrypted values: 6U8H2WSpEoXY1cFDS2Ze/63ohRVIS4t3A4I5E3RJeemrqXTWEUN6BlTawMVgyjQri9t8l6t9jotJmIEQOoc++C9W38Z8mYEAzU2UzvGm50AMcFqEXheSBEw7c3LZFRoE
```

## Inspecting the values of differences

Values are encrypted using the public key that is set up during the configuration.
The default algorithm for Public Key encryption is RSA with Electronic Codeblock mode (CBC) and `OAEPWITHSHA-256ANDMGF1PADDING` padding.

### Example of decrypting values of differences

You can find an example in one of the tests: [EncryptionServiceTest](src/test/java/io/github/rabobank/shadow_tool/EncryptionServiceTest.java).