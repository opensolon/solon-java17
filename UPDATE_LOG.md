

### v3.11.0

* 新增 solon-cache-caffeine3
* undertow-jakarta 升为 2.3.24.Final
* jetty.jakarta 升为 12.1.9
* xxl-job-core 升为 3.4.0
* jackson3 升为 3.1.3
* tomcat 升为 11.0.22

### v3.10.0

* jakarta.logback 升为 1.5.32
* jackson3 升为 3.1.0

### v3.9.6

* jakarta.logback 升为 1.5.32
* jackson3 升为 3.1.0

### v3.9.1

* jakarta.logback 升为 1.5.27

### v3.9.0

* 优化 solon-serialization-jackson3 初始化处理

### v3.8.4

* 修复 solon.serialization.json.enumAsName: false 部分序列化框架不生效的问题

### v3.8.1

* 新增 `solon-net-httputils-java11` 插件 //还有问题
* 添加 `solon-server` newVirtualThreadFactory 名字前缀支持
* 调整 项目的默认 java 版本改为 11；需要 17 的模块则指定 17 

### v3.8.0

* 添加 `solon-server-tomcat-jakarta` ssl、vthread 支持
* 添加 `solon-server-undertow-jakarta` jsp、ssl、vthread 支持
* 添加 `hibernate-jakarta-solon-plugin` EntityManager 注入支持
* 优化 `solon-server-undertow-jakarta` 请求大小控制
* 优化 `solon-web-servlet-jakarta` 启用 ContextHolder:currentWith 替代 currentSet（兼容 ScopedValue 切换）
* 修复 `hibernate-jakarta-solon-plugin` 无法自动建表的问题,修改SessionFactory初始化时机


http-server 情况汇总

| 插件                            | jsp  | ssl | vthread |
|-------------------------------|------|-----|---------|
| solon-server-tomcat-jakarta   | 1    | 1   | 1       |
| solon-server-jetty-jakarta    | 1    | 1   | 1       |
| solon-server-undertow-jakarta | 1    | 1   | 1       |



### v3.7.3

* 新增 xxljob3-solon-cloud-plugin 插件
* 修复 solon-server-jetty-jakarta jsp 支持
* 添加 solon-server-tomcat-jakarta jsp 支持
* 添加 solon-server-tomcat-jakarta websocket 支持

### v3.7.0

* 优化 solon-server 不再默认输出 TEXT_PLAIN_UTF8_VALUE（允许空 content-type）
* 移除 solon.xxx 和 nami.xxx 风格的发布包
* jakarta.logback 升为 1.5.20

### v3.6.3

* 优化 solon-server 不再默认输出 TEXT_PLAIN_UTF8_VALUE（允许空 content-type）

### v3.6.0

* 新增 solon-server-undertow-jakarta 插件
* 新增 solon-server-undertow-add-jsp-jakarta 插件
* 完善 solon-server-jetty-jakarta 插件
* 完善 solon-server-jetty-add-jsp-jakarta 插件
* 完善 solon-server-jetty-add-websocket-jakarta 插件

### v3.5.2

* 新增 solon-server-jetty-jakarta 插件
* 新增 solon-server-jetty-add-jsp-jakarta 插件
* 新增 solon-server-jetty-add-websocket-jakarta 插件

### v3.4.0

* 新增 hibernate-jakarta-solon-plugin 插件
* 新增 solon-web-webservices-jakarta 插件