package io.github.rabobank.shadow_tool;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.rabobank.shadow_tool.ShadowFlow.ShadowFlowBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShadowFlowTest {

    private static final Logger logger = ((Logger) LoggerFactory.getLogger(ShadowFlow.class));
    private static ListAppender<ILoggingEvent> listAppender;

    private static final DummyObject dummyObjectA = new DummyObject("Bob", "Utrecht", List.of("Mirabel", "Bruno"));
    private static final DummyObject dummyObjectB = new DummyObject("Bob", "Amsterdam", List.of("Bruno", "Mirabel", "Mirabel"));

    @BeforeAll
    static void init() {
        listAppender = new ListAppender<>();
        logger.addAppender(listAppender);
        listAppender.start();
    }

    @BeforeEach
    void beforeEach() {
        listAppender.list.clear();
    }

    @ParameterizedTest
    @MethodSource("executorArguments")
    void shouldAlwaysReturnCurrentFlow(final ExecutorService executorService) {
        final var shadowFlow = new ShadowFlowBuilder<DummyObject>(100)
                .withExecutorService(executorService).build();

        final var result = shadowFlow.compare(
                () -> dummyObjectA,
                () -> dummyObjectB
        );

        assertEquals(dummyObjectA, result);
    }

    @ParameterizedTest
    @MethodSource("executorArguments")
    void shouldAlwaysReturnCurrentFlowReactive(final ExecutorService executorService) {
        final var shadowFlow = new ShadowFlowBuilder<DummyObject>(100)
                .withExecutorService(executorService).build();

        final var result = shadowFlow.compare(
                Mono.just(dummyObjectA),
                Mono.just(dummyObjectB)
        );

        assertEquals(dummyObjectA, result.block());
    }

    @ParameterizedTest
    @MethodSource("executorArguments")
    void shouldCallCurrentFlowOnlyOnce(final ExecutorService executorService) {
        final var shadowFlow = new ShadowFlowBuilder<DummyObject>(100)
                .withExecutorService(executorService).build();

        final var callCounter = new AtomicInteger(0);
        shadowFlow.compare(
                Mono.fromCallable(() -> {
                    callCounter.incrementAndGet();
                    return dummyObjectA;
                }),
                Mono.just(dummyObjectB)
        ).block();

        assertEquals(1, callCounter.get());
    }

    @Test
    void shouldAlwaysReturnCurrentFlowReactiveWithErrorInShadowFlow() {
        final List<Throwable> exceptions = new ArrayList<>();

        final var result = createBlockingShadowFlow(100).compare(
                Mono.just(dummyObjectA),
                Mono.<DummyObject>error(new Exception("Something happened in the shadow flow!")).doOnError(exceptions::add)
        );

        assertEquals(dummyObjectA, result.block());
        assertEquals(1, exceptions.size());
        assertEquals("Something happened in the shadow flow!", exceptions.get(0).getMessage());
    }

    @SuppressWarnings("ReactiveStreamsUnusedPublisher")
    @ParameterizedTest
    @MethodSource("executorArguments")
    void shouldAlwaysReturnCurrentFlowReactiveWithErrorInCurrentFlow(final ExecutorService executorService) {
        final var shadowFlow = new ShadowFlowBuilder<DummyObject>(100)
                .withExecutorService(executorService).build();

        final var result = shadowFlow.compare(
                Mono.error(new IllegalArgumentException("Something happened in the current flow!")),
                Mono.just(dummyObjectB)
        );

        assertThrows(IllegalArgumentException.class, result::block, "Something happened in the current flow!");
    }

    @Test
    void verifyDifferencesAreLoggedReactive() {
        createBlockingShadowFlow(100).compare(
                Mono.just(dummyObjectA),
                Mono.just(dummyObjectB)
        ).block();

        assertThatLogContains("The following differences were found: place, madrigals");
    }

    @Test
    void shouldRunShadowFlowAsynchronouslyByDefaultReactive() {
        final Executable shadowCall = () -> new ShadowFlowBuilder<DummyObject>(100).build().compare(
                Mono.just(dummyObjectA),
                Mono.just(dummyObjectB).delayElement(Duration.ofSeconds(5))
        ).block(Duration.ofMillis(100));

        assertDoesNotThrow(shadowCall);
    }

    @Test
    void shouldRunShadowFlowMonoCollections() {
        createBlockingShadowFlow(100).compareCollections(
                Mono.just(List.of(dummyObjectA)),
                Mono.just(List.of(dummyObjectB)),
                DummyObject.class
        ).block();

        assertThatLogContains("The following differences were found: place, madrigals");
    }

    @Test
    void shouldRunShadowFlowMonoCollectionsWithVariables() {
        final var asyncCallA = List.of(dummyObjectA);
        final var asyncCallB = List.of(dummyObjectB);

        createBlockingShadowFlow(100).compareCollections(
                Mono.just(asyncCallA),
                Mono.just(asyncCallB),
                DummyObject.class
        ).block();

        assertThatLogContains("The following differences were found: place, madrigals");
    }

    // Same test, slightly different input type to validate "? extends Collection<T>"
    @Test
    void shouldRunShadowFlowMonoWithCollectionExtendWithVariables() {
        final var asyncCallA = Mono.just(List.of(dummyObjectA));
        final var asyncCallB = Mono.just(List.of(dummyObjectB));

        createBlockingShadowFlow(100).compareCollections(
                asyncCallA,
                asyncCallB,
                DummyObject.class
        ).block();

        assertThatLogContains("The following differences were found: place, madrigals");
    }

    @Test
    void shouldRunShadowFlowAsynchronouslyByDefault() {
        final var isShadowFlowDone = new AtomicBoolean(false);
        final var shadowFlow = new ShadowFlowBuilder<DummyObject>(100).build();
        final var result = shadowFlow.compare(
                () -> dummyObjectA,
                () -> {
                    await().atMost(1, SECONDS);
                    isShadowFlowDone.set(true);
                    return dummyObjectB;
                }
        );
        assertEquals(dummyObjectA, result);
        assertFalse(isShadowFlowDone.get());
    }

    @ParameterizedTest
    @MethodSource("verifyLogMessageArguments")
    void verifyLogs(final int percentage, final String message) {
        final var shadowFlow = createBlockingShadowFlow(percentage);
        shadowFlow.compare(
                () -> dummyObjectA,
                () -> dummyObjectB
        );

        assertThatLogContains(message);
    }

    @Test
    void verifyEncryptedValueDifferencesAreLogged() {
        final var mockedEncryptionService = mock(EncryptionService.class);
        final var shadowFlow = new ShadowFlow<>(100, new SameThreadExecutorService(), mockedEncryptionService, null);
        when(mockedEncryptionService.encrypt(anyString())).thenReturn("<encrypted-data>");

        shadowFlow.compare(
                () -> dummyObjectA,
                () -> dummyObjectB
        );

        assertThatLogContains("The following differences were found: place, madrigals. Encrypted values: <encrypted-data>");
    }

    @Test
    void verifyInstanceNameCanBeOverridden() {
        final var shadowFlow = new ShadowFlowBuilder<DummyObject>(100)
                .withExecutorService(new SameThreadExecutorService())
                .withInstanceName("custom-identity")
                .build();

        shadowFlow.compare(
                () -> dummyObjectA,
                () -> dummyObjectB
        );

        assertThatLogContains("[instance=custom-identity] The following differences were found: place, madrigals");
    }

    @Test
    void verifyPercentageWorks() {
        final var counter = new AtomicInteger();
        final var shadowFlow = createBlockingShadowFlow(50);

        IntStream.range(0, 1000).forEach(ignored -> shadowFlow.compare(
                () -> dummyObjectA,
                () -> {
                    counter.incrementAndGet();
                    return dummyObjectB;
                }
        ));

        assertTrue(() -> counter.get() >= 400 && counter.get() <= 600);
    }

    @Test
    void shouldNotFailOnError() {
        final var shadowFlow = createBlockingShadowFlow(100);
        final var result = shadowFlow.compare(
                () -> dummyObjectA,
                () -> {
                    throw new RuntimeException("What is happening");
                }
        );

        assertEquals(dummyObjectA, result);
    }

    @Test
    void shouldNotFailOnErrorWhenSubmittingATask() {
        final var mockedExecutorService = mock(ExecutorService.class);
        when(mockedExecutorService.submit(any(Runnable.class)))
                .thenThrow(RejectedExecutionException.class);
        final var shadowFlow = new ShadowFlowBuilder<DummyObject>(100)
                .withExecutorService(mockedExecutorService)
                .build();

        final var result = shadowFlow.compare(
                () -> dummyObjectA,
                () -> dummyObjectB
        );

        assertEquals(dummyObjectA, result);
        assertThatLogContains("Failed to run the shadow flow");
    }

    @Test
    void shouldBeAbleToCompareCollectionOfObjects() {
        final var shadowFlow = createBlockingShadowFlow(100);

        shadowFlow.compareCollections(
                () -> List.of(dummyObjectA),
                () -> List.of(dummyObjectB),
                DummyObject.class
        );

        assertThatLogContains("The following differences were found: place, madrigals");
    }

    @Test
    void typeOfCollectionShouldBeTheResult() {
        final var shadowFlow = createBlockingShadowFlow(100);

        final List<DummyObject> result = shadowFlow.compareCollections(
                () -> List.of(dummyObjectA),
                () -> List.of(dummyObjectB),
                DummyObject.class
        );

        assertEquals(result, List.of(dummyObjectA));
    }

    @Test
    void typeOfCollectionShouldBeTheResultForMonos() {
        final var shadowFlow = createBlockingShadowFlow(100);

        final Set<DummyObject> result = shadowFlow.compareCollections(
                Mono.just(Set.of(dummyObjectA)),
                Mono.just(Set.of(dummyObjectB)),
                DummyObject.class
        ).block();

        assertEquals(result, Set.of(dummyObjectA));
    }

    private ShadowFlow<DummyObject> createBlockingShadowFlow(final int percentage) {
        return new ShadowFlowBuilder<DummyObject>(percentage)
                .withExecutorService(new SameThreadExecutorService())
                .build();
    }

    private static void assertThatLogContains(final String expectedMessage) {
        assertTrue(listAppender.list.stream().anyMatch(event -> event.getFormattedMessage().contains(expectedMessage)));
    }

    static Stream<Arguments> executorArguments() {
        return Stream.of(
                Arguments.of(Executors.newCachedThreadPool()),
                Arguments.of(new SameThreadExecutorService())
        );
    }

    static Stream<Arguments> verifyLogMessageArguments() {
        return Stream.of(
                Arguments.of(100, "The following differences were found: place, madrigals"),
                Arguments.of(100, "[instance=default] The following differences were found: place, madrigals"),
                Arguments.of(101, "Invalid percentage! Must be within the range of 0 and 100. Got 101. The shadow flow will be effectively disabled by setting it to 0%.")
        );
    }
}
