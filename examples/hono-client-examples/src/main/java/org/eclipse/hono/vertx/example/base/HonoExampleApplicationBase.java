/*******************************************************************************
 * Copyright (c) 2016, 2022 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.hono.vertx.example.base;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.eclipse.hono.application.client.ApplicationClient;
import org.eclipse.hono.application.client.DownstreamMessage;
import org.eclipse.hono.application.client.MessageConsumer;
import org.eclipse.hono.application.client.MessageContext;
import org.eclipse.hono.application.client.TimeUntilDisconnectNotification;
import org.eclipse.hono.application.client.amqp.AmqpApplicationClient;
import org.eclipse.hono.application.client.amqp.ProtonBasedApplicationClient;
import org.eclipse.hono.application.client.kafka.impl.KafkaApplicationClientImpl;
import org.eclipse.hono.client.ServiceInvocationException;
import org.eclipse.hono.client.amqp.config.ClientConfigProperties;
import org.eclipse.hono.client.amqp.connection.HonoConnection;
import org.eclipse.hono.client.kafka.CommonKafkaClientConfigProperties;
import org.eclipse.hono.client.kafka.consumer.MessagingKafkaConsumerConfigProperties;
import org.eclipse.hono.client.kafka.producer.CachingKafkaProducerFactory;
import org.eclipse.hono.client.kafka.producer.KafkaProducerFactory;
import org.eclipse.hono.client.kafka.producer.MessagingKafkaProducerConfigProperties;
import org.eclipse.hono.util.Lifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;


/**
 * Example base class for consuming telemetry and event data from devices connected to Hono and sending commands to these devices.
 * <p>
 * This class implements all necessary code to get Hono's messaging consumer client and Hono's command client running.
 * <p>
 * The code consumes data until it receives any input on its console (which finishes it and closes vertx).
 */
@SuppressFBWarnings(
        value = { "HARD_CODE_PASSWORD", "PREDICTABLE_RANDOM" },
        justification = """
                We use the default passwords of the Hono Sandbox installation throughout this class
                for ease of use. The passwords are publicly documented and do not affect any
                private installations of Hono.
                The values returned by the Random are only used as arbitrary values in example message
                payload.
                """)
public class HonoExampleApplicationBase {

    public static final String HONO_CLIENT_USER = System.getProperty("username", "consumer@HONO");
    public static final String HONO_CLIENT_PASSWORD = System.getProperty("password", "verysecret");
    public static final Boolean USE_PLAIN_CONNECTION =
            Boolean.valueOf(System.getProperty("plain.connection", "false"));
    public static final Boolean SEND_ONE_WAY_COMMANDS =
            Boolean.valueOf(System.getProperty("sendOneWayCommands", "false"));
    public static final Boolean USE_KAFKA = Boolean.valueOf(System.getProperty("kafka", "false"));

    private static final Logger LOG = LoggerFactory.getLogger(HonoExampleApplicationBase.class);
    private static final String KAFKA_CONSUMER_GROUP_ID = "hono-example-application";
    private static final String COMMAND_SEND_LIFECYCLE_INFO = "sendLifecycleInfo";
    private static final Random RAND = new Random();

    private final Vertx vertx = Vertx.vertx();
    private final ApplicationClient<? extends MessageContext> client;
    private final int port;

    /**
     * A map holding a handler to cancel a timer that was started to send commands periodically to a device.
     * Only affects devices that use a connection oriented protocol like MQTT.
     */
    private final Map<String, Handler<Void>> periodicCommandSenderTimerCancelerMap = new HashMap<>();
    /**
     * A map holding the last reported notification for a device being connected. Will be emptied as soon as the
     * notification is handled.
     * Only affects devices that use a connection oriented protocol like MQTT.
     */
    private final Map<String, TimeUntilDisconnectNotification> pendingTtdNotification = new HashMap<>();
    private MessageConsumer eventConsumer;
    private MessageConsumer telemetryConsumer;

    /**
     * The client for sending and receiving data is instantiated here.
     * <p>
     * Depending of the value of {@link #USE_KAFKA} either a Kafka based or an AMQP based messaging client is created.
     */
    public HonoExampleApplicationBase() {
        if (USE_KAFKA) {
            port = HonoExampleConstants.HONO_KAFKA_CONSUMER_PORT;
            client = createKafkaApplicationClient();
        } else {
            port = HonoExampleConstants.HONO_AMQP_CONSUMER_PORT;
            client = createAmqpApplicationClient();
        }
    }

    /**
     * The consumer needs one connection to the AMQP 1.0 messaging network from which it can consume data.
     * <p>
     * The client for receiving data is instantiated here.
     * <p>
     * NB: if you want to integrate this code with your own software, it might be necessary to copy the trust
     * store to your project as well and adopt the file path.
     */
    private ApplicationClient<? extends MessageContext> createAmqpApplicationClient() {

        final ClientConfigProperties props = new ClientConfigProperties();
        props.setLinkEstablishmentTimeout(5000L);
        props.setHost(HonoExampleConstants.HONO_MESSAGING_HOST);
        props.setPort(port);
        if (!USE_PLAIN_CONNECTION) {
            props.setUsername(HONO_CLIENT_USER);
            props.setPassword(HONO_CLIENT_PASSWORD);
            props.setTlsEnabled(true);
            props.setServerRole("AMQP Messaging Network");
            props.setTrustStorePath("target/config/hono-demo-certs-jar/trusted-certs.pem");
            props.setHostnameVerificationRequired(false);
        }

        return new ProtonBasedApplicationClient(HonoConnection.newConnection(vertx, props));
    }

    /**
     * Creates an application client for Kafka based messaging. Unlike with AMQP, the Kafka clients manage their
     * connections to the cluster internally.
     * <p>
     * NB: if you want to integrate this code with your own software, it might be necessary to copy the trust store to
     * your project as well and adopt the file path.
     */
    private ApplicationClient<? extends MessageContext> createKafkaApplicationClient() {

        final Map<String, String> properties = new HashMap<>();
        properties.put("bootstrap.servers", HonoExampleConstants.HONO_MESSAGING_HOST + ":" + port);
        // add the following lines with appropriate values to enable TLS
        // properties.put("ssl.truststore.location", "/path/to/file");
        // properties.put("ssl.truststore.password", "secret");

        final CommonKafkaClientConfigProperties commonClientConfig = new CommonKafkaClientConfigProperties();
        commonClientConfig.setCommonClientConfig(properties);
        final MessagingKafkaConsumerConfigProperties consumerConfig = new MessagingKafkaConsumerConfigProperties();
        consumerConfig.setCommonClientConfig(commonClientConfig);
        consumerConfig.setConsumerConfig(Map.of("group.id", KAFKA_CONSUMER_GROUP_ID));

        final MessagingKafkaProducerConfigProperties producerConfig = new MessagingKafkaProducerConfigProperties();
        producerConfig.setCommonClientConfig(commonClientConfig);

        final KafkaProducerFactory<String, Buffer> producerFactory = CachingKafkaProducerFactory.sharedFactory(vertx);
        return new KafkaApplicationClientImpl(vertx, consumerConfig, producerFactory, producerConfig);
    }

    /**
     * Start the application client and set the message handling method to treat data that is received.
     */
    protected void consumeData() {

        final CompletableFuture<ApplicationClient<? extends MessageContext>> startup = new CompletableFuture<>();

        if (client instanceof AmqpApplicationClient) {
            final AmqpApplicationClient ac = (AmqpApplicationClient) client;
            ac.addDisconnectListener(c -> LOG.info("lost connection to Hono, trying to reconnect ..."));
            ac.addReconnectListener(c -> LOG.info("reconnected to Hono"));
        }

        final Promise<Void> readyTracker = Promise.promise();
        client.addOnClientReadyHandler(readyTracker);
        client.start()
                .compose(ok -> readyTracker.future())
                .compose(v -> CompositeFuture.all(createEventConsumer(), createTelemetryConsumer()))
                .onSuccess(ok -> startup.complete(client))
                .onFailure(startup::completeExceptionally);

        try {
            startup.join();
            LOG.info("Consumer ready for telemetry and event messages");
            System.in.read();
        } catch (final CompletionException e) {
            LOG.error("{} consumer failed to start [{}:{}]",
                    USE_KAFKA ? "Kafka" : "AMQP", HonoExampleConstants.HONO_MESSAGING_HOST, port, e.getCause());
        } catch (final IOException e) {
            // nothing we can do
        }

        final CompletableFuture<ApplicationClient<? extends MessageContext>> shutDown = new CompletableFuture<>();

        @SuppressWarnings("rawtypes")
        final List<Future> closeFutures = new ArrayList<>();
        Optional.ofNullable(eventConsumer)
            .map(MessageConsumer::close)
            .ifPresent(closeFutures::add);
        Optional.ofNullable(telemetryConsumer)
            .map(MessageConsumer::close)
            .ifPresent(closeFutures::add);
        Optional.of(client)
            .map(Lifecycle::stop)
            .ifPresent(closeFutures::add);

        CompositeFuture.join(closeFutures)
            .compose(ok -> vertx.close())
            .recover(t -> vertx.close())
            .onComplete(ar -> shutDown.complete(client));

        // wait for clients to be closed
        shutDown.join();
        LOG.info("Consumer has been shut down");
    }

    /**
     * Create the message consumer that handles event messages and invokes the notification callback
     * {@link #handleCommandReadinessNotification(TimeUntilDisconnectNotification)} if the message indicates that it
     * stays connected for a specified time.
     *
     * @return A succeeded future if the creation was successful, a failed Future otherwise.
     */
    private Future<MessageConsumer> createEventConsumer() {
        return client.createEventConsumer(
                HonoExampleConstants.TENANT_ID,
                msg -> {
                    // handle command readiness notification
                    msg.getTimeUntilDisconnectNotification().ifPresent(this::handleCommandReadinessNotification);
                    handleEventMessage(msg);
                },
                cause -> LOG.error("event consumer closed by remote", cause))
            .onSuccess(consumer -> this.eventConsumer = consumer);
    }

    /**
     * Create the message consumer that handles telemetry messages and invokes the notification callback
     * {@link #handleCommandReadinessNotification(TimeUntilDisconnectNotification)} if the message indicates that it
     * stays connected for a specified time.
     *
     * @return A succeeded future if the creation was successful, a failed Future otherwise.
     */
    private Future<MessageConsumer> createTelemetryConsumer() {
        return client.createTelemetryConsumer(
                HonoExampleConstants.TENANT_ID,
                msg -> {
                    // handle command readiness notification
                    msg.getTimeUntilDisconnectNotification().ifPresent(this::handleCommandReadinessNotification);
                    handleTelemetryMessage(msg);
                },
                cause -> LOG.error("telemetry consumer closed by remote", cause))
            .onSuccess(consumer -> this.telemetryConsumer = consumer);
    }

    /**
     * Handler method for a <em>device ready for command</em> notification (by an explicit event or contained
     * implicitly in another message).
     * <p>
     * For notifications with a positive ttd value (as usual for request-response protocols), the
     * code creates a simple command in JSON.
     * <p>
     * For notifications signaling a connection oriented protocol, the handling is delegated to
     * {@link #handlePermanentlyConnectedCommandReadinessNotification(TimeUntilDisconnectNotification)}.
     *
     * @param notification The notification containing the tenantId, deviceId and the Instant (that
     *                     defines until when this notification is valid). See {@link TimeUntilDisconnectNotification}.
     */
    private void handleCommandReadinessNotification(final TimeUntilDisconnectNotification notification) {
        if (notification.getTtd() <= 0) {
            handlePermanentlyConnectedCommandReadinessNotification(notification);
        } else {
            LOG.info("Device is ready to receive a command : [{}].", notification);
            sendCommand(notification);
        }
    }

    /**
     * Handle a ttd notification for permanently connected devices.
     * <p>
     * Instead of immediately handling the notification, it is first put to a map and a timer is started to handle it
     * later. Notifications for the same device that are received before the timer expired, will overwrite the original
     * notification. By this an <em>event flickering</em> (like it could occur when starting the app while several
     * notifications were persisted in the messaging network) is handled correctly.
     * <p>
     * If the contained <em>ttd</em> is set to -1, a command will be sent periodically every
     * {@link HonoExampleConstants#COMMAND_INTERVAL_FOR_DEVICES_CONNECTED_WITH_UNLIMITED_EXPIRY} seconds to the device
     * until a new notification was received with a <em>ttd</em> set to 0.
     *
     * @param notification The notification of a permanently connected device to handle.
     */
    private void handlePermanentlyConnectedCommandReadinessNotification(
            final TimeUntilDisconnectNotification notification) {

        final String keyForDevice = notification.getTenantAndDeviceId();

        final TimeUntilDisconnectNotification previousNotification = pendingTtdNotification.get(keyForDevice);
        if (previousNotification != null) {
            if (notification.getCreationTime().isAfter(previousNotification.getCreationTime())) {
                LOG.info("Set new ttd value [{}] of notification for [{}]",
                        notification.getTtd(), notification.getTenantAndDeviceId());
                pendingTtdNotification.put(keyForDevice, notification);
            } else {
                LOG.trace("Received notification for [{}] that was already superseded by newer [{}]",
                        notification, previousNotification);
            }
        } else {
            pendingTtdNotification.put(keyForDevice, notification);
            // there was no notification available already, so start a handler now
            vertx.setTimer(1000, timerId -> {
                LOG.debug("Handle device notification for [{}].", notification.getTenantAndDeviceId());
                // now take the notification from the pending map and handle it
                final TimeUntilDisconnectNotification notificationToHandle = pendingTtdNotification.remove(keyForDevice);
                if (notificationToHandle != null) {
                    if (notificationToHandle.getTtd() == -1) {
                        LOG.info("Device notified as being ready to receive a command until further notice : [{}].",
                                notificationToHandle);

                        // cancel a still existing timer for this device (if found)
                        cancelPeriodicCommandSender(notification);
                        // immediately send the first command
                        sendCommand(notificationToHandle);

                        // for devices that stay connected, start a periodic timer now that repeatedly sends a command
                        // to the device
                        vertx.setPeriodic(
                                (long) HonoExampleConstants.COMMAND_INTERVAL_FOR_DEVICES_CONNECTED_WITH_UNLIMITED_EXPIRY
                                        * 1000,
                                id -> {
                                    sendCommand(notificationToHandle);
                                    // register a canceler for this timer directly after it was created
                                    setPeriodicCommandSenderTimerCanceler(id, notification);
                                });
                    } else {
                        LOG.info("Device notified as not being ready to receive a command (anymore) : [{}].", notification);
                        cancelPeriodicCommandSender(notificationToHandle);
                        LOG.debug("Device will not receive further commands : [{}].", notification.getTenantAndDeviceId());
                    }
                }
            });
        }
    }

    /**
     * Sends a command to the device for which a {@link TimeUntilDisconnectNotification} was received.
     *
     * @param notification The notification that was received for the device.
     */
    private void sendCommand(final TimeUntilDisconnectNotification notification) {

        if (SEND_ONE_WAY_COMMANDS) {
            sendOneWayCommandToAdapter(notification.getTenantId(), notification.getDeviceId(), notification);
        } else {
            sendCommandToAdapter(notification.getTenantId(), notification.getDeviceId(), notification);
        }
    }

    /**
     * Calculate the timeout for a command that is tried to be sent to a device for which a
     * {@link TimeUntilDisconnectNotification} was received.
     *
     * @param notification The notification that was received for the device.
     * @return The timeout (milliseconds) to be set for the command.
     */
    private Duration calculateCommandTimeout(final TimeUntilDisconnectNotification notification) {

        if (notification.getTtd() == -1) {
            // let the command expire directly before the next periodic timer is started
            return Duration.ofMillis(HonoExampleConstants.COMMAND_INTERVAL_FOR_DEVICES_CONNECTED_WITH_UNLIMITED_EXPIRY * 1000L);
        } else {
            // let the command expire when the notification expires
            return Duration.ofMillis(notification.getMillisecondsUntilExpiry());
        }
    }

    private void setPeriodicCommandSenderTimerCanceler(final Long timerId,
            final TimeUntilDisconnectNotification ttdNotification) {
        this.periodicCommandSenderTimerCancelerMap.put(ttdNotification.getTenantAndDeviceId(), v -> {
            vertx.cancelTimer(timerId);
            periodicCommandSenderTimerCancelerMap.remove(ttdNotification.getTenantAndDeviceId());
        });
    }

    private void cancelPeriodicCommandSender(final TimeUntilDisconnectNotification notification) {
        if (isPeriodicCommandSenderActiveForDevice(notification)) {
            LOG.debug("Cancelling periodic sender for {}", notification.getTenantAndDeviceId());
            periodicCommandSenderTimerCancelerMap.get(notification.getTenantAndDeviceId()).handle(null);
        } else {
            LOG.debug("Wanted to cancel periodic sender for {}, but could not find one",
                    notification.getTenantAndDeviceId());
        }
    }

    private boolean isPeriodicCommandSenderActiveForDevice(final TimeUntilDisconnectNotification notification) {
        return periodicCommandSenderTimerCancelerMap.containsKey(notification.getTenantAndDeviceId());
    }

    /**
     * Send a command to the device for which a {@link TimeUntilDisconnectNotification} was received.
     * <p>
     * If the contained <em>ttd</em> is set to a value @gt; 0, the commandClient will be closed after a response
     * was received.
     * If the contained <em>ttd</em> is set to -1, the commandClient will remain open for further commands to be sent.
     * @param ttdNotification The ttd notification that was received for the device.
     */
    private void sendCommandToAdapter(
            final String tenantId,
            final String deviceId,
            final TimeUntilDisconnectNotification ttdNotification) {

        final Duration commandTimeout = calculateCommandTimeout(ttdNotification);
        final Buffer commandBuffer = buildCommandPayload();
        final String command = "setBrightness";
        if (LOG.isDebugEnabled()) {
            LOG.debug("Sending command [{}] to [{}].", command, ttdNotification.getTenantAndDeviceId());
        }

        client.sendCommand(tenantId, deviceId, command, commandBuffer, "application/json", null, commandTimeout, null)
            .onSuccess(result -> {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Successfully sent command payload: [{}].", commandBuffer.toString());
                    LOG.debug("And received response: [{}].", Optional.ofNullable(result.getPayload())
                            .orElseGet(Buffer::buffer).toString());
                }
            })
            .onFailure(t -> {
                if (t instanceof ServiceInvocationException) {
                    final int errorCode = ((ServiceInvocationException) t).getErrorCode();
                    LOG.debug("Command was replied with error code [{}].", errorCode);
                } else {
                    LOG.debug("Could not send command : {}.", t.getMessage());
                }
            });
    }

    /**
     * Send a one way command to the device for which a {@link TimeUntilDisconnectNotification} was received.
     * <p>
     * If the contained <em>ttd</em> is set to a value @gt; 0, the commandClient will be closed after a response
     * was received.
     * If the contained <em>ttd</em> is set to -1, the commandClient will remain open for further commands to be sent.
     * @param ttdNotification The ttd notification that was received for the device.
     */
    private void sendOneWayCommandToAdapter(final String tenantId, final String deviceId,
            final TimeUntilDisconnectNotification ttdNotification) {

        final Buffer commandBuffer = buildOneWayCommandPayload();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Sending one-way command [{}] to [{}].",
                    COMMAND_SEND_LIFECYCLE_INFO, ttdNotification.getTenantAndDeviceId());
        }

        client.sendOneWayCommand(tenantId, deviceId, COMMAND_SEND_LIFECYCLE_INFO, commandBuffer)
            .onSuccess(statusResult -> {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Successfully sent one-way command payload: [{}] and received status [{}].",
                            commandBuffer.toString(), statusResult);
                }
            })
            .onFailure(t -> {
                if (t instanceof ServiceInvocationException) {
                    final int errorCode = ((ServiceInvocationException) t).getErrorCode();
                    LOG.debug("One-way command was replied with error code [{}].", errorCode);
                } else {
                    LOG.debug("Could not send one-way command : {}.", t.getMessage());
                }
            });
    }

    private static Buffer buildCommandPayload() {
        final JsonObject jsonCmd = new JsonObject().put("brightness", RAND.nextInt(100));
        return Buffer.buffer(jsonCmd.encodePrettily());
    }

    private static Buffer buildOneWayCommandPayload() {
        final JsonObject jsonCmd = new JsonObject().put("info", "app restarted.");
        return Buffer.buffer(jsonCmd.encodePrettily());
    }

    /**
     * Handler method for a Message from Hono that was received as telemetry data.
     * <p>
     * The tenant, the device, the payload, the content-type, the creation-time and the application properties
     * will be logged.
     *
     * @param msg The message that was received.
     */
    private static void handleTelemetryMessage(final DownstreamMessage<? extends MessageContext> msg) {
        LOG.debug("received telemetry data [tenant: {}, device: {}, content-type: {}]: [{}].",
                msg.getTenantId(), msg.getDeviceId(), msg.getContentType(), msg.getPayload());
    }

    /**
     * Handler method for a Message from Hono that was received as event data.
     * <p>
     * The tenant, the device, the payload, the content-type, the creation-time and the application properties will
     * be logged.
     *
     * @param msg The message that was received.
     */
    private static void handleEventMessage(final DownstreamMessage<? extends MessageContext> msg) {
        LOG.debug("received event [tenant: {}, device: {}, content-type: {}]: [{}].",
                msg.getTenantId(), msg.getDeviceId(), msg.getContentType(), msg.getPayload());
    }
}
