package io.github.rabobank.shadow_tool;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.rabobank.shadow_tool.ShadowFlow.ShadowFlowBuilder;
import org.javers.core.metamodel.annotation.Id;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Hooks;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ShadowFlowTest {

    private static final Logger logger = (Logger) LoggerFactory.getLogger(ShadowFlow.class);
    private static ListAppender<ILoggingEvent> listAppender;

    private static final DummyObject dummyObjectA = new DummyObject("Bob", "Utrecht", List.of("Mirabel", "Bruno"));
    private static final DummyObject dummyObjectB = new DummyObject("Bob", "Amsterdam", List.of("Bruno", "Mirabel", "Mirabel"));

    @BeforeAll
    static void init() {
        listAppender = new ListAppender<>();
        listAppender.list = new CopyOnWriteArrayList<>();
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
                .withExecutor(executorService).build();

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
                .withExecutor(executorService).build();

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
                .withExecutor(executorService).build();

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
        assertEquals("Something happened in the shadow flow!", exceptions.getFirst().getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldHandleReactiveShadowErrorsWithoutDroppingThem(final boolean collections) {
        final var droppedErrors = new ArrayList<Throwable>();
        final var failure = new IllegalStateException("shadow failure");
        final var shadowFlow = createBlockingShadowFlow(100);
        Hooks.onErrorDropped(droppedErrors::add);
        try {
            if (collections) {
                assertEquals(List.of(dummyObjectA), shadowFlow.compareCollections(
                        Mono.just(List.of(dummyObjectA)), Mono.error(failure), DummyObject.class
                ).block());
            } else {
                assertEquals(dummyObjectA, shadowFlow.compare(
                        Mono.just(dummyObjectA), Mono.error(failure)
                ).block());
            }
            assertThatLogContains("Failed to run the shadow flow");
            assertTrue(droppedErrors.isEmpty(), () -> "Unexpected dropped errors: " + droppedErrors);
        } finally {
            Hooks.resetOnErrorDropped();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldSampleEachReactiveSubscription(final boolean collections) {
        final var calls = new AtomicInteger();
        final var shadowFlow = createBlockingShadowFlow(50);
        final Mono<?> comparison = collections
                ? shadowFlow.compareCollections(Mono.just(List.of(dummyObjectA)),
                        Mono.fromSupplier(() -> {
                            calls.incrementAndGet();
                            return List.of(dummyObjectB);
                        }), DummyObject.class)
                : shadowFlow.compare(Mono.just(dummyObjectA), Mono.fromSupplier(() -> {
                    calls.incrementAndGet();
                    return dummyObjectB;
                }));

        IntStream.range(0, 1000).forEach(ignored -> comparison.block());

        assertTrue(calls.get() >= 400 && calls.get() <= 600, () -> "Shadow calls: " + calls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldPropagateReactorContextToShadowFlow(final boolean collections) {
        final var shadowFlow = createBlockingShadowFlow(100);
        final var contextValue = new CompletableFuture<String>();
        final Mono<?> comparison = collections
                ? shadowFlow.compareCollections(Mono.just(List.of(dummyObjectA)),
                        Mono.deferContextual(context -> {
                            contextValue.complete(context.get("requestId"));
                            return Mono.just(List.of(dummyObjectB));
                        }), DummyObject.class)
                : shadowFlow.compare(Mono.just(dummyObjectA), Mono.deferContextual(context -> {
                    contextValue.complete(context.get("requestId"));
                    return Mono.just(dummyObjectB);
                }));

        comparison.contextWrite(Context.of("requestId", "request-123")).block();

        assertEquals("request-123", contextValue.getNow(null));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldLogRejectedReactiveShadowWork(final boolean collections) {
        try (final var executor = Executors.newSingleThreadExecutor()) {
            executor.shutdown();
            final var shadowFlow = new ShadowFlowBuilder<DummyObject>(100).withExecutor(executor).build();
            if (collections) {
                assertEquals(List.of(dummyObjectA), shadowFlow.compareCollections(
                        Mono.just(List.of(dummyObjectA)), Mono.just(List.of(dummyObjectB)), DummyObject.class
                ).block());
            } else {
                assertEquals(dummyObjectA, shadowFlow.compare(
                        Mono.just(dummyObjectA), Mono.just(dummyObjectB)
                ).block());
            }
            assertThatLogContains("Failed to run the shadow flow");
        }
    }

    @Test
    void shouldNotEncryptWhenInfoLoggingIsDisabled() {
        final var encryptionService = mock(EncryptionService.class);
        final var shadowFlow = new ShadowFlowBuilder<DummyObject>(100)
                .withExecutor(Runnable::run)
                .withEncryptionService(encryptionService)
                .build();
        final var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
        try {
            assertEquals(dummyObjectA, shadowFlow.compare(() -> dummyObjectA, () -> dummyObjectB));
            verifyNoInteractions(encryptionService);
        } finally {
            logger.setLevel(previousLevel);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldNotSubscribeShadowWhenCurrentFlowIsEmpty(final boolean collections) {
        final var calls = new AtomicInteger();
        final var shadowFlow = createBlockingShadowFlow(100);
        final Mono<?> comparison = collections
                ? shadowFlow.compareCollections(Mono.<List<DummyObject>>empty(), Mono.fromSupplier(() -> {
                    calls.incrementAndGet();
                    return List.of(dummyObjectB);
                }), DummyObject.class)
                : shadowFlow.compare(Mono.empty(), Mono.fromSupplier(() -> {
                    calls.incrementAndGet();
                    return dummyObjectB;
                }));

        assertNull(comparison.block());
        assertEquals(0, calls.get());
    }

    @SuppressWarnings("ReactiveStreamsUnusedPublisher")
    @ParameterizedTest
    @MethodSource("executorArguments")
    void shouldAlwaysReturnCurrentFlowReactiveWithErrorInCurrentFlow(final ExecutorService executorService) {
        final var shadowFlow = new ShadowFlowBuilder<DummyObject>(100)
                .withExecutor(executorService).build();

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
    void shouldRunShadowFlowAsynchronouslyByDefault() throws Exception {
        final var release = new CompletableFuture<DummyObject>();
        final var completed = new CompletableFuture<Void>();
        final var shadowFlow = new ShadowFlowBuilder<DummyObject>(100).build();
        try {
            final var result = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> shadowFlow.compare(
                    () -> dummyObjectA,
                    () -> {
                        try {
                            return release.join();
                        } finally {
                            completed.complete(null);
                        }
                    }
            ));
            assertEquals(dummyObjectA, result);
            assertFalse(completed.isDone());
        } finally {
            release.complete(dummyObjectB);
        }
        completed.get(5, SECONDS);
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
                .withExecutor(new SameThreadExecutorService())
                .withInstanceName("custom-identity")
                .build();

        shadowFlow.compare(
                () -> dummyObjectA,
                () -> dummyObjectB
        );

        assertThatLogContains("[instance=custom-identity] The following differences were found: place, madrigals");
    }

    @Test
    void shouldRestoreMdcAfterShadowFlow() {
        MDC.put("requestId", "request-123");
        try {
            createBlockingShadowFlow(100).compare(() -> dummyObjectA, () -> dummyObjectB);

            assertEquals("request-123", MDC.get("requestId"));
        } finally {
            MDC.remove("requestId");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRestoreWorkerMdcEvenWhenShadowFails(final boolean failure) throws Exception {
        final var callerContext = MDC.getCopyOfContextMap();
        MDC.clear();
        try (final var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> MDC.put("workerId", "worker-123")).get(5, SECONDS);
            final var observedContext = new CompletableFuture<Map<String, String>>();
            final var shadowFlow = new ShadowFlowBuilder<DummyObject>(100).withExecutor(executor).build();

            assertEquals(dummyObjectA, shadowFlow.compare(() -> dummyObjectA, () -> {
                observedContext.complete(MDC.getCopyOfContextMap());
                if (failure) {
                    throw new IllegalStateException("shadow failure");
                }
                return dummyObjectB;
            }));

            assertNull(observedContext.get(5, SECONDS));
            assertEquals("worker-123", executor.submit(() -> MDC.get("workerId")).get(5, SECONDS));
        } finally {
            if (callerContext == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(callerContext);
            }
        }
    }

    @Test
    void shouldRunBothApisOnCustomVirtualThreads() throws Exception {
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var shadowFlow = new ShadowFlowBuilder<DummyObject>(100)
                    .withExecutor(executor)
                    .build();
            final var synchronousThread = new CompletableFuture<Boolean>();
            final var reactiveThread = new CompletableFuture<Boolean>();

            assertEquals(dummyObjectA, shadowFlow.compare(
                    () -> dummyObjectA,
                    () -> {
                        synchronousThread.complete(Thread.currentThread().isVirtual());
                        return dummyObjectB;
                    }
            ));
            assertEquals(dummyObjectA, shadowFlow.compare(
                    Mono.just(dummyObjectA),
                    Mono.fromSupplier(() -> {
                        reactiveThread.complete(Thread.currentThread().isVirtual());
                        return dummyObjectB;
                    })
            ).block(Duration.ofSeconds(5)));

            assertTrue(synchronousThread.get(5, SECONDS));
            assertTrue(reactiveThread.get(5, SECONDS));
        }
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
    void shouldNotFailOnErrorWhenSubmittingATaskUsingDeprecatedExecutorService() {
        final var mockedExecutorService = mock(ExecutorService.class);
        doThrow(RejectedExecutionException.class).when(mockedExecutorService).execute(any(Runnable.class));
        final var shadowFlow = new ShadowFlowBuilder<DummyObject>(100)
                .withExecutor(mockedExecutorService)
                .build();

        final var result = shadowFlow.compare(
                () -> dummyObjectA,
                () -> dummyObjectB
        );

        assertEquals(dummyObjectA, result);
        assertThatLogContains("Failed to run the shadow flow");
    }

    @Test
    void shouldNotFailOnErrorWhenSubmittingATask() {
        final var mockedExecutor = mock(Executor.class);
        doThrow(RejectedExecutionException.class).when(mockedExecutor).execute(any(Runnable.class));
        final var shadowFlow = new ShadowFlowBuilder<DummyObject>(100)
                .withExecutor(mockedExecutor)
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldLogDifferencesWhenCollectionContainsAnAddedOrRemovedObject(final boolean removal) {
        final var shadowFlow = createBlockingShadowFlow(100);
        final var single = List.of(dummyObjectA);
        final var multiple = List.of(dummyObjectA, dummyObjectB);

        shadowFlow.compareCollections(
                () -> removal ? multiple : single,
                () -> removal ? single : multiple,
                DummyObject.class
        );

        assertThatLogContains("The following differences were found:");
        assertFalse(listAppender.list.stream()
                .anyMatch(event -> event.getFormattedMessage().contains("Failed to run the shadow flow")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldLogEntityLifecycleChanges(final boolean removal) {
        final var shadowFlow = new ShadowFlowBuilder<IdentifiedObject>(100)
                .withExecutor(Runnable::run)
                .build();
        final var objects = List.of(new IdentifiedObject(1, "Utrecht"));
        final var empty = List.<IdentifiedObject>of();

        shadowFlow.compareCollections(
                () -> removal ? objects : empty,
                () -> removal ? empty : objects,
                IdentifiedObject.class
        );

        assertThatLogContains(removal ? "ObjectRemoved" : "NewObject");
        assertFalse(listAppender.list.stream()
                .anyMatch(event -> event.getFormattedMessage().contains("Failed to run the shadow flow")));
    }

    private record IdentifiedObject(@Id int id, String city) {}

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
                .withExecutor(new SameThreadExecutorService())
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
