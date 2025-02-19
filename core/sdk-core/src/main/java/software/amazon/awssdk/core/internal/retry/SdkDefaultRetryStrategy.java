/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.core.internal.retry;

import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.retry.RetryUtils;
import software.amazon.awssdk.retries.AdaptiveRetryStrategy;
import software.amazon.awssdk.retries.DefaultRetryStrategy;
import software.amazon.awssdk.retries.LegacyRetryStrategy;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.retries.internal.DefaultAwareRetryStrategy;
import software.amazon.awssdk.retries.internal.RetryStrategyDefaults;

/**
 * Retry strategies used by any SDK client.
 */
@SdkPublicApi
public final class SdkDefaultRetryStrategy {

    private static final String DEFAULTS_NAME = "sdk";

    private static final RetryStrategyDefaults DEFAULTS_PREDICATES = new RetryStrategyDefaults() {
        @Override
        public String name() {
            return DEFAULTS_NAME;
        }

        @Override
        public void applyDefaults(RetryStrategy.Builder<?, ?> builder) {
            configureStrategy(builder);
            markDefaultsAdded(builder);
        }
    };

    private SdkDefaultRetryStrategy() {
    }

    /**
     * Retrieve the default retry strategy for the configured retry mode.
     *
     * @return the default retry strategy for the configured retry mode.
     */
    public static RetryStrategy defaultRetryStrategy() {
        return forRetryMode(RetryMode.defaultRetryMode());
    }

    /**
     * Retrieve the appropriate retry strategy for the retry mode with AWS-specific conditions added.
     *
     * @param mode The retry mode for which we want the retry strategy
     * @return the appropriate retry strategy for the retry mode with AWS-specific conditions added.
     */
    public static RetryStrategy forRetryMode(RetryMode mode) {
        switch (mode) {
            case STANDARD:
                return standardRetryStrategy();
            case ADAPTIVE:
                return legacyAdaptiveRetryStrategy();
            case ADAPTIVE_V2:
                return adaptiveRetryStrategy();
            case LEGACY:
                return legacyRetryStrategy();
            default:
                throw new IllegalStateException("unknown retry mode: " + mode);
        }
    }

    /**
     * Returns the {@link RetryMode} for the given retry strategy.
     *
     * @param retryStrategy The retry strategy to test for
     * @return The retry mode for the given strategy
     */
    public static RetryMode retryMode(RetryStrategy retryStrategy) {
        if (retryStrategy instanceof StandardRetryStrategy) {
            return RetryMode.STANDARD;
        }
        if (retryStrategy instanceof AdaptiveRetryStrategy) {
            return RetryMode.ADAPTIVE_V2;
        }
        if (retryStrategy instanceof LegacyRetryStrategy) {
            return RetryMode.LEGACY;
        }
        if (retryStrategy instanceof RetryPolicyAdapter) {
            return RetryMode.ADAPTIVE;
        }
        throw new IllegalArgumentException("unknown retry strategy class: " + retryStrategy.getClass().getName());
    }

    /**
     * Returns a {@link StandardRetryStrategy} with generic SDK retry conditions.
     *
     * @return a {@link StandardRetryStrategy} with generic SDK retry conditions.
     */
    public static StandardRetryStrategy standardRetryStrategy() {
        return standardRetryStrategyBuilder().build();
    }

    /**
     * Returns a {@link LegacyRetryStrategy} with generic SDK retry conditions.
     *
     * @return a {@link LegacyRetryStrategy} with generic SDK retry conditions.
     */
    public static LegacyRetryStrategy legacyRetryStrategy() {
        return legacyRetryStrategyBuilder().build();
    }

    /**
     * Returns an {@link AdaptiveRetryStrategy} with generic SDK retry conditions.
     *
     * @return an {@link AdaptiveRetryStrategy} with generic SDK retry conditions.
     */
    public static AdaptiveRetryStrategy adaptiveRetryStrategy() {
        return adaptiveRetryStrategyBuilder().build();
    }

    /**
     * Returns a {@link StandardRetryStrategy.Builder} with preconfigured generic SDK retry conditions.
     *
     * @return a {@link StandardRetryStrategy.Builder} with preconfigured generic SDK retry conditions.
     */
    public static StandardRetryStrategy.Builder standardRetryStrategyBuilder() {
        StandardRetryStrategy.Builder builder = DefaultRetryStrategy.standardStrategyBuilder();
        return configure(builder);
    }

    /**
     * Returns a {@link LegacyRetryStrategy.Builder} with preconfigured generic SDK retry conditions.
     *
     * @return a {@link LegacyRetryStrategy.Builder} with preconfigured generic SDK retry conditions.
     */
    public static LegacyRetryStrategy.Builder legacyRetryStrategyBuilder() {
        LegacyRetryStrategy.Builder builder = DefaultRetryStrategy.legacyStrategyBuilder();
        return configure(builder);
    }

    /**
     * Returns an {@link AdaptiveRetryStrategy.Builder} with preconfigured generic SDK retry conditions.
     *
     * @return an {@link AdaptiveRetryStrategy.Builder} with preconfigured generic SDK retry conditions.
     */
    public static AdaptiveRetryStrategy.Builder adaptiveRetryStrategyBuilder() {
        AdaptiveRetryStrategy.Builder builder = DefaultRetryStrategy.adaptiveStrategyBuilder();
        return configure(builder);
    }

    /**
     * Configures a retry strategy using its builder to add SDK-generic retry exceptions.
     *
     * @param builder The builder to add the SDK-generic retry exceptions
     * @param <T>     The type of the builder extending {@link RetryStrategy.Builder}
     * @return The given builder
     */
    public static <T extends RetryStrategy.Builder<T, ?>> T configure(T builder) {
        builder.retryOnException(SdkDefaultRetryStrategy::retryOnRetryableException)
               .retryOnException(SdkDefaultRetryStrategy::retryOnStatusCodes)
               .retryOnException(SdkDefaultRetryStrategy::retryOnClockSkewException)
               .retryOnException(SdkDefaultRetryStrategy::retryOnThrottlingCondition);
        SdkDefaultRetrySetting.RETRYABLE_EXCEPTIONS.forEach(builder::retryOnExceptionOrCauseInstanceOf);
        builder.treatAsThrottling(SdkDefaultRetryStrategy::treatAsThrottling);
        Integer maxAttempts = SdkSystemSetting.AWS_MAX_ATTEMPTS.getIntegerValue().orElse(null);
        if (maxAttempts != null) {
            builder.maxAttempts(maxAttempts);
        }
        if (builder instanceof DefaultAwareRetryStrategy.Builder) {
            DefaultAwareRetryStrategy.Builder b = (DefaultAwareRetryStrategy.Builder) builder;
            b.markDefaultAdded(DEFAULTS_NAME);
        }
        return builder;
    }

    /**
     * Configures a retry strategy using its builder to add SDK-generic retry exceptions.
     *
     * @param builder The builder to add the SDK-generic retry exceptions
     * @return The given builder
     */
    public static RetryStrategy.Builder<?, ?> configureStrategy(RetryStrategy.Builder<?, ?> builder) {
        builder.retryOnException(SdkDefaultRetryStrategy::retryOnRetryableException)
               .retryOnException(SdkDefaultRetryStrategy::retryOnStatusCodes)
               .retryOnException(SdkDefaultRetryStrategy::retryOnClockSkewException)
               .retryOnException(SdkDefaultRetryStrategy::retryOnThrottlingCondition);
        SdkDefaultRetrySetting.RETRYABLE_EXCEPTIONS.forEach(builder::retryOnExceptionOrCauseInstanceOf);
        builder.treatAsThrottling(SdkDefaultRetryStrategy::treatAsThrottling);
        return builder;
    }

    private static boolean treatAsThrottling(Throwable t) {
        if (t instanceof SdkException) {
            return RetryUtils.isThrottlingException((SdkException) t);
        }
        return false;
    }

    private static boolean retryOnRetryableException(Throwable ex) {
        if (ex instanceof SdkException) {
            return RetryUtils.isRetryableException((SdkException) ex);
        }
        return false;
    }

    private static boolean retryOnStatusCodes(Throwable ex) {
        if (ex instanceof SdkServiceException) {
            SdkServiceException failure = (SdkServiceException) ex;
            return SdkDefaultRetrySetting.RETRYABLE_STATUS_CODES.contains(failure.statusCode());
        }
        return false;
    }

    private static boolean retryOnClockSkewException(Throwable ex) {
        if (ex instanceof SdkException) {
            return RetryUtils.isClockSkewException((SdkException) ex);
        }
        return false;
    }

    private static boolean retryOnThrottlingCondition(Throwable ex) {
        if (ex instanceof SdkException) {
            return RetryUtils.isThrottlingException((SdkException) ex);
        }
        return false;
    }

    /**
     * Returns a {@link RetryStrategy} that implements the legacy {@link RetryMode#ADAPTIVE} mode.
     *
     * @return a {@link RetryStrategy} that implements the legacy {@link RetryMode#ADAPTIVE} mode.
     */
    private static RetryStrategy legacyAdaptiveRetryStrategy() {
        return RetryPolicyAdapter.builder()
                                 .retryPolicy(RetryPolicy.forRetryMode(RetryMode.ADAPTIVE))
                                 .build();
    }

    public static RetryStrategyDefaults retryStrategyDefaults() {
        return DEFAULTS_PREDICATES;
    }

    private static void markDefaultsAdded(RetryStrategy.Builder<?, ?> builder) {
        if (builder instanceof DefaultAwareRetryStrategy.Builder) {
            DefaultAwareRetryStrategy.Builder b = (DefaultAwareRetryStrategy.Builder) builder;
            b.markDefaultAdded(DEFAULTS_NAME);
        }
    }

}

