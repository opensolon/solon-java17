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
package org.noear.solon.cloud.extend.cse.api;

import com.google.common.eventbus.EventBus;
import org.apache.servicecomb.config.kie.client.KieClient;
import org.apache.servicecomb.config.kie.client.model.KieAddressManager;
import org.apache.servicecomb.config.kie.client.model.KieConfiguration;
import org.apache.servicecomb.foundation.auth.SignRequest;
import org.apache.servicecomb.foundation.ssl.SSLCustom;
import org.apache.servicecomb.foundation.ssl.SSLOption;
import org.apache.servicecomb.http.client.auth.DefaultRequestAuthHeaderProvider;
import org.apache.servicecomb.http.client.auth.RequestAuthHeaderProvider;
import org.apache.servicecomb.http.client.common.HttpConfiguration;
import org.apache.servicecomb.http.client.common.HttpTransport;
import org.apache.servicecomb.http.client.common.HttpTransportFactory;
import org.apache.servicecomb.service.center.client.ServiceCenterAddressManager;
import org.apache.servicecomb.service.center.client.ServiceCenterClient;
import org.apache.servicecomb.service.center.client.ServiceCenterRawClient;
import org.noear.solon.Solon;
import org.noear.solon.cloud.extend.cse.impl.CseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * CSE SDK 客户端封装
 *
 * 使用 ServiceComb Java Chassis SDK 的原生客户端类：
 * - ServiceCenterClient: 服务注册发现
 * - KieClient: KIE 配置中心
 * - HttpTransport: 直接 HTTP 调用（用于 KIE push/remove）
 *
 * 同时提供 IAM Token 认证，自动获取和刷新 Token。
 *
 * @author noear
 * @since 1.2
 */
public class CseSdkClient {
    static final Logger log = LoggerFactory.getLogger(CseSdkClient.class);

    private final CseConfig config;
    private final EventBus eventBus;

    private final ServiceCenterClient serviceCenterClient;
    private final KieClient kieClient;
    private final HttpTransport httpTransport;
    private final KieConfiguration kieConfiguration;
    private final KieAddressManager kieAddressManager;

    public CseSdkClient(CseConfig config) {
        this.config = config;
        this.eventBus = new EventBus();

        // 1. 创建 SSL 配置
        HttpConfiguration.SSLProperties sslProperties = createSslProperties(config);

        // 2. 创建认证提供者
        RequestAuthHeaderProvider authProvider = createAuthProvider(config);

        // 3. 创建 HTTP 传输层
        this.httpTransport = HttpTransportFactory.createHttpTransport(sslProperties, authProvider);

        // 4. 创建 ServiceCenterClient（注册中心）
        String registryAddress = buildAddress(config.getRegistryServer());
        ServiceCenterAddressManager addressManager = new ServiceCenterAddressManager(
                config.getProjectId(),
                Collections.singletonList(registryAddress),
                "", // vhost
                getLocalIp(),
                eventBus
        );

        ServiceCenterRawClient rawClient = new ServiceCenterRawClient.Builder()
                .setTenantName(config.getProjectId())
                .setHttpTransport(httpTransport)
                .setAddressManager(addressManager)
                .build();

        this.serviceCenterClient = new ServiceCenterClient(rawClient);

        // 5. 创建 KieClient（配置中心）
        String kieAddress = buildAddress(config.getConfigServer());
        this.kieAddressManager = new KieAddressManager(
                Collections.singletonList(kieAddress),
                config.getProjectId(),
                "",
                eventBus
        );

        this.kieConfiguration = new KieConfiguration()
                .setProject(config.getProjectId())
                .setAppName(config.getAppId())
                .setServiceName(Solon.app() != null ? Solon.cfg().appName() : "default")
                .setEnvironment("production")
                .setEnableAppConfig(true)
                .setEnableServiceConfig(true)
                .setEnableCustomConfig(true)
                .setEnableLongPolling(false)
                .setFirstPullRequired(true);

        // 设置自定义标签（KieConfiguration 仅支持单个 customLabel + customLabelValue）
        Map<String, String> customLabels = config.getConfigLabels();
        if (!customLabels.isEmpty()) {
            Map.Entry<String, String> first = customLabels.entrySet().iterator().next();
            this.kieConfiguration
                    .setCustomLabel(first.getKey())
                    .setCustomLabelValue(first.getValue());
            log.info("CSE KIE custom label set: {}={}", first.getKey(), first.getValue());
        }

        this.kieClient = new KieClient(kieAddressManager, httpTransport, kieConfiguration);

        log.info("CSE SDK client initialized: registry={}, kie={}, project={}",
                registryAddress, kieAddress, config.getProjectId());
    }

    public ServiceCenterClient getServiceCenterClient() {
        return serviceCenterClient;
    }

    public KieClient getKieClient() {
        return kieClient;
    }

    public HttpTransport getHttpTransport() {
        return httpTransport;
    }

    public KieConfiguration getKieConfiguration() {
        return kieConfiguration;
    }

    public KieAddressManager getKieAddressManager() {
        return kieAddressManager;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public CseConfig getConfig() {
        return config;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 构建 SDK 完整地址（自动加 http/https 前缀）
     */
    private String buildAddress(String host) {
        if (host == null || host.isEmpty()) {
            return host;
        }
        if (host.startsWith("http://") || host.startsWith("https://")) {
            return host;
        }
        String scheme = config.isHttps() ? "https://" : "http://";
        return scheme + host;
    }

    /**
     * 创建 SSL 配置
     */
    private HttpConfiguration.SSLProperties createSslProperties(CseConfig config) {
        HttpConfiguration.SSLProperties sslProps = new HttpConfiguration.SSLProperties();
        if (config.isHttps()) {
            sslProps.setEnabled(true);
            SSLOption sslOption = new SSLOption();
            // 开发环境：不验证对端证书
            sslOption.setEngine("jdk");
            sslOption.setProtocols("TLSv1.2,TLSv1.3");
            sslOption.setAuthPeer(false);
            sslOption.setCheckCNHost(false);
            sslProps.setSslOption(sslOption);
            sslProps.setSslCustom(SSLCustom.defaultSSLCustom());
        }
        return sslProps;
    }

    /**
     * 创建认证提供者
     *
     * 通过 solon.cloud.cse.auth.enable 控制：
     * - true:  使用 AK/SK 向 IAM 获取 Token 认证
     * - false: 不认证（本地/内网环境）
     * 默认：配置了 accessKey 且非空时自动启用
     */
    private RequestAuthHeaderProvider createAuthProvider(CseConfig config) {
        if (!config.isAuthEnabled()) {
            log.info("CSE auth disabled");
            return new DefaultRequestAuthHeaderProvider();
        }

        String ak = config.getAccessKey();
        String sk = config.getSecretKey();
        String iamEndpoint = config.getIamEndpoint();

        if (ak == null || ak.isEmpty() || sk == null || sk.isEmpty()) {
            log.warn("CSE auth enabled but AK/SK is missing, fallback to no auth");
            return new DefaultRequestAuthHeaderProvider();
        }

        log.info("CSE auth enabled, iamEndpoint={}", iamEndpoint);
        return new IamTokenAuthProvider(config, ak, sk, iamEndpoint);
    }

    /**
     * 获取本机 IP
     */
    private static String getLocalIp() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    // ==================== IAM Token 认证提供者 ====================

    /**
     * 基于 IAM Token 的认证提供者
     *
     * 使用 AK/SK 向华为云 IAM 获取临时 Token，
     * 缓存并在过期前自动刷新。
     */
    private static class IamTokenAuthProvider implements RequestAuthHeaderProvider {
        private static final Logger authLog = LoggerFactory.getLogger(IamTokenAuthProvider.class);

        private final CseConfig config;
        private final String ak;
        private final String sk;
        private final String iamEndpoint;

        private volatile String cachedToken;
        private volatile long expireAt; // 过期时间戳（毫秒）

        IamTokenAuthProvider(CseConfig config, String ak, String sk, String iamEndpoint) {
            this.config = config;
            this.ak = ak;
            this.sk = sk;
            this.iamEndpoint = iamEndpoint;
        }

        @Override
        public Map<String, String> loadAuthHeader(SignRequest signRequest) {
            String token = getToken();
            Map<String, String> headers = new HashMap<>();
            if (token != null) {
                headers.put("X-Auth-Token", token);
            }
            return headers;
        }

        /**
         * 获取有效的 IAM Token，自动缓存和刷新
         */
        String getToken() {
            long now = System.currentTimeMillis();
            // 提前 5 分钟刷新
            if (cachedToken != null && now < expireAt - 5 * 60 * 1000L) {
                return cachedToken;
            }

            synchronized (this) {
                if (cachedToken != null && now < expireAt - 5 * 60 * 1000L) {
                    return cachedToken;
                }

                try {
                    fetchToken();
                } catch (Exception e) {
                    authLog.warn("Failed to fetch IAM token: {}", e.getMessage());
                }

                return cachedToken;
            }
        }

        /**
         * 向华为云 IAM 获取 Token
         */
        @SuppressWarnings("unchecked")
        private void fetchToken() throws Exception {
            // 使用可配置的 IAM 端点
            String iamUrl;
            if (iamEndpoint != null && !iamEndpoint.isEmpty()) {
                if (iamEndpoint.startsWith("http://") || iamEndpoint.startsWith("https://")) {
                    iamUrl = iamEndpoint + "/v3/auth/tokens";
                } else {
                    String scheme = config.isHttps() ? "https://" : "http://";
                    iamUrl = scheme + iamEndpoint + "/v3/auth/tokens";
                }
            } else {
                // 兜底：从注册中心地址推导
                String iamHost = config.getRegistryServer().split(":")[0];
                iamUrl = (config.isHttps() ? "https://" : "http://") + iamHost + "/v3/auth/tokens";
            }

            // 构建请求体
            String domainName = config.getDomainName();
            String projectId = config.getProjectId();

            String body = String.format(
                    "{\"auth\":{\"identity\":{\"methods\":[\"aksk\"],\"aksk\":{\"ak\":\"%s\",\"sk\":\"%s\"}},"
                            + "\"scope\":{\"project\":{\"name\":\"%s\"}}}}",
                    ak, sk, domainName != null ? domainName : "default"
            );

            HttpURLConnection conn = (HttpURLConnection) new URL(iamUrl).openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Domain-Name", domainName != null ? domainName : "default");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int status = conn.getResponseCode();
                if (status == 201) {
                    // Token 在响应头 X-Subject-Token 中
                    cachedToken = conn.getHeaderField("X-Subject-Token");
                    // 过期时间在响应体中
                    try (InputStream is = conn.getInputStream()) {
                        String respBody = readAll(is);
                        // 简单解析 expires_at
                        long expiresAt = parseExpiresAt(respBody);
                        if (expiresAt > 0) {
                            expireAt = expiresAt;
                        } else {
                            // 默认 12 小时
                            expireAt = System.currentTimeMillis() + 12 * 3600 * 1000L;
                        }
                    }
                    authLog.info("IAM token fetched successfully, expires at {}", new Date(expireAt));
                } else {
                    String errBody = readAll(conn.getErrorStream());
                    authLog.warn("Failed to fetch IAM token: status={}, body={}", status, errBody);
                }
            } finally {
                conn.disconnect();
            }
        }

        private long parseExpiresAt(String json) {
            // 简单提取 "expires_at":"2025-01-01T..." 并转换为时间戳
            try {
                int idx = json.indexOf("\"expires_at\"");
                if (idx < 0) return 0;
                int start = json.indexOf("\"", idx + 12) + 1;
                int end = json.indexOf("\"", start);
                String dateStr = json.substring(start, end);
                // ISO 8601 格式，使用简单解析
                return java.time.OffsetDateTime.parse(dateStr).toInstant().toEpochMilli();
            } catch (Exception e) {
                return 0;
            }
        }

        private String readAll(InputStream is) throws IOException {
            if (is == null) return "";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        }
    }
}
