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
package org.noear.solon.cloud.extend.cse.integration;

import org.noear.solon.Utils;
import org.noear.solon.annotation.BindProps;
import org.noear.solon.cloud.CloudClient;
import org.noear.solon.cloud.CloudManager;
import org.noear.solon.cloud.extend.cse.api.CseSdkClient;
import org.noear.solon.cloud.extend.cse.impl.CseConfig;
import org.noear.solon.cloud.extend.cse.service.CloudConfigServiceCseImp;
import org.noear.solon.cloud.extend.cse.service.CloudDiscoveryServiceCseImp;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.BeanWrap;
import org.noear.solon.core.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 华为云 CSE (ServiceComb 引擎) Solon Cloud 插件入口
 *
 * 基于 ServiceComb Java Chassis SDK 集成：
 * - 使用 ServiceCenterClient 进行服务注册发现
 * - 使用 KieClient 进行 KIE 配置中心管理
 * - 通过 IAM Token (AK/SK) 自动认证
 *
 * 配置示例 (app.yml):
 * <pre>
 * solon.app:
 *   group: "demo"
 *   name: "demoapp"
 *
 * solon.cloud.cse:
 *   server: "https://cse.cn-north-4.myhuaweicloud.com"
 *   projectId: "default"
 *   domainName: "default"
 *   appId: "default"
 *   accessKey: "YOUR_AK"
 *   secretKey: "YOUR_SK"
 *   config:
 *     load: "demoapp.yml"
 * </pre>
 *
 * @author 王忠凯
 * @since 1.2
 */
public class CseCloudPlugin implements Plugin {
    static final Logger log = LoggerFactory.getLogger(CseCloudPlugin.class);

    private CloudDiscoveryServiceCseImp discoveryService;
    private CloudConfigServiceCseImp configService;

    @Override
    public void start(AppContext context) {
        CseConfig cseConfig = new CseConfig(context);

        // CSE 的注册中心与配置中心地址通常是分开的，只要任一配置了就启动插件
        if (Utils.isEmpty(cseConfig.getRegistryServer()) && Utils.isEmpty(cseConfig.getConfigServer())) {
            log.debug("CSE server not configured, skip plugin init");
            return;
        }

        log.info("CSE Cloud Plugin starting (SDK mode), registry={}, config={}",
                cseConfig.getRegistryServer(), cseConfig.getConfigServer());

        // 初始化 SDK 客户端
        CseSdkClient sdkClient = new CseSdkClient(cseConfig);

        //1. 登记配置服务
        if (cseConfig.getConfigEnable()) {
            try {
                configService = new CloudConfigServiceCseImp(sdkClient);
                CloudManager.register(configService);
                // 支持从多个配置文件加载（YAML 数组或逗号分隔）
                List<String> loadFiles = cseConfig.getConfigLoadFiles();
                for (String loadFile : loadFiles) {
                    CloudClient.configLoad(loadFile);
                    log.info("CSE config loaded: {}", loadFile);
                }
                registerBindPropsRefresh(context);
                log.info("CSE CloudConfigService registered (KIE SDK), loaded {} config file(s)", loadFiles.size());
            } catch (Exception e) {
                log.warn("CSE CloudConfigService init failed: {}", e.getMessage());
            }
        }

        //2. 登记发现服务
        if (cseConfig.getDiscoveryEnable()) {
            try {
                discoveryService = new CloudDiscoveryServiceCseImp(sdkClient);
                CloudManager.register(discoveryService);
                log.info("CSE CloudDiscoveryService registered (ServiceCenter SDK)");
            } catch (Exception e) {
                log.warn("CSE CloudDiscoveryService init failed: {}", e.getMessage());
            }
        }
    }

    /**
     * 注册 @BindProps 自动刷新
     *
     * 当 CSE 远程配置变更时，Solon.cfg() 会通过 loadAdd 更新，
     * 并触发 onChange 事件。此处监听该事件，对所有标注了 @BindProps
     * 的 Bean 执行重新绑定，使其字段值与最新配置保持一致。
     *
     * 使用 500ms 防抖避免一次 loadAdd 触发多次 onChange 时频繁重绑。
     */
    private void registerBindPropsRefresh(AppContext context) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cse-bindprops-refresh");
            t.setDaemon(true);
            return t;
        });
        AtomicBoolean pending = new AtomicBoolean(false);

        context.cfg().onChange((key, value) -> {
            if (pending.compareAndSet(false, true)) {
                scheduler.schedule(() -> {
                    pending.set(false);
                    try {
                        List<BeanWrap> beans = context.beanFind(
                                w -> w.clz().isAnnotationPresent(BindProps.class));
                        for (BeanWrap wrap : beans) {
                            BindProps anno = wrap.clz().getAnnotation(BindProps.class);
                            Object bean = wrap.raw();
                            if (bean != null && anno != null) {
                                context.cfg().getProp(anno.prefix()).bindTo(bean);
                                log.info("CSE @BindProps refreshed: {}", wrap.clz().getSimpleName());
                            }
                        }
                    } catch (Exception e) {
                        log.warn("CSE @BindProps refresh failed: {}", e.getMessage());
                    }
                }, 500, TimeUnit.MILLISECONDS);
            }
        });

        log.info("CSE @BindProps auto-refresh registered");
    }

    /**
     * 预停止（Solon 关闭时调用，先注销服务实例）
     */
    @Override
    public void preStop() throws Throwable {
        if (discoveryService != null) {
            discoveryService.shutdown();
        }
        if (configService != null) {
            configService.shutdown();
        }
    }

    /**
     * 完全停止
     */
    @Override
    public void stop() throws Throwable {
        // preStop 里已经做了清理
    }
}
