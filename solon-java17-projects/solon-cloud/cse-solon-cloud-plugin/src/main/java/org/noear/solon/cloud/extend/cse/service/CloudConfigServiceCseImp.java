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

import org.apache.servicecomb.config.kie.client.KieClient;
import org.apache.servicecomb.config.kie.client.model.ConfigurationsRequest;
import org.apache.servicecomb.config.kie.client.model.ConfigurationsResponse;
import org.apache.servicecomb.config.kie.client.model.KieAddressManager;
import org.apache.servicecomb.config.kie.client.model.ConfigurationsRequestFactory;
import org.apache.servicecomb.http.client.common.HttpRequest;
import org.apache.servicecomb.http.client.common.HttpResponse;
import org.apache.servicecomb.http.client.common.HttpTransport;
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.cloud.CloudConfigHandler;
import org.noear.solon.cloud.extend.cse.api.CseSdkClient;
import org.noear.solon.cloud.extend.cse.impl.CseConfig;
import org.noear.solon.cloud.model.Config;
import org.noear.solon.cloud.service.CloudConfigObserverEntity;
import org.noear.solon.cloud.service.CloudConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * CSE KIE 配置中心服务实现（基于 ServiceComb Java Chassis SDK）
 *
 * 核心机制:
 * - 通过 KieClient SDK 拉取 KIE 配置中心配置
 * - push/remove 通过 HttpTransport 直接调用 KIE REST API
 * - 使用定时轮询方式订阅配置变更
 *
 * @author noear
 * @since 1.2
 */
public class CloudConfigServiceCseImp implements CloudConfigService {
    static final Logger log = LoggerFactory.getLogger(CloudConfigServiceCseImp.class);

    private final CseConfig cseConfig;
    private final KieClient kieClient;
    private final HttpTransport httpTransport;
    private final KieAddressManager kieAddressManager;
    private final String appId;

    // 观察者调度器
    private final ScheduledExecutorService observerExecutor;

    // 已订阅的观察者集合
    private final Map<String, CloudConfigObserverEntity> observerMap = new ConcurrentHashMap<>();

    // 本地缓存的配置值
    private final Map<String, String> configCache = new ConcurrentHashMap<>();

    // KIE 配置请求列表（由 ConfigurationsRequestFactory 生成）
    private final List<ConfigurationsRequest> configRequests;

    public CloudConfigServiceCseImp(CseSdkClient sdkClient) {
        this.cseConfig = sdkClient.getConfig();
        this.kieClient = sdkClient.getKieClient();
        this.httpTransport = sdkClient.getHttpTransport();
        this.kieAddressManager = sdkClient.getKieAddressManager();
        this.appId = cseConfig.getAppId();

        // 生成配置请求列表（按 app/service/custom 作用域）
        this.configRequests = ConfigurationsRequestFactory.buildConfigurationRequests(
                sdkClient.getKieConfiguration()
        );

        // 观察者轮询线程池
        this.observerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cse-config-observer");
            t.setDaemon(true);
            return t;
        });

        log.info("CSE config service initialized: KIE mode, requests={}", configRequests.size());
    }

    /**
     * 拉取配置
     */
    @Override
    public Config pull(String group, String name) {
        if (Utils.isEmpty(group)) {
            group = Solon.cfg().appGroup();
        }
        if (Utils.isEmpty(group)) {
            group = appId;
        }

        try {
            String value = pullKieConfig(group, name);
            configCache.put(group + "@" + name, value);

            if (value != null) {
                return new Config(group, name, value, System.currentTimeMillis());
            }
        } catch (Exception e) {
            log.debug("CSE pull config exception: group={}, name={}, error={}",
                    group, name, e.getMessage());
        }

        return new Config(group, name, null, 0);
    }

    /**
     * 推送配置（通过 KIE REST API）
     */
    @Override
    public boolean push(String group, String name, String value) {
        if (Utils.isEmpty(group)) {
            group = Solon.cfg().appGroup();
        }
        if (Utils.isEmpty(group)) {
            group = appId;
        }

        try {
            // 先查询是否已存在
            String kieId = findKieId(group, name);

            String url = kieAddressManager.address() + "/v1/" + cseConfig.getProjectId() + "/kie/kv";
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");

            // 构建请求体
            String body = String.format(
                    "{\"key\":\"%s\",\"value\":%s,\"value_type\":\"yaml\",\"status\":\"enabled\","
                            + "\"labels\":{\"app\":\"%s\"}}",
                    name,
                    value != null ? "\"" + escapeJson(value) + "\"" : "null",
                    group
            );

            HttpResponse resp;
            if (kieId != null) {
                // 更新: PUT /v1/{project_id}/kie/kv/{kie_id}
                url += "/" + kieId;
                HttpRequest req = new HttpRequest(url, headers, body, "PUT");
                resp = httpTransport.put(req);
            } else {
                // 创建: POST /v1/{project_id}/kie/kv
                HttpRequest req = new HttpRequest(url, headers, body, "POST");
                resp = httpTransport.post(req);
            }

            if (resp.getStatusCode() >= 200 && resp.getStatusCode() < 300) {
                log.info("KIE push config success: name={}", name);
                return true;
            } else {
                log.warn("KIE push config failed: name={}, status={}, body={}",
                        name, resp.getStatusCode(), resp.getContent());
            }
        } catch (Exception e) {
            log.warn("KIE push config exception: name={}, error={}", name, e.getMessage());
        }
        return false;
    }

    /**
     * 移除配置
     */
    @Override
    public boolean remove(String group, String name) {
        if (Utils.isEmpty(group)) {
            group = Solon.cfg().appGroup();
        }
        if (Utils.isEmpty(group)) {
            group = appId;
        }

        configCache.remove(group + "@" + name);

        try {
            String kieId = findKieId(group, name);
            if (kieId == null) {
                log.debug("KIE config not found, skip delete: name={}", name);
                return true;
            }

            String url = kieAddressManager.address() + "/v1/" + cseConfig.getProjectId() + "/kie/kv/" + kieId;
            Map<String, String> headers = new HashMap<>();
            HttpRequest req = new HttpRequest(url, headers, null, "DELETE");

            HttpResponse resp = httpTransport.delete(req);
            if (resp.getStatusCode() >= 200 && resp.getStatusCode() < 300) {
                log.info("KIE delete config success: name={}", name);
                return true;
            } else {
                log.warn("KIE delete config failed: name={}, status={}", name, resp.getStatusCode());
            }
        } catch (Exception e) {
            log.warn("KIE delete config exception: name={}, error={}", name, e.getMessage());
        }
        return false;
    }

    /**
     * 关注配置变更（轮询模式）
     */
    @Override
    public void attention(String group, String name, CloudConfigHandler observer) {
        if (Utils.isEmpty(group)) {
            group = Solon.cfg().appGroup();
        }
        if (Utils.isEmpty(group)) {
            group = appId;
        }

        final String finalGroup = group;
        final String finalName = name;

        CloudConfigObserverEntity entity = new CloudConfigObserverEntity(finalGroup, finalName, observer);
        String key = finalGroup + "@" + finalName;
        observerMap.put(key, entity);

        String refreshInterval = cseConfig.getConfigRefreshInterval("10s");
        long period = parseIntervalMillis(refreshInterval, 10000);

        observerExecutor.scheduleAtFixedRate(() -> {
            try {
                String newValue = pullKieConfig(finalGroup, finalName);
                String oldValue = configCache.get(finalGroup + "@" + finalName);

                if (!Objects.equals(newValue, oldValue)) {
                    configCache.put(finalGroup + "@" + finalName, newValue);
                    Config config = new Config(finalGroup, finalName, newValue, System.currentTimeMillis());
                    entity.handle(config);
                }
            } catch (Exception e) {
                log.debug("CSE config attention poll exception: {}", e.getMessage());
            }
        }, period, period, TimeUnit.MILLISECONDS);

        log.info("CSE config attention started: group={}, name={}", finalGroup, finalName);
    }

    // ========== 内部方法 ==========

    /**
     * 通过 HttpTransport 直接调用 KIE REST API 拉取配置
     *
     * 先尝试 KieClient SDK 查询, 若失败则回退到直接 HTTP 查询。
     * 直接 HTTP 查询使用 GET /v1/{project_id}/kie/kv?label=app:{appId},
     * 解析 JSON 响应按 key 匹配配置值。
     */
    @SuppressWarnings("unchecked")
    private String pullKieConfig(String group, String name) {
        // 1. 先尝试 KieClient SDK
        try {
            for (ConfigurationsRequest request : configRequests) {
                ConfigurationsResponse resp = kieClient.queryConfigurations(request, request.getRevision());
                if (resp != null && resp.getConfigurations() != null) {
                    Object value = resp.getConfigurations().get(name);
                    if (value != null) {
                        return value.toString();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("KIE pull config via SDK failed, fallback to HTTP: name={}, error={}", name, e.getMessage());
        }

        // 2. SDK 查询失败时回退到直接 HTTP 查询
        try {
            String url = buildKieQueryUrl();
            Map<String, String> headers = new HashMap<>();
            HttpRequest req = new HttpRequest(url, headers, null, "GET");
            HttpResponse resp = httpTransport.get(req);

            if (resp.getStatusCode() >= 200 && resp.getStatusCode() < 300) {
                String content = resp.getContent();
                if (content != null) {
                    String value = extractKvValue(content, name);
                    if (value != null) {
                        log.debug("KIE config pulled via HTTP fallback: name={}", name);
                    }
                    return value;
                }
            } else {
                log.warn("KIE HTTP query failed: name={}, status={}", name, resp.getStatusCode());
            }
        } catch (Exception e) {
            log.warn("KIE pull config via HTTP failed: name={}, error={}", name, e.getMessage());
        }
        return null;
    }

    /**
     * 构建 KIE 查询 URL
     *
     * 如果配置了自定义标签 (solon.cloud.cse.config.labels), 使用自定义标签查询;
     * 否则回退到默认标签 app:{appId}
     */
    private String buildKieQueryUrl() {
        StringBuilder url = new StringBuilder();
        url.append(kieAddressManager.address())
                .append("/v1/").append(cseConfig.getProjectId())
                .append("/kie/kv");

        Map<String, String> labels = cseConfig.getConfigLabels();
        if (labels.isEmpty()) {
            // 默认: app:{appId}
            url.append("?label=app:").append(appId);
        } else {
            url.append("?");
            boolean first = true;
            for (Map.Entry<String, String> entry : labels.entrySet()) {
                if (!first) {
                    url.append("&");
                }
                url.append("label=").append(entry.getKey()).append(":").append(entry.getValue());
                first = false;
            }
        }
        return url.toString();
    }

    /**
     * 从 KIE 查询响应 JSON 中按 key 提取 value
     * 响应格式: {"total":N,"data":[{"key":"xxx","value":"yyy",...},...]}
     * 兼容冒号后有空格的 JSON 格式
     */
    private String extractKvValue(String json, String key) {
        int pos = 0;
        while (true) {
            // 查找 "key" 字段
            int keyTagIdx = json.indexOf("\"key\"", pos);
            if (keyTagIdx < 0) {
                return null;
            }
            // 跳过 "key" 找到冒号后的起始引号
            int i = keyTagIdx + 5;
            while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == ':' || json.charAt(i) == '\t' || json.charAt(i) == '\n' || json.charAt(i) == '\r')) {
                i++;
            }
            if (i >= json.length() || json.charAt(i) != '"') {
                pos = keyTagIdx + 5;
                continue;
            }
            i++; // 跳过起始引号
            // 读取 key 的值
            int keyStart = i;
            while (i < json.length() && json.charAt(i) != '"') {
                if (json.charAt(i) == '\\') i++; // 跳过转义字符
                i++;
            }
            String foundKey = json.substring(keyStart, i);
            if (!foundKey.equals(key)) {
                pos = i + 1;
                continue;
            }
            // key 匹配, 在其后查找 "value" 字段
            int valueTagIdx = json.indexOf("\"value\"", i);
            if (valueTagIdx < 0) {
                return null;
            }
            // 跳过 "value" 找到冒号后的起始引号
            i = valueTagIdx + 7;
            while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == ':' || json.charAt(i) == '\t' || json.charAt(i) == '\n' || json.charAt(i) == '\r')) {
                i++;
            }
            if (i >= json.length() || json.charAt(i) != '"') {
                return null;
            }
            i++; // 跳过起始引号
            // value 中可能包含转义字符, 逐字符解析
            StringBuilder sb = new StringBuilder();
            boolean escaped = false;
            for (; i < json.length(); i++) {
                char c = json.charAt(i);
                if (escaped) {
                    switch (c) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        default: sb.append(c); break;
                    }
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
    }

    /**
     * 查找 KIE 配置 ID
     */
    @SuppressWarnings("unchecked")
    private String findKieId(String group, String name) throws Exception {
        String url = kieAddressManager.address() + "/v1/" + cseConfig.getProjectId() + "/kie/kv?label=app:" + group;
        Map<String, String> headers = new HashMap<>();
        HttpRequest req = new HttpRequest(url, headers, null, "GET");

        HttpResponse resp = httpTransport.get(req);
        if (resp.getStatusCode() >= 200 && resp.getStatusCode() < 300) {
            // 简单解析 JSON 查找 id
            String content = resp.getContent();
            if (content != null) {
                // 查找 "key":"name" 对应的 "id":"xxx"
                int keyIdx = content.indexOf("\"key\":\"" + name + "\"");
                if (keyIdx >= 0) {
                    // 向前查找 "id"
                    int idStart = content.lastIndexOf("\"id\":\"", keyIdx);
                    if (idStart >= 0) {
                        idStart += 6;
                        int idEnd = content.indexOf("\"", idStart);
                        if (idEnd > idStart) {
                            return content.substring(idStart, idEnd);
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 简单的 JSON 字符串转义
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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
        observerExecutor.shutdown();
    }
}
