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
package org.noear.solon.cloud.extend.cse.impl;

import org.noear.solon.cloud.CloudProps;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Props;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSE 专属配置扩展
 * 继承 CloudProps，补充 CSE ServiceComb 引擎特有的配置项
 *
 * @author 王忠凯
 * @since 1.0
 */
public class CseConfig extends CloudProps {

    public CseConfig(AppContext appContext) {
        super(appContext, "cse");
    }

    /**
     * 注册中心地址（host:port）
     * 对应 solon.cloud.cse.registryServer
     * 未配置时回退到 server
     */
    public String getRegistryServer() {
        String v = getValue("registryServer");
        if (v == null || v.isEmpty()) {
            v = getServer();
        }
        return v;
    }

    /**
     * 配置中心地址（host:port）
     * 对应 solon.cloud.cse.configServer
     * 未配置时回退到 server
     */
    public String getConfigServer() {
        String v = getValue("configServer");
        if (v == null || v.isEmpty()) {
            v = getServer();
        }
        return v;
    }

    /**
     * 项目 ID / 租户 ID (CSE REST API 的 project_id)
     * 对应 solon.cloud.cse.projectId
     */
    public String getProjectId() {
        String v = getValue("projectId");
        if (v == null || v.isEmpty()) {
            v = "default";
        }
        return v;
    }

    /**
     * 域名 (CSE 需要 x-domain-name header)
     * 对应 solon.cloud.cse.domainName
     */
    public String getDomainName() {
        String v = getValue("domainName");
        if (v == null || v.isEmpty()) {
            v = "default";
        }
        return v;
    }

    /**
     * 微服务应用名 (CSE 的 appId)
     * 对应 solon.cloud.cse.appId
     */
    public String getAppId() {
        String v = getValue("appId");
        if (v == null || v.isEmpty()) {
            v = "default";
        }
        return v;
    }

    /**
     * 是否启用 HTTPS
     * 对应 solon.cloud.cse.https
     */
    public boolean isHttps() {
        String v = getValue("https");
        return "true".equalsIgnoreCase(v);
    }

    // ==================== 配置中心标签 ====================

    /**
     * KIE 配置查询的自定义标签
     * 对应 solon.cloud.cse.config.labels
     *
     * 支持两种格式:
     * 1. 字符串: "app:demo,version:v1" (逗号分隔)
     * 2. YAML Map: labels:\n  app: demo\n  version: v1
     *
     * 未配置时返回空 Map, 查询时回退到默认标签 app:{appId}
     */
    public Map<String, String> getConfigLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        // YAML Map 格式: labels:\n  app: demo\n  env: prod
        Props labelProps = getProp("config.labels");
        if (labelProps != null && !labelProps.isEmpty()) {
            labelProps.forEach((k, v) -> {
                if (k != null && v != null) {
                    labels.put(k.toString(), v.toString());
                }
            });
            return labels;
        }
        // 字符串格式: "app:demo,env:prod"
        String str = getValue("config.labels");
        if (str != null && !str.isEmpty()) {
            for (String pair : str.split(",")) {
                int idx = pair.indexOf(':');
                if (idx > 0) {
                    labels.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
                }
            }
        }
        return labels;
    }

    /**
     * 获取需要加载的配置文件列表
     * 对应 solon.cloud.cse.config.load
     *
     * 支持三种格式:
     * 1. 单个文件: load: "demo-app.yml"
     * 2. YAML 数组: load:\n  - demo-app.yml\n  - demo-db.yml
     * 3. 逗号分隔: load: "demo-app.yml,demo-db.yml"
     *
     * 返回去重后的文件列表，未配置时返回空列表
     */
    public List<String> getConfigLoadFiles() {
        List<String> files = new ArrayList<>();
        // 1. 优先检查 YAML 数组格式: config.load:\n  - file1\n  - file2
        List<String> list = getProp("config").getList("load");
        if (!list.isEmpty()) {
            for (String f : list) {
                if (f != null && !f.isEmpty() && !files.contains(f)) {
                    files.add(f);
                }
            }
            return files;
        }
        // 2. 回退到单个字符串格式（兼容逗号分隔）
        String single = getConfigLoad();
        if (single != null && !single.isEmpty()) {
            for (String f : single.split(",")) {
                f = f.trim();
                if (!f.isEmpty() && !files.contains(f)) {
                    files.add(f);
                }
            }
        }
        return files;
    }

    // ==================== 认证配置 ====================

    /**
     * 是否启用 AK/SK 认证
     * 对应 solon.cloud.cse.auth.enable
     * 默认：配置了 accessKey 且非空时自动启用
     */
    public boolean isAuthEnabled() {
        String v = getValue("auth.enable");
        if (v != null && !v.isEmpty()) {
            return "true".equalsIgnoreCase(v);
        }
        // 未显式配置时，有 AK 就启用
        String ak = getAccessKey();
        return ak != null && !ak.isEmpty();
    }

    /**
     * IAM 认证端点地址（host:port 或完整 URL）
     * 对应 solon.cloud.cse.auth.iamEndpoint
     * 未配置时从 registryServer 的 host 推导
     */
    public String getIamEndpoint() {
        String v = getValue("auth.iamEndpoint");
        if (v == null || v.isEmpty()) {
            // 默认从注册中心地址推导 IAM 地址
            String registry = getRegistryServer();
            if (registry == null || registry.isEmpty()) {
                return null;
            }
            // 去掉端口，用默认 443/80
            String host = registry.split(":")[0];
            return host;
        }
        return v;
    }

    /**
     * 心跳间隔（秒），默认 15
     * 对应 solon.cloud.cse.discovery.healthCheckInterval
     */
    public int getHealthCheckInterval() {
        String v = getDiscoveryHealthCheckInterval("15");
        return parseIntSafe(v, 15);
    }

    /**
     * 心跳失败次数阈值，默认 3
     * 对应 solon.cloud.cse.discovery.healthCheckTimes
     */
    public int getHealthCheckTimes() {
        String v = getValue("discovery.healthCheckTimes");
        return parseIntSafe(v, 3);
    }

    /**
     * 实例注册后，多长时间（秒）开始心跳，默认 30
     */
    public int getHealthCheckDelay() {
        String v = getValue("discovery.healthCheckDelay");
        return parseIntSafe(v, 30);
    }

    /**
     * 连接超时（毫秒）
     */
    public int getConnectTimeout() {
        String v = getValue("connectTimeout");
        return parseIntSafe(v, 5000);
    }

    /**
     * 读取超时（毫秒）
     */
    public int getReadTimeout() {
        String v = getValue("readTimeout");
        return parseIntSafe(v, 10000);
    }

    /**
     * 安全的整数解析
     */
    private static int parseIntSafe(String v, int def) {
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
