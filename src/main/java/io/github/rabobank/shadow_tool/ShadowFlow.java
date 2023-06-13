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

import javax.crypto.Cipher;
import java.security.PublicKey;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static java.util.stream.Collectors.joining;
import static org.javers.core.diff.ListCompareAlgorithm.LEVENSHTEIN_DISTANCE;

/**
 * @param <T> The model that the current and new flow should be mapped to for comparison.
 */
public class ShadowFlow<T> {

    private static final int ZERO = 0;
    private static final int HUNDRED = 100;
    private static final Logger logger = LoggerFactory.getLogger(ShadowFlow.class);
    private static final String INSTANCE_PREFIX_FORMAT = "[instance=%s]";
    private static final String DEFAULT_INSTANCE_NAME = "default";
    private static final String MESSAGE_FORMAT = "{} Calling new flow: {}";
    private static final String DEFAULT_ALGORITHM = "RSA";
    private static final String DEFAULT_ALGORITHM_MODE_PADDING =
            DEFAULT_ALGORITHM + "/ECB/OAEPWITHSHA-256ANDMGF1PADDING";

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
        final var nonNullInstanceName = instanceName == null ? DEFAULT_INSTANCE_NAME : instanceName;
        instanceNameLogPrefix = String.format(INSTANCE_PREFIX_FORMAT, nonNullInstanceName);

        if (executorService != null) {
            this.executorService = executorService;
            scheduler = Schedulers.fromExecutor(executorService);
        } else {
            this.executorService = Executors.newCachedThreadPool();
            scheduler = Schedulers.boundedElastic();
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
     * @param clazz       The model that the current and new flow should be mapped to for comparison.
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

        return Mono.deferContextual(contextView ->
                currentFlow.doOnNext(currentResponse -> {
                    logger.info(MESSAGE_FORMAT, instanceNameLogPrefix, callNewFlow);
                    if (callNewFlow) {
                        newFlow.doOnNext(newResponse -> logDifferences(javers.compare(currentResponse, newResponse)))
                                .contextWrite(contextView)
                                .subscribeOn(scheduler)
                                .subscribe();
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
     * @param clazz       The model that the current and new flow should be mapped to for comparison.
     * @return This will always return the mono of currentFlow.
     */
    public Mono<Collection<T>> compareCollections(final Mono<? extends Collection<T>> currentFlow, final Mono<? extends Collection<T>> newFlow, final Class<T> clazz) {
        final var callNewFlow = shouldCallNewFlow();

        return Mono.deferContextual(contextView ->
                currentFlow.doOnNext(currentResponse -> {
                    logger.info(MESSAGE_FORMAT, instanceNameLogPrefix, callNewFlow);
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
        logger.info(MESSAGE_FORMAT, instanceNameLogPrefix, callNewFlow);

        if (callNewFlow) {
            try {
                executorService.submit(() -> logDifferences(diffSupplier.get()));
            } catch (final Exception e) {
                logger.error("{} Failed to run the shadow flow", instanceNameLogPrefix, e);
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

    /**
     * @param <T> The model that the current and new flow should be mapped to for comparison.
     */
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
         * Since the data is potentially sensitive, encryption is required. Mutually exclusive with
         * {@link ShadowFlowBuilder#withCipher(Cipher cipher) withCipher }. This method will use a cipher with
         * RSA/ECB/OAEPWITHSHA-256ANDMGF1PADDING
         *
         * @param publicKey The public RSA key used for encryption, should be at least 2048 bits.
         * @return This builder.
         */
        public ShadowFlowBuilder<T> withEncryption(final PublicKey publicKey) {
            try {
                final var cipher = Cipher.getInstance(DEFAULT_ALGORITHM_MODE_PADDING);
                cipher.init(Cipher.ENCRYPT_MODE, publicKey);
                withCipher(cipher);
            } catch (Exception e) {
                logger.error("Invalid encryption setup. Encryption and logging of values is disabled", e);
            }
            return this;
        }

        /**
         * This configures the shadow flow to log the values of the differences found between the two flows.
         * Since the data is potentially sensitive, encryption is required. Provide your cryptographic cipher
         * for this encryption. Mutually exclusive with
         * {@link ShadowFlowBuilder#withEncryption(PublicKey publicKey) withEncryption }
         *
         * @param cipher The cipher that will do the encryption.
         * @return This builder.
         */
        public ShadowFlowBuilder<T> withCipher(final Cipher cipher) {
            try {
                encryptionService = new EncryptionService(cipher);
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

        /**
         * Build a new ShadowFlow instance.
         *
         * @return New instance of ShadowFlow
         */
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
