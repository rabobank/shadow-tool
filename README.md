# Shadow Tool

This library allows you to safely test your migration from one back-end to another in production!

> This artifact contains the core Shadow Tool implementation only. If you are using Spring Boot, configure Shadow Tool via the **[Shadow Tool Spring Boot Starter](https://github.com/rabobank/shadow-tool-spring-boot-starter/)**: `io.github.rabobank.shadow_tool:shadow-tool-spring-boot-starter`.

The core library requires Java 25 or later.

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

Use this dependency when you want to wire and use Shadow Tool directly in application code.
For Spring Boot applications, configuration should be done through the [Shadow Tool Spring Boot Starter](https://github.com/rabobank/shadow-tool-spring-boot-starter/) dependency: `io.github.rabobank.shadow_tool:shadow-tool-spring-boot-starter`.

### Maven

[![Maven Central](https://img.shields.io/maven-central/v/io.github.rabobank/shadow-tool)](https://central.sonatype.com/artifact/io.github.rabobank/shadow-tool)

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

If you use Spring Boot, set up configuration via the [Shadow Tool Spring Boot Starter](https://github.com/rabobank/shadow-tool-spring-boot-starter/).
The following example shows manual use of the core library. For sampled requests, the new backend is called in the
background; the method always returns the current backend's result.

```java
import io.github.rabobank.shadow_tool.ShadowFlow;
import java.util.function.Supplier;

record Account(String name, String city) {}

class AccountService {
    private final ShadowFlow<Account> shadowFlow = new ShadowFlow.ShadowFlowBuilder<Account>(10).build();

    Account getAccount() {
        Supplier<Account> current = () -> currentBackend();
        Supplier<Account> replacement = () -> replacementBackend();
        return shadowFlow.compare(current, replacement);
    }

    private Account currentBackend() { return new Account("Bob", "Utrecht"); }
    private Account replacementBackend() { return new Account("Bob", "Amsterdam"); }
}
```

In this example, `10` means the replacement backend is called for approximately 10% of requests. Choose a percentage
that your replacement backend can handle; the sampled calls add load to it and consume application resources.
Both backends should map to the same domain type so their results can be compared.
The current backend runs normally: its errors still propagate to the caller. Shadow failures are logged without
replacing the current result. Use immutable response objects, as comparison happens after the current result is returned.

By default, Shadow Tool logs changed property names but not their values. Logging requires an SLF4J implementation
configured by your application. To inspect changed values, configure an encryption service:

```java
var shadowFlow = new ShadowFlow.ShadowFlowBuilder<Account>(10)
        .withEncryption(publicKey)
        .build();
```

`withEncryption` uses RSA with OAEP SHA-256; use an RSA public key of at least 2048 bits. Keep the matching
private key outside the application so only authorized operators can decrypt log values. RSA has a strict limit on
the amount of data it can encrypt; if comparisons can produce large diffs, supply an `EncryptionService` that uses
authenticated hybrid encryption (a fresh symmetric key and nonce per message, with that key protected by RSA).
For a 2048-bit RSA key with OAEP SHA-256, the entire formatted diff must fit within 190 UTF-8 bytes.

For non-sensitive test data only, `NoopEncryptionService.INSTANCE` can be supplied with
`withEncryptionService`; it Base64-encodes values but does not encrypt them. To implement a custom encryption
strategy, provide a thread-safe `EncryptionService` using `withEncryptionService`. Do not share a mutable `Cipher`
instance across concurrent calls.
`withCipher` is also available for an initialized cipher whose algorithm supports repeated encryption calls;
algorithms requiring a fresh nonce or IV for each message should be implemented as a custom `EncryptionService`.

You can distinguish multiple Shadow Flow instances in logs with `withInstanceName("...")`.

### Execution and virtual threads

By default, shadow work uses Reactor's shared bounded-elastic scheduler. To choose another execution strategy,
provide an executor with `withExecutor`. For example, Java's virtual-thread executor works with both APIs:

```java
try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
    var shadowFlow = new ShadowFlow.ShadowFlowBuilder<Account>(10)
            .withExecutor(executor)
            .build();
    var result = shadowFlow.compare(
            () -> new Account("Bob", "Utrecht"),
            () -> new Account("Bob", "Amsterdam"));
}
```

In an application, keep the executor and `ShadowFlow` alive for the service's lifetime, not just one request.
You own the supplied executor and must close it at shutdown; closing it waits for submitted tasks.
Virtual threads do not limit concurrency, so continue to sample conservatively to protect downstream services.
No Java preview features or `--enable-preview` flags are required.

### Collections

Use `compareCollections` rather than `compare` for top-level lists, sets, or other collections:

```java
var accounts = shadowFlow.compareCollections(
        () -> java.util.List.of(new Account("Bob", "Utrecht")),
        () -> java.util.List.of(new Account("Bob", "Amsterdam")),
        Account.class);
```

The same method supports `Mono` collections. The returned collection is always the current flow's collection.

### Reactive

The Shadow Tool also provides a reactive API based on Project Reactor.
The returned `Mono` is the current flow; a sampled replacement flow is subscribed independently for comparison.
Sampling is performed for each subscription. If the current flow is empty or fails, the replacement is not subscribed.
Reactor context is forwarded to the replacement. Configure timeouts on backend publishers, as shadow subscriptions
are independent and are not cancelled when the current request completes.

```java
import reactor.core.publisher.Mono;

class MyService {
    private final ShadowFlow<Account> shadowFlow = new ShadowFlow.ShadowFlowBuilder<Account>(10).build();

    public Mono<Account> getAccount() {
        return shadowFlow.compare(
                getAccountFromCurrent(),
                getAccountFromNew()
        );
    }

    private Mono<Account> getAccountFromCurrent() {
        return Mono.fromSupplier(() -> new Account("Bob", "Utrecht"));
    }

    private Mono<Account> getAccountFromNew() {
        return Mono.fromSupplier(() -> new Account("Bob", "Amsterdam"));
    }
} 
```

## Logs

The Shadow Tool logs any differences it finds between the two flows.
At INFO level it logs changed property names, or change types such as `NewObject` for object-level changes.
It can also log the values when encryption is set up.
You can expect output similar to the following:

```
# Without Encryption enabled
The following differences were found: firstName, lastName

# With Encryption enabled
The following differences were found: firstName, lastName. Encrypted values: 6U8H2WSpEoXY1cFDS2Ze/63ohRVIS4t3A4I5E3RJeemrqXTWEUN6BlTawMVgyjQri9t8l6t9jotJmIEQOoc++C9W38Z8mYEAzU2UzvGm50AMcFqEXheSBEw7c3LZFRoE
```

## Inspecting the values of differences

The configured encryption service encrypts the formatted diff as a whole. With `withEncryption`, decrypt the Base64
value using the corresponding RSA private key and OAEP SHA-256. For custom encryption, use the matching decryption
procedure for that service. The [EncryptionServiceTest](src/test/java/io/github/rabobank/shadow_tool/EncryptionServiceTest.java)
demonstrates decrypting a value produced by the built-in public-key service.
