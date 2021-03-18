/*
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.hono.application.client.kafka.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.eclipse.hono.application.client.DownstreamMessage;
import org.eclipse.hono.application.client.MessageConsumer;
import org.eclipse.hono.application.client.kafka.KafkaMessageContext;
import org.eclipse.hono.client.kafka.CachingKafkaProducerFactory;
import org.eclipse.hono.client.kafka.HonoTopic.Type;
import org.eclipse.hono.client.kafka.KafkaProducerConfigProperties;
import org.eclipse.hono.client.kafka.consumer.KafkaConsumerConfigProperties;
import org.eclipse.hono.kafka.test.KafkaClientUnitTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.client.consumer.KafkaConsumer;

/**
 * Verifies behavior of {@link KafkaApplicationClientImpl}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
public class KafkaApplicationClientImplTest {
    private static final String PARAMETERIZED_TEST_NAME_PATTERN = "{displayName} [{index}]; parameters: {argumentsWithNames}";
    private KafkaApplicationClientImpl client;
    private MockConsumer<String, Buffer> mockConsumer;
    private String tenantId;

    static Stream<Type> messageTypes() {
        return Stream.of(
                Type.TELEMETRY,
                Type.EVENT,
                Type.COMMAND_RESPONSE);
    }

    /**
     *
     * Sets up fixture.
     *
     * @param vertx The vert.x instance to use.
     */
    @BeforeEach
    void setUp(final Vertx vertx) {
        final KafkaProducerConfigProperties producerConfig = new KafkaProducerConfigProperties();
        final MockProducer<String, Buffer> mockProducer = KafkaClientUnitTestHelper.newMockProducer(true);
        final CachingKafkaProducerFactory<String, Buffer> producerFactory = KafkaClientUnitTestHelper
                .newProducerFactory(mockProducer);

        tenantId = UUID.randomUUID().toString();

        mockConsumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);

        final KafkaConsumerConfigProperties consumerConfig = new KafkaConsumerConfigProperties();
        consumerConfig.setConsumerConfig(Map.of("client.id", "application-test-consumer"));
        producerConfig.setProducerConfig(Map.of("client.id", "application-test-sender"));

        client = new KafkaApplicationClientImpl(vertx, consumerConfig, producerFactory, producerConfig);
        client.setKafkaConsumerFactory(() -> KafkaConsumer.create(vertx, mockConsumer));
    }

    /**
     * Cleans up fixture.
     *
     * @param context The vert.x test context.
     */
    @AfterEach
    void shutDown(final VertxTestContext context) {
        client.stop().onComplete(r -> context.completeNow());
    }

    /**
     * Verifies that the message consumer is successfully created by the application client.
     *
     * @param msgType The message type (telemetry, event or command_response)
     * @param ctx The vert.x test context.
     */
    @ParameterizedTest(name = PARAMETERIZED_TEST_NAME_PATTERN)
    @MethodSource("messageTypes")
    public void testCreateConsumer(final Type msgType, final VertxTestContext ctx) {

        //Verify that the consumer for the given tenant and the message type is successfully created
        createConsumer(tenantId, msgType, m -> {}, t -> {})
                .onComplete(ctx.succeeding(consumer -> ctx.verify(() -> {
                    assertThat(consumer).isNotNull();
                    ctx.completeNow();
                })));
    }

    /**
     * Verifies that the underlying Kafka consumer is closed when {@link MessageConsumer#close()} is invoked.
     *
     * @param msgType The message type (telemetry, event or command_response)
     * @param ctx The vert.x test context.
     */
    @ParameterizedTest(name = PARAMETERIZED_TEST_NAME_PATTERN)
    @MethodSource("messageTypes")
    public void testCloseConsumer(final Type msgType, final VertxTestContext ctx) {
        // Given a consumer for the given tenant and the message type
        createConsumer(tenantId, msgType, m -> {}, t -> {})
                // When the message consumer is closed
                .compose(MessageConsumer::close)
                .onComplete(ctx.succeeding(consumer -> ctx.verify(() -> {
                    // verify that the Kafka mock consumer is also closed
                    assertThat(mockConsumer.closed()).isTrue();
                    ctx.completeNow();
                })));
    }

    private Future<MessageConsumer> createConsumer(final String tenantId, final Type type,
            final Handler<DownstreamMessage<KafkaMessageContext>> msgHandler, final Handler<Throwable> closeHandler) {

        switch (type) {
        case TELEMETRY:
            return client.createTelemetryConsumer(tenantId, msgHandler, closeHandler);
        case EVENT:
            return client.createEventConsumer(tenantId, msgHandler, closeHandler);
        default:
            return client.createCommandResponseConsumer(tenantId, null, msgHandler, closeHandler);
        }
    }
}