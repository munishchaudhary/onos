/*
 * Copyright 2019-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onosproject.p4runtime.ctl.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.onosproject.net.pi.model.PiPipeconf;
import org.onosproject.p4runtime.api.P4RuntimePipelineConfigClient;
import org.onosproject.p4runtime.ctl.utils.PipeconfHelper;
import org.slf4j.Logger;
import p4.config.v1.P4InfoOuterClass;
import p4.tmp.P4Config;
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig;
import p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigRequest;
import p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigResponse;
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest;
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigResponse;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.onosproject.p4runtime.ctl.client.P4RuntimeClientImpl.LONG_TIMEOUT_SECONDS;
import static org.onosproject.p4runtime.ctl.client.P4RuntimeClientImpl.SHORT_TIMEOUT_SECONDS;
import static org.slf4j.LoggerFactory.getLogger;
import static p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigRequest.ResponseType.COOKIE_ONLY;
import static p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT;

/**
 * Implementation of P4RuntimePipelineConfigClient. Handles pipeline
 * config-related RPCs.
 */
final class PipelineConfigClientImpl implements P4RuntimePipelineConfigClient {

    private static final Logger log = getLogger(PipelineConfigClientImpl.class);

    private static final SetForwardingPipelineConfigResponse DEFAULT_SET_RESPONSE =
            SetForwardingPipelineConfigResponse.getDefaultInstance();

    private final P4RuntimeClientImpl client;

    PipelineConfigClientImpl(P4RuntimeClientImpl client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<Boolean> setPipelineConfig(
            PiPipeconf pipeconf, ByteBuffer deviceData) {

        if (!client.isSessionOpen()) {
            log.warn("Dropping set pipeline config request for {}, session is CLOSED",
                     client.deviceId());
            return completedFuture(false);
        }

        log.info("Setting pipeline config for {} to {}...",
                 client.deviceId(), pipeconf.id());

        checkNotNull(deviceData, "deviceData cannot be null");

        final ForwardingPipelineConfig pipelineConfigMsg =
                buildForwardingPipelineConfigMsg(pipeconf, deviceData);
        if (pipelineConfigMsg == null) {
            // Error logged in buildForwardingPipelineConfigMsg()
            return completedFuture(false);
        }

        final SetForwardingPipelineConfigRequest requestMsg =
                SetForwardingPipelineConfigRequest
                        .newBuilder()
                        .setDeviceId(client.p4DeviceId())
                        .setElectionId(client.lastUsedElectionId())
                        .setAction(VERIFY_AND_COMMIT)
                        .setConfig(pipelineConfigMsg)
                        .build();

        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        final StreamObserver<SetForwardingPipelineConfigResponse> responseObserver =
                new StreamObserver<SetForwardingPipelineConfigResponse>() {
                    @Override
                    public void onNext(SetForwardingPipelineConfigResponse value) {
                        if (!DEFAULT_SET_RESPONSE.equals(value)) {
                            log.warn("Received invalid SetForwardingPipelineConfigResponse " +
                                             " from {} [{}]",
                                     client.deviceId(),
                                     TextFormat.shortDebugString(value));
                            future.complete(false);
                        }
                        // All good, pipeline is set.
                        future.complete(true);
                    }

                    @Override
                    public void onError(Throwable t) {
                        client.handleRpcError(t, "SET-pipeline-config");
                        future.complete(false);
                    }

                    @Override
                    public void onCompleted() {
                        // Ignore, unary call.
                    }
                };

        client.execRpc(
                s -> s.setForwardingPipelineConfig(requestMsg, responseObserver),
                LONG_TIMEOUT_SECONDS);

        return future;
    }

    private ForwardingPipelineConfig buildForwardingPipelineConfigMsg(
            PiPipeconf pipeconf, ByteBuffer deviceData) {

        final P4InfoOuterClass.P4Info p4Info = PipeconfHelper.getP4Info(pipeconf);
        if (p4Info == null) {
            // Problem logged by PipeconfHelper.
            return null;
        }
        final ForwardingPipelineConfig.Cookie cookieMsg =
                ForwardingPipelineConfig.Cookie
                        .newBuilder()
                        .setCookie(pipeconf.fingerprint())
                        .build();
        // FIXME: This is specific to PI P4Runtime implementation and should be
        //  moved to driver.
        final P4Config.P4DeviceConfig p4DeviceConfigMsg = P4Config.P4DeviceConfig
                .newBuilder()
                .setExtras(P4Config.P4DeviceConfig.Extras.getDefaultInstance())
                .setReassign(true)
                .setDeviceData(ByteString.copyFrom(deviceData))
                .build();
        return ForwardingPipelineConfig
                .newBuilder()
                .setP4Info(p4Info)
                .setP4DeviceConfig(p4DeviceConfigMsg.toByteString())
                .setCookie(cookieMsg)
                .build();
    }


    @Override
    public CompletableFuture<Boolean> isPipelineConfigSet(
            PiPipeconf pipeconf, ByteBuffer expectedDeviceData) {
        return getPipelineCookieFromServer()
                .thenApply(cfgFromDevice -> comparePipelineConfig(
                        pipeconf, expectedDeviceData, cfgFromDevice));
    }

    @Override
    public CompletableFuture<Boolean> isAnyPipelineConfigSet() {
        return getPipelineCookieFromServer().thenApply(Objects::nonNull);
    }

    private boolean comparePipelineConfig(
            PiPipeconf pipeconf, ByteBuffer expectedDeviceData,
            ForwardingPipelineConfig cfgFromDevice) {
        if (cfgFromDevice == null) {
            return false;
        }

        final ForwardingPipelineConfig expectedCfg = buildForwardingPipelineConfigMsg(
                pipeconf, expectedDeviceData);
        if (expectedCfg == null) {
            return false;
        }

        if (cfgFromDevice.hasCookie()) {
            return cfgFromDevice.getCookie().getCookie() == pipeconf.fingerprint();
        }

        // No cookie.
        log.warn("{} returned GetForwardingPipelineConfigResponse " +
                         "with 'cookie' field unset. " +
                         "Will try by comparing 'device_data' and 'p4_info'...",
                 client.deviceId());

        if (cfgFromDevice.getP4DeviceConfig().isEmpty()
                && !expectedCfg.getP4DeviceConfig().isEmpty()) {
            // Don't bother with a warn or error since we don't really allow
            // updating the P4 blob to a different one without changing the
            // P4Info. I.e, comparing just the P4Info should be enough for us.
            log.debug("{} returned GetForwardingPipelineConfigResponse " +
                              "with empty 'p4_device_config' field, " +
                              "equality will be based only on P4Info",
                      client.deviceId());
            return cfgFromDevice.getP4Info().equals(expectedCfg.getP4Info());
        }

        return cfgFromDevice.getP4DeviceConfig()
                .equals(expectedCfg.getP4DeviceConfig())
                && cfgFromDevice.getP4Info()
                .equals(expectedCfg.getP4Info());
    }

    private CompletableFuture<ForwardingPipelineConfig> getPipelineCookieFromServer() {
        final GetForwardingPipelineConfigRequest request =
                GetForwardingPipelineConfigRequest
                        .newBuilder()
                        .setDeviceId(client.p4DeviceId())
                        .setResponseType(COOKIE_ONLY)
                        .build();
        final CompletableFuture<ForwardingPipelineConfig> future = new CompletableFuture<>();
        final StreamObserver<GetForwardingPipelineConfigResponse> responseObserver =
                new StreamObserver<GetForwardingPipelineConfigResponse>() {
                    @Override
                    public void onNext(GetForwardingPipelineConfigResponse value) {
                        if (value.hasConfig()) {
                            future.complete(value.getConfig());
                            if (!value.getConfig().getP4DeviceConfig().isEmpty()) {
                                log.warn("{} returned GetForwardingPipelineConfigResponse " +
                                                 "with p4_device_config field set " +
                                                 "({} bytes), but we requested COOKIE_ONLY",
                                         client.deviceId(),
                                         value.getConfig().getP4DeviceConfig().size());
                            }
                            if (value.getConfig().hasP4Info()) {
                                log.warn("{} returned GetForwardingPipelineConfigResponse " +
                                                 "with p4_info field set " +
                                                 "({} bytes), but we requested COOKIE_ONLY",
                                         client.deviceId(),
                                         value.getConfig().getP4Info().getSerializedSize());
                            }
                        } else {
                            future.complete(null);
                            log.warn("{} returned {} with 'config' field unset",
                                     client.deviceId(), value.getClass().getSimpleName());
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        future.complete(null);
                        if (Status.fromThrowable(t).getCode() ==
                                Status.Code.FAILED_PRECONDITION) {
                            // FAILED_PRECONDITION means that a pipeline
                            // config was not set in the first place, don't
                            // bother logging.
                            return;
                        }
                        client.handleRpcError(t, "GET-pipeline-config");
                    }

                    @Override
                    public void onCompleted() {
                        // Ignore, unary call.
                    }
                };
        // Use long timeout as the device might return the full P4 blob
        // (e.g. server does not support cookie), over a slow network.
        client.execRpc(
                s -> s.getForwardingPipelineConfig(request, responseObserver),
                SHORT_TIMEOUT_SECONDS);
        return future;
    }
}
