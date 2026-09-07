/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.cloud.extend.cse.service;

import org.apache.servicecomb.service.center.client.ServiceCenterClient;
import org.apache.servicecomb.service.center.client.model.*;
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.cloud.CloudDiscoveryHandler;
import org.noear.solon.cloud.extend.cse.api.CseSdkClient;
import org.noear.solon.cloud.extend.cse.impl.CseConfig;
import org.noear.solon.cloud.model.Discovery;
import org.noear.solon.cloud.model.Instance;
import org.noear.solon.cloud.service.CloudDiscoveryObserverEntity;
import org.noear.solon.cloud.service.CloudDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * CSE ServiceComb 云端注册与发现服务实现（基于 ServiceComb Java Chassis SDK）
 *
 * 核心机制:
 * - 通过 ServiceCenterClient SDK 向 CSE ServiceComb 引擎注册/注销微服务实例
 * - 使用定时任务上报心跳（heartbeat）保持实例存活
 * - 使用定时轮询方式订阅服务变化
 *
 * @author noear
 * @since 1.2
 */
public class CloudDiscoveryServiceCseImp implements CloudDiscoveryService {
    static final Logger log = LoggerFactory.getLogger(CloudDiscoveryServiceCseImp.class);

    private final CseConfig cseConfig;
    private final ServiceCenterClient scClient;
    private final String appId;

    // 注册成功后的服务信息
    private String registeredServiceId;
    private String registeredInstanceId;
    private Instance registeredInstance;

    // 心跳调度器
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?> heartbeatFuture;

    // 观察者调度器
    private final ScheduledExecutorService observerExecutor;

    // 已订阅的观察者集合
    private final Map<String, CloudDiscoveryObserverEntity> observerMap = new ConcurrentHashMap<>();

    public CloudDiscoveryServiceCseImp(CseSdkClient sdkClient) {
        this.cseConfig = sdkClient.getConfig();
        this.scClient = sdkClient.getServiceCenterClient();
        this.appId = cseConfig.getAppId();

        // 观察者轮询线程池
        this.observerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cse-discovery-observer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 注册服务实例
     */
    @Override
    public void register(String group, Instance instance) {
        registerState(group, instance, true);
    }

    /**
     * 注册服务实例（带健康状态）
     */
    @Override
    public void registerState(String group, Instance instance, boolean health) {
        if (Utils.isEmpty(group)) {
            group = Solon.cfg().appGroup();
        }
        if (Utils.isEmpty(group)) {
            group = appId;
        }

        this.registeredInstance = instance;

        try {
            // 1. 创建微服务
            Microservice microservice = new Microservice();
            microservice.setAppId(group);
            microservice.setServiceName(instance.service());
            microservice.setVersion(instance.metaGet("version") != null ? instance.metaGet("version") : "1.0.0.0");
            microservice.setEnvironment("production");
            microservice.setStatus(MicroserviceStatus.UP);
            microservice.setRegisterBy("SDK"); // CSE 仅允许 SDK/SIDECAR/PLATFORM
            Framework framework = new Framework();
            framework.setName("solon");
            framework.setVersion(Solon.version());
            microservice.setFramework(framework);

            // 先查询是否已注册
            RegisteredMicroserviceResponse existResp = scClient.queryServiceId(microservice);
            if (existResp != null && existResp.getServiceId() != null) {
                registeredServiceId = existResp.getServiceId();
                log.info("CSE microservice already registered: serviceId={}", registeredServiceId);
            } else {
                RegisteredMicroserviceResponse regResp = scClient.registerMicroservice(microservice);
                if (regResp != null && regResp.getServiceId() != null) {
                    registeredServiceId = regResp.getServiceId();
                    log.info("CSE microservice registered: serviceId={}", registeredServiceId);
                } else {
                    log.warn("CSE register microservice failed");
                    return;
                }
            }

            // 2. 构建实例
            MicroserviceInstance msInstance = new MicroserviceInstance();
            msInstance.setServiceId(registeredServiceId);
            msInstance.setHostName(instance.host());
            msInstance.setStatus(health ? MicroserviceInstanceStatus.UP : MicroserviceInstanceStatus.DOWN);

            // endpoints 格式: rest:host:port
            String protocol = instance.protocol();
            if (Utils.isEmpty(protocol)) {
                protocol = "rest";
            }
            List<String> endpoints = new ArrayList<>();
            endpoints.add(protocol + ":" + instance.host() + ":" + instance.port());
            msInstance.setEndpoints(endpoints);

            // 健康检查
            HealthCheck healthCheck = new HealthCheck();
            healthCheck.setMode(HealthCheckMode.push);
            healthCheck.setInterval(cseConfig.getHealthCheckInterval());
            healthCheck.setTimes(cseConfig.getHealthCheckTimes());
            msInstance.setHealthCheck(healthCheck);

            // 元数据
            Map<String, String> properties = new HashMap<>();
            properties.put("version", microservice.getVersion());
            if (instance.meta() != null) {
                properties.putAll(instance.meta());
            }
            msInstance.setProperties(properties);

            // 3. 注册实例
            RegisteredMicroserviceInstanceResponse instResp = scClient.registerMicroserviceInstance(msInstance);
            if (instResp != null && instResp.getInstanceId() != null) {
                registeredInstanceId = instResp.getInstanceId();
                log.info("CSE instance registered: {}://{}:{}, serviceId={}, instanceId={}",
                        protocol, instance.host(), instance.port(), registeredServiceId, registeredInstanceId);

                // 4. 启动心跳
                startHeartbeat();
            } else {
                log.warn("CSE register instance failed (instanceId is null)");
            }
        } catch (Exception e) {
            log.warn("CSE register instance exception: {}", e.getMessage(), e);
        }
    }

    /**
     * 注销服务实例
     */
    @Override
    public void deregister(String group, Instance instance) {
        stopHeartbeat();

        if (registeredServiceId != null && registeredInstanceId != null) {
            try {
                scClient.deleteMicroserviceInstance(registeredServiceId, registeredInstanceId);
                log.info("CSE instance deregistered: serviceId={}, instanceId={}",
                        registeredServiceId, registeredInstanceId);
            } catch (Exception e) {
                log.warn("CSE deregister exception: {}", e.getMessage());
            }
            registeredInstanceId = null;
            registeredServiceId = null;
        }
    }

    /**
     * 查询服务实例列表
     */
    @Override
    public Discovery find(String group, String service) {
        if (Utils.isEmpty(group)) {
            group = Solon.cfg().appGroup();
        }
        if (Utils.isEmpty(group)) {
            group = appId;
        }

        Discovery discovery = new Discovery(group, service);

        try {
            String consumerId = registeredServiceId;
            if (consumerId == null) {
                // 如果当前服务未注册，尝试查询
                Microservice query = new Microservice();
                query.setAppId(group);
                query.setServiceName(Solon.cfg().appName());
                query.setVersion("1.0.0.0");
                RegisteredMicroserviceResponse resp = scClient.queryServiceId(query);
                if (resp != null) {
                    consumerId = resp.getServiceId();
                }
            }

            // 查询实例: findMicroserviceInstance(consumerId, appId, serviceName, version, env)
            FindMicroserviceInstancesResponse findResp = scClient.findMicroserviceInstance(
                    consumerId, group, service, "0.0.0.0+", "production"
            );

            if (findResp != null && findResp.isModified()) {
                MicroserviceInstancesResponse instResp = findResp.getMicroserviceInstancesResponse();
                if (instResp != null && instResp.getInstances() != null) {
                    for (MicroserviceInstance msInst : instResp.getInstances()) {
                        if (msInst.getStatus() != MicroserviceInstanceStatus.UP) {
                            continue;
                        }

                        // 解析 endpoints
                        String host = msInst.getHostName();
                        int port = 0;
                        if (msInst.getEndpoints() != null && !msInst.getEndpoints().isEmpty()) {
                            String ep = msInst.getEndpoints().get(0);
                            // 格式: rest:192.168.1.1:8080
                            int idx1 = ep.indexOf(':');
                            if (idx1 > 0) {
                                String rest = ep.substring(idx1 + 1);
                                int idx2 = rest.lastIndexOf(':');
                                if (idx2 > 0) {
                                    host = rest.substring(0, idx2);
                                    try {
                                        port = Integer.parseInt(rest.substring(idx2 + 1));
                                    } catch (NumberFormatException ignore) {
                                    }
                                }
                            }
                        }

                        Instance inst = new Instance(service, host, port)
                                .metaPut("cse.instanceId", msInst.getInstanceId())
                                .metaPut("cse.serviceId", msInst.getServiceId());

                        if (msInst.getProperties() != null) {
                            for (Map.Entry<String, String> entry : msInst.getProperties().entrySet()) {
                                inst.metaPut(entry.getKey(), entry.getValue());
                            }
                        }
                        inst.weight(1.0D);

                        discovery.instanceAdd(inst);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("CSE find exception: group={}, service={}, error={}", group, service, e.getMessage());
        }

        return discovery;
    }

    /**
     * 查询所有微服务名
     */
    @Override
    public Collection<String> findServices(String group) {
        if (Utils.isEmpty(group)) {
            group = Solon.cfg().appGroup();
        }
        if (Utils.isEmpty(group)) {
            group = appId;
        }

        List<String> result = new ArrayList<>();
        try {
            MicroservicesResponse resp = scClient.getMicroserviceList();
            if (resp != null && resp.getServices() != null) {
                for (Microservice ms : resp.getServices()) {
                    if (group.equals(ms.getAppId())) {
                        result.add(ms.getServiceName());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("CSE findServices exception: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 关注服务实例列表（轮询模式）
     */
    @Override
    public void attention(String group, String service, CloudDiscoveryHandler observer) {
        if (Utils.isEmpty(group)) {
            group = Solon.cfg().appGroup();
        }
        if (Utils.isEmpty(group)) {
            group = appId;
        }

        final String finalGroup = group;
        final String finalService = service;

        CloudDiscoveryObserverEntity entity = new CloudDiscoveryObserverEntity(finalGroup, finalService, observer);
        String key = finalGroup + "@" + finalService;
        observerMap.put(key, entity);

        String refreshInterval = cseConfig.getDiscoveryRefreshInterval("10s");
        long period = parseIntervalMillis(refreshInterval, 10000);

        observerExecutor.scheduleAtFixedRate(() -> {
            try {
                Discovery discovery = find(finalGroup, finalService);
                entity.handle(discovery);
            } catch (Exception e) {
                log.debug("CSE attention poll exception: {}", e.getMessage());
            }
        }, period, period, TimeUnit.MILLISECONDS);

        log.info("CSE attention started: group={}, service={}", finalGroup, finalService);
    }

    // ========== 内部方法 ==========

    private void startHeartbeat() {
        if (heartbeatFuture != null && !heartbeatFuture.isDone()) {
            return;
        }

        if (heartbeatExecutor == null) {
            heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cse-heartbeat");
                t.setDaemon(true);
                return t;
            });
        }

        int interval = cseConfig.getHealthCheckInterval();
        int delay = cseConfig.getHealthCheckDelay();

        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (registeredServiceId != null && registeredInstanceId != null) {
                try {
                    boolean ok = scClient.sendHeartBeat(registeredServiceId, registeredInstanceId);
                    if (!ok) {
                        log.warn("CSE heartbeat failed, will retry");
                    }
                } catch (Exception e) {
                    log.warn("CSE heartbeat exception: {}", e.getMessage());
                }
            }
        }, delay, interval, TimeUnit.SECONDS);

        log.info("CSE heartbeat started: interval={}s, delay={}s", interval, delay);
    }

    private void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    private long parseIntervalMillis(String intervalStr, long def) {
        if (intervalStr == null || intervalStr.isEmpty()) {
            return def;
        }
        try {
            String trimmed = intervalStr.trim().toLowerCase();
            if (trimmed.endsWith("ms")) {
                return Long.parseLong(trimmed.substring(0, trimmed.length() - 2));
            } else if (trimmed.endsWith("s")) {
                return Long.parseLong(trimmed.substring(0, trimmed.length() - 1)) * 1000L;
            } else {
                return Long.parseLong(trimmed) * 1000L;
            }
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * 优雅关闭
     */
    public void shutdown() {
        stopHeartbeat();
        observerExecutor.shutdown();
        deregister(null, registeredInstance);
    }
}
