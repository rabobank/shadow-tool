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
3. Optional: To be able to inspect the values of the differences, it is required to set up encryption.
   A 32-byte key and 16-byte IV are required, in the representation of a hex-string. Generate as follows (for both the secret and IV):
   ```bash
   $ openssl rand -hex 32
   14f543f5dee18a66f3ed0903023fec797bf3a3e105424e4bd19ce7579a48bdcf
   ```
   Depending on your data, treat these values as secrets. When the data is sensitive, nobody other than you should be able to inspect these values.

## How to use?

```java
ShadowFlow<AccountInfo> shadowFlow = new ShadowFlowBuilder<AccountInfo>(10)
        .withInstanceName("account-service") # Optional. Default value is 'default'
        .withEncryption(<key>, <iv>) # Optional. See configuration above for generating these secrets.
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
Values are encrypted using the encryption key and initialization vector (IV) which are set up during the configuration.
The algorithm used is AES, with PKCS5 padding and Cipher block chaining (CBC).
A utility like OpenSSL can be used to decrypt the values. Continuing the example above:

```bash
$ encrypted_text="6U8H2WSpEoXY1cFDS2Ze/63ohRVIS4t3A4I5E3RJeemrqXTWEUN6BlTawMVgyjQri9t8l6t9jotJmIEQOoc++C9W38Z8mYEAzU2UzvGm50AMcFqEXheSBEw7c3LZFRoE"
$ key="2d4a75512e73b8761400b49aff747af368a18de82d3865fe597efaf6d11053f9"
$ iv="ebc3a59998fe444066b5fd819578d564"
$ echo -n $encrypted_text | openssl enc -d -aes-256-cbc -base64 -nosalt -A -K $key -iv $iv
'firstName' changed: 'terry' -> 'Terry'
'lastName' changed: 'pratchett' -> 'Pratchett'
```
