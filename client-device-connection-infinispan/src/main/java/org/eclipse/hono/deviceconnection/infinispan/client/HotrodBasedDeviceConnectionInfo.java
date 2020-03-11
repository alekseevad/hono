/**
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
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


package org.eclipse.hono.deviceconnection.infinispan.client;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.client.ServerErrorException;
import org.eclipse.hono.service.HealthCheckProvider;
import org.eclipse.hono.util.DeviceConnectionConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.HealthCheckHandler;
import io.vertx.ext.healthchecks.Status;


/**
 * A client for accessing device connection information in a data grid using the
 * Hotrod protocol.
 *
 */
public final class HotrodBasedDeviceConnectionInfo implements DeviceConnectionInfo, HealthCheckProvider {

    /**
     * For <em>viaGateways</em> parameter value lower or equal to this value, the {@link #getCommandHandlingAdapterInstances(String, String, Set, SpanContext)}
     * method will use an optimized approach, potentially saving additional cache requests.
     */
    static final int VIA_GATEWAYS_OPTIMIZATION_THRESHOLD = 3;

    private static final Logger LOG = LoggerFactory.getLogger(HotrodBasedDeviceConnectionInfo.class);

    /**
     * Prefix for cache entries having gateway id values, concerning <em>lastKnownGatewayForDevice</em>
     * operations.
     */
    private static final String KEY_PREFIX_GATEWAY_ENTRIES_VALUE = "gw";
    /**
     * Prefix for cache entries having protocol adapter instance id values, concerning
     * <em>commandHandlingAdapterInstance</em> operations.
     */
    private static final String KEY_PREFIX_ADAPTER_INSTANCE_VALUES = "ai";
    private static final String KEY_SEPARATOR = "@@";

    final RemoteCache<String, String> cache;
    final Tracer tracer;

    /**
     * Creates a client for accessing device connection information.
     * 
     * @param cache The remote cache that contains the data.
     * @param tracer The tracer instance.
     * @throws NullPointerException if cache or tracer is {@code null}.
     */
    public HotrodBasedDeviceConnectionInfo(final RemoteCache<String, String> cache, final Tracer tracer) {
        this.cache = Objects.requireNonNull(cache);
        this.tracer = Objects.requireNonNull(tracer);
    }

    /**
     * {@inheritDoc}
     * 
     * If this method is invoked from a vert.x Context, then the returned future will be completed on that context.
     */
    @Override
    public Future<Void> setLastKnownGatewayForDevice(final String tenantId, final String deviceId,
            final String gatewayId, final SpanContext context) {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(gatewayId);

        return cache.put(getGatewayEntryKey(tenantId, deviceId), gatewayId)
            .map(replacedValue -> {
                LOG.debug("set last known gateway [tenant: {}, device-id: {}, gateway: {}]", tenantId, deviceId,
                        gatewayId);
                return (Void) null;
            })
            .recover(t -> {
                LOG.debug("failed to set last known gateway [tenant: {}, device-id: {}, gateway: {}]",
                        tenantId, deviceId, gatewayId, t);
                return Future.failedFuture(new ServerErrorException(HttpURLConnection.HTTP_INTERNAL_ERROR, t));
            });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<JsonObject> getLastKnownGatewayForDevice(final String tenantId, final String deviceId,
            final SpanContext context) {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);

        return cache.get(getGatewayEntryKey(tenantId, deviceId))
                .compose(gatewayId -> {
                    if (gatewayId == null) {
                        LOG.debug("could not find last known gateway for device [tenant: {}, device-id: {}]", tenantId,
                                deviceId);
                        return Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_NOT_FOUND));
                    } else {
                        LOG.debug("found last known gateway for device [tenant: {}, device-id: {}]: {}", tenantId,
                                deviceId, gatewayId);
                        return Future.succeededFuture(getLastKnownGatewayResultJson(gatewayId));
                    }
                })
                .recover(t -> {
                    LOG.debug("failed to find last known gateway for device [tenant: {}, device-id: {}]",
                            tenantId, deviceId, t);
                    return Future.failedFuture(new ServerErrorException(HttpURLConnection.HTTP_INTERNAL_ERROR, t));
                });
    }

    @Override
    public Future<Void> setCommandHandlingAdapterInstance(final String tenantId, final String deviceId,
            final String adapterInstanceId, final SpanContext context) {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(adapterInstanceId);

        return cache.put(getAdapterInstanceEntryKey(tenantId, deviceId), adapterInstanceId)
                .map(replacedValue -> {
                    LOG.debug("set command handling adapter instance [tenant: {}, device-id: {}, adapter-instance: {}]",
                            tenantId, deviceId, adapterInstanceId);
                    return (Void) null;
                })
                .recover(t -> {
                    LOG.debug("failed to set command handling adapter instance [tenant: {}, device-id: {}, adapter-instance: {}]",
                            tenantId, deviceId, adapterInstanceId, t);
                    return Future.failedFuture(new ServerErrorException(HttpURLConnection.HTTP_INTERNAL_ERROR, t));
                });
    }

    @Override
    public Future<Void> removeCommandHandlingAdapterInstance(final String tenantId, final String deviceId,
            final String adapterInstanceId, final SpanContext context) {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(adapterInstanceId);

        final String key = getAdapterInstanceEntryKey(tenantId, deviceId);
        return cache.getWithVersion(key)
               .recover(t -> {
                    LOG.debug("failed to get cache entry when trying to remove command handling adapter instance [tenant: {}, device-id: {}, adapter-instance: {}]",
                            tenantId, deviceId, adapterInstanceId, t);
                    return Future.failedFuture(new ServerErrorException(HttpURLConnection.HTTP_INTERNAL_ERROR, t));
                }).compose(versioned -> {
                    if (versioned == null) {
                        LOG.debug("command handling adapter instance was not removed, entry not found [tenant: {}, device-id: {}, adapter-instance: {}]",
                                tenantId, deviceId, adapterInstanceId);
                        return Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_NOT_FOUND));
                    }
                    if (!adapterInstanceId.equals(versioned.getValue())) {
                        LOG.debug("command handling adapter instance was not removed, value '{}' didn't match [tenant: {}, device-id: {}, adapter-instance: {}]",
                                versioned.getValue(), tenantId, deviceId, adapterInstanceId);
                        return Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_PRECON_FAILED));
                    }
                    return cache.removeWithVersion(key, versioned.getVersion())
                            .recover(t -> {
                                LOG.debug("failed to remove command handling adapter instance [tenant: {}, device-id: {}, adapter-instance: {}]", tenantId, deviceId,
                                        adapterInstanceId, t);
                                return Future.failedFuture(new ServerErrorException(HttpURLConnection.HTTP_INTERNAL_ERROR, t));
                            }).compose(removed -> {
                                if (removed) {
                                    LOG.debug("removed command handling adapter instance [tenant: {}, device-id: {}, adapter-instance: {}]",
                                            tenantId, deviceId, adapterInstanceId);
                                    return Future.succeededFuture();
                                } else {
                                    LOG.debug("command handling adapter instance was not removed, has been updated in the meantime [tenant: {}, device-id: {}, adapter-instance: {}]",
                                            tenantId, deviceId, adapterInstanceId);
                                    return Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_PRECON_FAILED));
                                }
                            });
                });
    }

    @Override
    public Future<JsonObject> getCommandHandlingAdapterInstances(final String tenantId, final String deviceId,
            final Set<String> viaGateways, final SpanContext context) {

        if (viaGateways.isEmpty()) {
             // get the command handling adapter instance for the device (no gateway involved)
            return cache.get(getAdapterInstanceEntryKey(tenantId, deviceId))
                    .recover(t -> failedToGetEntriesWhenGettingInstances(tenantId, deviceId, t))
                    .compose(adapterInstanceId -> {
                        if (adapterInstanceId == null) {
                            LOG.debug("no command handling adapter instances found [tenant: {}, device-id: {}]",
                                    tenantId, deviceId);
                            return Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_NOT_FOUND));
                        } else {
                            LOG.debug("found command handling adapter instance '{}' [tenant: {}, device-id: {}]",
                                    adapterInstanceId, tenantId, deviceId);
                            return Future.succeededFuture(getAdapterInstancesResultJson(deviceId, adapterInstanceId));
                        }
                    });
        } else if (viaGateways.size() <= VIA_GATEWAYS_OPTIMIZATION_THRESHOLD) {
            return getInstancesQueryingAllGatewaysFirst(tenantId, deviceId, viaGateways);
        } else {
            // number of viaGateways is more than threshold value - reduce cache accesses by not checking *all* viaGateways,
            // instead trying the last known gateway first
            return getInstancesGettingLastKnownGatewayFirst(tenantId, deviceId, viaGateways);
        }
    }

    private Future<JsonObject> getInstancesQueryingAllGatewaysFirst(final String tenantId, final String deviceId, final Set<String> viaGateways) {
        // get the command handling adapter instances for the device and *all* via-gateways in one call first
        // (this saves the extra lastKnownGateway check if only one adapter instance is returned)
        return cache.getAll(getAdapterInstanceEntryKeys(tenantId, deviceId, viaGateways))
                .recover(t -> failedToGetEntriesWhenGettingInstances(tenantId, deviceId, t))
                .compose(getAllMap -> {
                    final Map<String, String> deviceToInstanceMap = convertAdapterInstanceEntryKeys(getAllMap);
                    final Future<JsonObject> resultFuture;
                    if (deviceToInstanceMap.isEmpty()) {
                        LOG.debug("no command handling adapter instances found [tenant: {}, device-id: {}]",
                                tenantId, deviceId);
                        resultFuture = Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_NOT_FOUND));
                    } else if (deviceToInstanceMap.containsKey(deviceId)) {
                        // there is a adapter instance set for the device itself - that gets precedence
                        resultFuture = getAdapterInstanceFoundForDeviceItselfResult(tenantId, deviceId, deviceToInstanceMap.get(deviceId));
                    } else if (deviceToInstanceMap.size() > 1) {
                        // multiple gateways found - check last known gateway
                        resultFuture = cache.get(getGatewayEntryKey(tenantId, deviceId))
                                .recover(t -> failedToGetEntriesWhenGettingInstances(tenantId, deviceId, t))
                                .compose(lastKnownGateway -> {
                                    if (lastKnownGateway == null) {
                                        // no last known gateway found - just return all found mapping entries
                                        LOG.debug("returning {} command handling adapter instances for device gateways (no last known gateway found) [tenant: {}, device-id: {}]",
                                                deviceToInstanceMap.size(), tenantId, deviceId);
                                        return Future.succeededFuture(getAdapterInstancesResultJson(deviceToInstanceMap));
                                    } else if (!viaGateways.contains(lastKnownGateway)) {
                                        // found gateway is not valid anymore - just return all found mapping entries
                                        LOG.debug("returning {} command handling adapter instances for device gateways (last known gateway not valid anymore) [tenant: {}, device-id: {}, lastKnownGateway: {}]",
                                                deviceToInstanceMap.size(), tenantId, deviceId, lastKnownGateway);
                                        return Future.succeededFuture(getAdapterInstancesResultJson(deviceToInstanceMap));
                                    } else if (!deviceToInstanceMap.containsKey(lastKnownGateway)) {
                                        // found gateway has no command handling instance assigned - just return all found mapping entries
                                        LOG.debug("returning {} command handling adapter instances for device gateways (last known gateway not in that list) [tenant: {}, device-id: {}, lastKnownGateway: {}]",
                                                deviceToInstanceMap.size(), tenantId, deviceId, lastKnownGateway);
                                        return Future.succeededFuture(getAdapterInstancesResultJson(deviceToInstanceMap));
                                    } else {
                                        LOG.debug("returning command handling adapter instance {} for last known gateway [tenant: {}, device-id: {}, lastKnownGateway: {}]",
                                                deviceToInstanceMap.get(lastKnownGateway), tenantId, deviceId, lastKnownGateway);
                                        return Future.succeededFuture(getAdapterInstancesResultJson(lastKnownGateway,
                                                deviceToInstanceMap.get(lastKnownGateway)));
                                    }
                                });
                    } else {
                        // one command handling instance found
                        final Map.Entry<String, String> foundEntry = deviceToInstanceMap.entrySet().iterator().next();
                        LOG.debug("returning command handling adapter instance {} associated with gateway {} [tenant: {}, device-id: {}]",
                                foundEntry.getValue(), foundEntry.getKey(), tenantId, deviceId);
                        resultFuture = Future.succeededFuture(getAdapterInstancesResultJson(foundEntry.getKey(),
                                foundEntry.getValue()));
                    }
                    return resultFuture;
                });
    }

    private Future<JsonObject> getInstancesGettingLastKnownGatewayFirst(final String tenantId, final String deviceId, final Set<String> viaGateways) {
        return cache.get(getGatewayEntryKey(tenantId, deviceId))
                .recover(t -> failedToGetEntriesWhenGettingInstances(tenantId, deviceId, t))
                .compose(lastKnownGateway -> {
                    if (lastKnownGateway == null) {
                        LOG.trace("no last known gateway found [tenant: {}, device-id: {}]", tenantId, deviceId);
                    } else if (!viaGateways.contains(lastKnownGateway)) {
                        LOG.trace("found gateway is not valid for the device anymore [tenant: {}, device-id: {}]", tenantId, deviceId);
                    }
                    if (lastKnownGateway != null && viaGateways.contains(lastKnownGateway)) {
                        // fetch command handling instances for lastKnownGateway and device
                        return cache.getAll(getAdapterInstanceEntryKeys(tenantId, deviceId, lastKnownGateway))
                                .recover(t -> failedToGetEntriesWhenGettingInstances(tenantId, deviceId, t))
                                .compose(getAllMap -> {
                                    final Map<String, String> deviceToInstanceMap = convertAdapterInstanceEntryKeys(getAllMap);
                                    if (deviceToInstanceMap.isEmpty()) {
                                        // no adapter instances found for last-known-gateway and device - check all via gateways
                                        return getAdapterInstancesWithoutLastKnownGatewayCheck(tenantId, deviceId, viaGateways);
                                    } else if (deviceToInstanceMap.containsKey(deviceId)) {
                                        // there is a adapter instance set for the device itself - that gets precedence
                                        return getAdapterInstanceFoundForDeviceItselfResult(tenantId, deviceId, deviceToInstanceMap.get(deviceId));
                                    } else {
                                        // adapter instance found for last known gateway
                                        LOG.debug("returning command handling adapter instance {} for last known gateway [tenant: {}, device-id: {}, lastKnownGateway: {}]",
                                                deviceToInstanceMap.get(lastKnownGateway), tenantId, deviceId, lastKnownGateway);
                                        return Future.succeededFuture(getAdapterInstancesResultJson(deviceToInstanceMap));
                                    }
                                });
                    } else {
                        // last-known-gateway not found or invalid - look for all adapter instances for device and viaGateways
                        return getAdapterInstancesWithoutLastKnownGatewayCheck(tenantId, deviceId, viaGateways);
                    }
                });
    }

    private Future<JsonObject> getAdapterInstancesWithoutLastKnownGatewayCheck(final String tenantId,
            final String deviceId, final Set<String> viaGateways) {
        return cache.getAll(getAdapterInstanceEntryKeys(tenantId, deviceId, viaGateways))
                .recover(t -> failedToGetEntriesWhenGettingInstances(tenantId, deviceId, t))
                .compose(getAllMap -> {
                    final Map<String, String> deviceToInstanceMap = convertAdapterInstanceEntryKeys(getAllMap);
                    final Future<JsonObject> resultFuture;
                    if (deviceToInstanceMap.isEmpty()) {
                        LOG.debug("no command handling adapter instances found [tenant: {}, device-id: {}]",
                                tenantId, deviceId);
                        resultFuture = Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_NOT_FOUND));
                    } else if (deviceToInstanceMap.containsKey(deviceId)) {
                        // there is a command handling instance set for the device itself - that gets precedence
                        resultFuture = getAdapterInstanceFoundForDeviceItselfResult(tenantId, deviceId, deviceToInstanceMap.get(deviceId));
                    } else {
                        LOG.debug("returning {} command handling adapter instance(s) (no last known gateway found) [tenant: {}, device-id: {}]",
                                deviceToInstanceMap.size(), tenantId, deviceId);
                        resultFuture = Future.succeededFuture(getAdapterInstancesResultJson(deviceToInstanceMap));
                    }
                    return resultFuture;
                });
    }

    private Future<JsonObject> getAdapterInstanceFoundForDeviceItselfResult(final String tenantId,
            final String deviceId, final String adapterInstanceId) {
        LOG.debug("returning command handling adapter instance {} for device itself [tenant: {}, device-id: {}]",
                adapterInstanceId, tenantId, deviceId);
        return Future.succeededFuture(getAdapterInstancesResultJson(deviceId, adapterInstanceId));
    }

    private <T> Future<T> failedToGetEntriesWhenGettingInstances(final String tenantId, final String deviceId, final Throwable t) {
        LOG.debug("failed to get cache entries when trying to get command handling adapter instances [tenant: {}, device-id: {}]",
                tenantId, deviceId, t);
        return Future.failedFuture(new ServerErrorException(HttpURLConnection.HTTP_INTERNAL_ERROR, t));
    }

    private static String getGatewayEntryKey(final String tenantId, final String deviceId) {
        return KEY_PREFIX_GATEWAY_ENTRIES_VALUE + KEY_SEPARATOR + tenantId + KEY_SEPARATOR + deviceId;
    }

    private static String getAdapterInstanceEntryKey(final String tenantId, final String deviceId) {
        return KEY_PREFIX_ADAPTER_INSTANCE_VALUES + KEY_SEPARATOR + tenantId + KEY_SEPARATOR + deviceId;
    }

    private static Set<String> getAdapterInstanceEntryKeys(final String tenantId, final String deviceIdA,
            final String deviceIdB) {
        final HashSet<String> keys = new HashSet<>(2);
        keys.add(getAdapterInstanceEntryKey(tenantId, deviceIdA));
        keys.add(getAdapterInstanceEntryKey(tenantId, deviceIdB));
        return keys;
    }

    /**
     * Puts the entries from the given map, having {@link #getAdapterInstanceEntryKey(String, String)} keys, into
     * a new map with just the extracted device ids as keys.
     *
     * @param map Map to get the entries from.
     * @return New map with keys containing just the device id.
     */
    private static Map<String, String> convertAdapterInstanceEntryKeys(final Map<String, String> map) {
        return map.entrySet().stream()
                .collect(Collectors.toMap(entry -> getDeviceIdFromAdapterInstanceEntryKey(entry.getKey()), Map.Entry::getValue));
    }

    private static String getDeviceIdFromAdapterInstanceEntryKey(final String key) {
        final int pos = key.lastIndexOf(KEY_SEPARATOR);
        return key.substring(pos + KEY_SEPARATOR.length());
    }

    private static Set<String> getAdapterInstanceEntryKeys(final String tenantId, final String deviceIdA,
            final Set<String> additionalDeviceIds) {
        final HashSet<String> keys = new HashSet<>(additionalDeviceIds.size() + 1);
        keys.add(getAdapterInstanceEntryKey(tenantId, deviceIdA));
        additionalDeviceIds.forEach(id -> keys.add(getAdapterInstanceEntryKey(tenantId, id)));
        return keys;
    }

    private static JsonObject getLastKnownGatewayResultJson(final String gatewayId) {
        return new JsonObject().put(DeviceConnectionConstants.FIELD_GATEWAY_ID, gatewayId);
    }

    private static JsonObject getAdapterInstancesResultJson(final Map<String, String> deviceToAdapterInstanceMap) {
        final JsonObject jsonObject = new JsonObject();
        final JsonArray adapterInstancesArray = new JsonArray(new ArrayList<>(deviceToAdapterInstanceMap.size()));
        for (final Map.Entry<String, String> resultEntry : deviceToAdapterInstanceMap.entrySet()) {
            final JsonObject entryJson = new JsonObject();
            entryJson.put(DeviceConnectionConstants.FIELD_PAYLOAD_DEVICE_ID, resultEntry.getKey());
            entryJson.put(DeviceConnectionConstants.FIELD_ADAPTER_INSTANCE_ID, resultEntry.getValue());
            adapterInstancesArray.add(entryJson);
        }
        jsonObject.put(DeviceConnectionConstants.FIELD_ADAPTER_INSTANCES, adapterInstancesArray);
        return jsonObject;
    }

    private static JsonObject getAdapterInstancesResultJson(final String deviceId, final String adapterInstanceId) {
        return getAdapterInstancesResultJson(Map.of(deviceId, adapterInstanceId));
    }

    /**
     * {@inheritDoc}
     * <p>
     * Registers a check for an established connection to the remote cache.
     * The check times out (and fails) after 1000ms.
     */
    @Override
    public void registerReadinessChecks(final HealthCheckHandler readinessHandler) {
        readinessHandler.register("remote-cache-connection", 1000, this::checkForCacheAvailability);
    }

    private void checkForCacheAvailability(final Promise<Status> status) {

        cache.checkForCacheAvailability()
            .map(stats -> Status.OK(stats))
            .otherwise(t -> Status.KO())
            .setHandler(status);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerLivenessChecks(final HealthCheckHandler livenessHandler) {
    }
}
