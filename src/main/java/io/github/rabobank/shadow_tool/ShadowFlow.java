package io.github.rabobank.shadow_tool;

import org.javers.common.string.PrettyValuePrinter;
import org.javers.core.Javers;
import org.javers.core.JaversBuilder;
import org.javers.core.diff.Diff;
import org.javers.core.diff.changetype.PropertyChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static java.util.stream.Collectors.joining;
import static org.javers.core.diff.ListCompareAlgorithm.LEVENSHTEIN_DISTANCE;

public class ShadowFlow<T> {

    private static final int ZERO = 0;
    private static final int HUNDRED = 100;
    private static final Logger logger = LoggerFactory.getLogger(ShadowFlow.class);
    private static final String INSTANCE_PREFIX_FORMAT = "[instance=%s]";
    private static final String DEFAULT_INSTANCE_NAME = "default";
    private final int percentage;
    private final ExecutorService executorService;
    private final EncryptionService encryptionService;
    private final Scheduler scheduler;
    private final String instanceNameLogPrefix;

    ShadowFlow(final int percentage,
               final ExecutorService executorService,
               final EncryptionService encryptionService,
               final String instanceName) {
        this.percentage = percentage;
        this.encryptionService = encryptionService;
        final String nonNullInstanceName = instanceName == null ? DEFAULT_INSTANCE_NAME : instanceName;
        this.instanceNameLogPrefix = String.format(INSTANCE_PREFIX_FORMAT, nonNullInstanceName);

        if (executorService != null) {
            this.executorService = executorService;
            this.scheduler = Schedulers.fromExecutor(executorService);
        } else {
            this.executorService = Executors.newCachedThreadPool();
            this.scheduler = Schedulers.boundedElastic();
        }
    }

    private final Javers javers = JaversBuilder.javers()
            .withListCompareAlgorithm(LEVENSHTEIN_DISTANCE)
            .build();


    /**
     * This will always call currentFlow, and based on the percentage also call the
     * newFlow. Ex: if percentage is 40%, it will always call currentFlow, and newFlow
     * will be called for 40% of the total requests. It will compare the results and
     * the differences will be logged using Slf4j.
     * <p>
     * The shadow flow minimizes the impact on the production flow by doing the comparison
     * asynchronously. Calling the newFlow, comparing the results and logging it to Slf4j
     * is all done on a separate thread.
     *
     * @param currentFlow A supplier that returns the result of the service call
     *                    that you currently have.
     * @param newFlow     A supplier that return the result of the new service call
     *                    that you want to start using.
     * @return This will always return the value of currentFlow supplier.
     */
    public T compare(final Supplier<T> currentFlow, final Supplier<T> newFlow) {
        final var currentFlowResponse = currentFlow.get();
        doShadowFlow(() -> javers.compare(currentFlowResponse, newFlow.get()));

        return currentFlowResponse;
    }

    /**
     * This will always call currentFlow, and based on the percentage also call the
     * newFlow. Ex: if percentage is 40%, it will always call currentFlow, and newFlow
     * will be called for 40% of the total requests. It will compare the results and
     * the differences will be logged using Slf4j.
     * <p>
     * The shadow flow minimizes the impact on the production flow by doing the comparison
     * asynchronously. Calling the newFlow, comparing the results and logging it to Slf4j
     * is all done on a separate thread.
     * <p>
     * When you are comparing a collection of objects, you have to use this method
     *
     * @param currentFlow A supplier that returns the result of the service call
     *                    that you currently have.
     * @param newFlow     A supplier that return the result of the new service call
     *                    that you want to start using.
     * @return This will always return the value of currentFlow supplier.
     */
    public Collection<T> compareCollections(final Supplier<Collection<T>> currentFlow, final Supplier<Collection<T>> newFlow, final Class<T> clazz) {
        final var currentFlowResponse = currentFlow.get();
        doShadowFlow(() -> javers.compareCollections(currentFlowResponse, newFlow.get(), clazz));

        return currentFlowResponse;
    }

    /**
     * Reactive API for shadow flow. This will always return currentFlow, and based
     * on the percentage also call the newFlow. Ex: if percentage is 40%, it will always
     * call currentFlow, and newFlow will be called for 40% of the total requests.
     * It will compare the results and the differences will be logged using Slf4j.
     *
     * @param currentFlow A mono that returns the result of the service call
     *                    that you currently have.
     * @param newFlow     A mono that returns the result of the new service call
     *                    that you want to start using.
     * @return This will always return the mono of currentFlow.
     */
    public Mono<T> compare(final Mono<T> currentFlow, final Mono<T> newFlow) {
        final var callNewFlow = shouldCallNewFlow();
        logger.info("{} Calling new flow: {}", instanceNameLogPrefix, callNewFlow);

        return Mono.deferContextual(contextView ->
                currentFlow.doOnNext(currentResponse -> {
                    if (callNewFlow) {
                        newFlow.doOnNext(newResponse -> logDifferences(javers.compare(currentResponse, newResponse)))
                                .contextWrite(contextView)
                                .subscribeOn(scheduler)
                                .subscribe()
                        ;
                    }
                }));
    }

    /**
     * Reactive API for shadow flow. This will always return currentFlow, and based
     * on the percentage also call the newFlow. Ex: if percentage is 40%, it will always
     * call currentFlow, and newFlow will be called for 40% of the total requests.
     * It will compare the results and the differences will be logged using Slf4j.
     * <p>
     * When you are comparing a collection of objects, you have to use this method
     *
     * @param currentFlow A mono that returns the result of the service call
     *                    that you currently have.
     * @param newFlow     A mono that returns the result of the new service call
     *                    that you want to start using.
     * @return This will always return the mono of currentFlow.
     */
    public Mono<? extends Collection<T>> compareCollections(final Mono<? extends Collection<T>> currentFlow, final Mono<? extends Collection<T>> newFlow, final Class<T> clazz) {
        final var callNewFlow = shouldCallNewFlow();
        logger.info("{} Calling new flow: {}", instanceNameLogPrefix, callNewFlow);

        return Mono.deferContextual(contextView ->
                currentFlow.doOnNext(currentResponse -> {
                    if (callNewFlow) {
                        newFlow.doOnNext(newResponse -> logDifferences(javers.compareCollections(currentResponse, newResponse, clazz)))
                                .contextWrite(contextView)
                                .subscribeOn(scheduler)
                                .subscribe();
                    }
                }));
    }

    private void doShadowFlow(final Supplier<Diff> diffSupplier) {
        final var callNewFlow = shouldCallNewFlow();
        logger.info("{} Calling new flow: {}", instanceNameLogPrefix, callNewFlow);

        if (callNewFlow) {
            try {
                executorService.submit(() -> logDifferences(diffSupplier.get()));
            } catch (final Throwable t) {
                logger.error("{} Failed to run the shadow flow", instanceNameLogPrefix, t);
            }
        }
    }

    private void logDifferences(final Diff differences) {
        if (differences.hasChanges()) {
            final var propertyNames = differences.getChanges().stream()
                    .map(change -> ((PropertyChange<?>) change).getPropertyName())
                    .collect(joining(", "));

            if (logger.isInfoEnabled()) { // This is mostly to ensure that we do not encrypt needlessly
                if (encryptionService != null) {
                    final var values = differences.getChanges().stream()
                            .map(change -> change.prettyPrint(PrettyValuePrinter.getDefault()))
                            .collect(joining("\n"));

                    final var encryptedValues = encryptionService.encrypt(values);
                    logger.info("{} The following differences were found: {}. Encrypted values: {}", instanceNameLogPrefix, propertyNames, encryptedValues);
                } else {
                    logger.info("{} The following differences were found: {}", instanceNameLogPrefix, propertyNames);
                }
            }
        }
    }

    private boolean shouldCallNewFlow() {
        return ThreadLocalRandom.current().nextInt(HUNDRED) < percentage;
    }

    public static class ShadowFlowBuilder<T> {

        private final Logger logger = LoggerFactory.getLogger(ShadowFlowBuilder.class);

        private final int percentage;

        private ExecutorService executorService;

        private EncryptionService encryptionService;

        private String instanceName;

        /**
         * Creates a new instance of a ShadowFlowBuilder which is used to configure and create a ShadowFlow instance.
         *
         * @param percentage Percentage of how many calls should be compared in the shadow flow.
         *                   This should be in the range of 0-100.
         *                   Zero effectively disables the shadow flow (but the main flow will always run).
         */
        public ShadowFlowBuilder(final int percentage) {
            this.percentage = validatePercentage(percentage);
        }

        /**
         * This allows you to configure your own ExecutorService.
         *
         * @param executorService The ExecutorService which will be used to ensure that the second service call and the
         *                        comparing mechanism is executed on a different thread. This ensures that the main flow
         *                        will not be impacted by the new flow.
         *                        By default, a CachedThreadPool will be created.
         * @return This builder.
         */
        public ShadowFlowBuilder<T> withExecutorService(final ExecutorService executorService) {
            this.executorService = executorService;
            return this;
        }

        /**
         * This configures the shadow flow to log the values of the differences found between the two flows.
         * Since the data is potentially sensitive, encryption is required.
         *
         * @param keyInHex                  The key used for encryption, should be 32 bytes length formatted as a Hex string.
         * @param initializationVectorInHex The IV used for encryption, should be 16 bytes length, formatted as a Hex string.
         * @return This builder.
         */
        public ShadowFlowBuilder<T> withEncryption(final String keyInHex, final String initializationVectorInHex) {
            try {
                this.encryptionService = new EncryptionService(keyInHex, initializationVectorInHex);
            } catch (Exception e) {
                logger.error("Invalid encryption setup. Encryption and logging of values is disabled", e);
            }
            return this;
        }

        /**
         * If the shadow tool is used for two separate use-cases in one application, this allows you to distinguish them.
         *
         * @param instanceName The name of the instance which will end up in the logs.
         * @return This builder.
         */
        public ShadowFlowBuilder<T> withInstanceName(final String instanceName) {
            this.instanceName = instanceName;
            return this;
        }

        public ShadowFlow<T> build() {
            return new ShadowFlow<>(percentage, executorService, encryptionService, instanceName);
        }

        private int validatePercentage(final int percentage) {
            if (percentage < ZERO || percentage > HUNDRED) {
                logger.error("Invalid percentage! Must be within the range of 0 and 100. Got {}. " +
                        "The shadow flow will be effectively disabled by setting it to 0%.", percentage);
                return ZERO;
            }

            return percentage;
        }
    }
}
