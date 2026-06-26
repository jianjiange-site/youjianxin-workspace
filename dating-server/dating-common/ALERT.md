# Alert 企业微信告警 · 接入手册

`com.dating.common.alert` —— 业务服务的关键链路报错时,自动 / 显式推企业微信「企业应用」消息接口。

业务侧近乎零侵入:已有的 `log.error(..., ex)` 自动兜底告警;关键链路可选 `AlertNotifier` 显式调用,带业务上下文。

> 本文档面向**接入方**(各业务服务的开发者)。原理细节看「工作原理」段;接入只需读完前三节(Step 1~3)。

---

## Step 1 · 申请企业微信应用并拿到三件套

由企业微信管理员在「我的企业 → 应用管理 → 自建」里新建一个应用(已有也可复用),拿到:

| 参数 | 含义 | 哪里看 |
|---|---|---|
| `corp-id` | 企业 ID | 「我的企业 → 企业信息」最下方 |
| `agent-id` | 应用 AgentId | 应用详情页顶部,整数 |
| `corp-secret` | 应用 Secret | 应用详情页「Secret」按钮,**只显示一次,马上存到 Nacos** |

> `corp-secret` 是密文敏感值,泄漏会被恶意调用消息接口刷消息。**严禁入仓** —— 只放 Nacos 对应环境的命名空间。

应用权限:确保应用可见范围包含将要被通知的人(`to-user`)、部门(`to-party`)、标签(`to-tag`)。

---

## Step 2 · pom 已就绪,不动

业务服务 pom 已经依赖 `dating-common`(`BizIdGenerator` / `ObjectStorage` 是这么接入的),**不需要新增任何 `<dependency>` 节点**。

---

## Step 3 · 配置(Nacos)

各服务在 Nacos 对应配置(`dating-<service>-<env>.yml`)里加:

```yaml
dating:
  alert:
    wework:
      corp-id: <你的企业 corpid>
      corp-secret: <应用 secret>          # 敏感,只放 Nacos,绝不入仓
      agent-id: <应用 agentid>             # 整数
      to-user: <userid>                    # 接收人,| 分隔多个;全空默认 @all 全员
```

如果多个服务公用同一个企业微信应用(推荐),把 `corp-id` / `agent-id` 放 Nacos 全局共享配置 `dating-alert-common.yml`,各服务 `shared-configs` 引用,避免每个服务重复写;`corp-secret` 同样放共享配。

**到此接入完成**。重启服务,所有 `log.error(...)` 自动告。

---

## Step 4 · 验证接入

加一个临时接口冒烟一下:

```java
@RestController
class AlertSmokeController {
    @Autowired AlertNotifier alertNotifier;
    private static final Logger log = LoggerFactory.getLogger(AlertSmokeController.class);

    @GetMapping("/__test/alert/log")
    public String viaLog() {
        log.error("smoke via appender", new RuntimeException("test-from-log"));
        return "ok";
    }

    @GetMapping("/__test/alert/notifier")
    public String viaNotifier() {
        alertNotifier.critical("__smoke", new RuntimeException("test-from-notifier"),
                Map.of("source", "manual"));
        return "ok";
    }
}
```

调一次,看企业微信里是否收到对应消息。验证通过后撤回这个 controller。

---

## 业务侧用法

### 零侵入:`log.error` 自动告

业务什么都不用改 —— 已有的全局异常处理器 / 业务代码里的 `log.error(...)` 会被 Logback Appender 拦截后异步推企业微信。

```java
// GlobalExceptionHandler 里既有的写法,无需改动
@ExceptionHandler(Throwable.class)
public Result<?> handle(Throwable ex) {
    log.error("user-service unexpected error", ex);   // ← 自动告警
    return Result.fail(500, "服务异常");
}
```

### 显式 API:关键链路标重

注入 `AlertNotifier`,调三种等级之一:

```java
@Autowired
private AlertNotifier alertNotifier;

// CRITICAL:跳过签名窗口去重,只过全局令牌桶。真正关键的链路用这个。
alertNotifier.critical("payment.callback.signing", ex,
        Map.of("orderId", id, "channel", "alipay"));

// ERROR:走完整限流(签名窗口 + 全局令牌桶),和 Appender 路径同优先级。
alertNotifier.error("redis.lookup.fail", ex, Map.of("key", k));

// WARN:无异常的纯文本警告
alertNotifier.warn("disk.low", "disk usage 92%", Map.of("mount", "/data"));
```

三个方法都**非阻塞**:限流被拒 / 队列满都只是丢弃 + 内部计数,不会抛业务线程,不会阻塞业务调用。

---

## 告警样例(企业微信里看到的样子)

### CRITICAL

```
# [CRITICAL] user-service @ prod

**场景**: payment.callback.signing
**异常**: `java.lang.IllegalStateException`
**消息**: signature mismatch
**traceId**: `abc123def456`
**userId**: `1024`
**host**: user-service-7f9b8c-x2kp9
**时间**: 2026-05-28T03:14:07Z

**业务上下文**:
> orderId: 100200300
> channel: alipay

**堆栈**:
at com.dating.payment.service.PaymentService.verifySign(PaymentService.java:128)
at com.dating.payment.controller.CallbackController.alipayCallback(...)
...
```

### SUMMARY(签名窗口过期摘要)

```
### [SUMMARY] user-service @ prod

过去 10m 内异常签名 `7a3f9c2b` 共出现 **47** 次,已发送 3 条,截流 **44** 条。

**样例**: `org.springframework.dao.DataAccessException` @ `com.dating.user.UserMapper#selectById:42`
**首次**: 2026-05-28T03:14:07Z
**最近**: 2026-05-28T03:23:55Z
```

---

## 完整配置 schema

```yaml
dating:
  alert:
    enabled: true                        # 全局总开关。false 时所有路径 no-op,不装配 bean
    env: ${SPRING_PROFILES_ACTIVE:local} # 显示在告警标题(prod / test / dev)
    service: ${spring.application.name}  # 显示在告警标题

    wework:
      base-url: https://qyapi.weixin.qq.com  # 一般不动,集成测试时用 @DynamicPropertySource 覆盖
      corp-id: ""                        # 必填
      corp-secret: ""                    # 必填,走 Nacos
      agent-id: 0                        # 必填,> 0
      to-user: ""                        # | 分隔;全空默认 @all
      to-party: ""
      to-tag: ""
      connect-timeout-ms: 3000
      read-timeout-ms: 5000
      token-refresh-skew: 5m             # access_token 过期前主动刷新的提前量

    throttle:
      window-duration: 10m               # 同签名滑动窗口长度
      max-per-window: 3                  # 同签名窗口内最多发几条
      global-per-minute: 60              # 全局令牌桶速率(企业应用消息上限 30000/min,默认很保守)
      global-burst: 30                   # 令牌桶突发容量
      cleanup-interval: 1m               # 过期窗口扫描间隔

    appender:
      enabled: true                      # Logback Appender 总开关
      level: ERROR                       # 触发的最低级别(WARN/ERROR/OFF)
      ignore-loggers:                    # 前缀匹配的 logger 黑名单
        - org.apache.kafka
        - io.lettuce.core
        - com.alibaba.nacos
        - org.springframework.boot.actuate

    async:
      queue-capacity: 1024               # 异步队列容量,溢出丢弃 + 计数
      consumer-threads: 1                # 消费线程数,默认 1 足够
      shutdown-timeout: 5s               # 优雅关闭 grace
```

### 必填字段

`dating.alert.enabled=true`(默认)时:
- `wework.corp-id` 必填
- `wework.corp-secret` 必填
- `wework.agent-id` 必填(> 0)

缺一启动期 fail-fast 抛 `IllegalStateException`。

### 关掉告警

`dating.alert.enabled=false` —— 整个 AutoConfig 失活,所有 bean 不装配。本地 dev / CI 通常这么配。

---

## 工作原理

### 双轨触发

1. **Logback Appender 自动兜底**:`WeWorkAppender` 在 Spring 完全启动后(`ApplicationStartedEvent`)编程式 attach 到 root logger。任何 `log.error(...)` 或 `log.warn(...)`(取决于 `appender.level`)经过它都会异步推企业微信。业务零侵入。

2. **AlertNotifier 显式 API**:业务想带自定义业务上下文(orderId / channel / phase 等),或者标 critical 级别(跳过签名窗口),就显式调。

两条路径在 `AlertSender` 入口汇合,共享限流 + 异步发送 + 优雅关闭。

### 限流去重

- **异常签名** = `hash(异常class.FQN + 顶层栈帧 class#method:line)` → 64-bit `long`。null throwable 退化为 `scene + level`。
- **签名滑动窗口**:同签名 10min 最多 3 条;超过的 dropped++,窗口过期时 sweeper 主动 emit 一条 `[SUMMARY]` 摘要。
- **全局令牌桶**:60 token/min,burst 30,跨签名兜底,防多种异常同时爆发刷屏。
- **CRITICAL 等级**(`alertNotifier.critical(...)`)**跳过签名窗口**,只过全局令牌桶 —— 关键告警不该被合并。

### access_token 全生命周期管理

企业应用消息接口需要 access_token,`WeWorkAccessTokenManager` 处理:

- **双 check 缓存**:并发拿 token 只触发一次 `gettoken`,避免风暴期反复刷。
- **提前刷新**:token 名义 7200s 有效,提前 `token-refresh-skew`(默认 5min)主动刷,避免边界态卡在失效瞬间。
- **errcode 重试**:`message/send` 返回 `42001` / `40014`(token 失效)时,`WeWorkMessageClient` 调一次 `forceRefresh` 重试一次,**不再重试**。
- 远低于 `gettoken` 接口 corp 200/天的硬限制。

### 防循环触发(三重保险)

陷阱:`Appender → notifier.error → 异步队列 → HTTP 调用 → 若失败 → log.error → 又被 Appender 抓 → 死循环`。

防护:
1. `WeWorkAppender` 静态 `ThreadLocal<Boolean> SENDING` 守护调用线程,Appender → notifier → ... 整条同步路径短路。
2. 消费线程 name 固定前缀 `dating-alert-sender-`,Appender 看到这个前缀直接 return,异步线程 HTTP 失败的 `log.error`(虽然代码里只用 `log.debug`)永远进不来。
3. `AsyncAlertSender.doSend()` 捕获所有异常**只 `log.debug` 不 `log.error`**。

### 启动期 ERROR 处理

Logback 在 Spring `ApplicationContext` ready 之前已经活跃。`WeWorkAppender` 用静态 `NOTIFIER` 字段,Spring 启动完后 `AppenderRegistrar` 注入。启动期(Spring ready 之前)产生的 `log.error` 看到 `NOTIFIER == null` **直接丢弃**(不缓冲)。

理由:启动期 ERROR 通常意味着 fail-fast 配置错,服务即将挂掉;stderr 栈仍会被 Promtail 采到 Loki 兜底。

### 优雅关闭

`AsyncAlertSender` 实现 `SmartLifecycle`,`phase = Integer.MAX_VALUE - 100` 保证最晚关。`stop()` 触发后:

1. 拒绝新 enqueue
2. `executor.shutdown()` + `awaitTermination(shutdownTimeout)`
3. 默认 5s 内队列里残留消息有机会发出去
4. 超时未发完丢弃(JVM 即将退出,daemon 线程跟着死)

---

## 常见问题

### Q: 我想让某个 logger 不告警

加到 `dating.alert.appender.ignore-loggers`(前缀匹配):

```yaml
dating.alert.appender.ignore-loggers:
  - org.apache.kafka
  - com.example.NoisyService           # 整个 logger
  - com.example.noisy                  # 整个包前缀
```

### Q: 临时关掉所有告警

`dating.alert.enabled=false` —— Nacos 改完滚动重启即可(首版不支持 `@RefreshScope` 不重启生效)。

### Q: 同一个错误一直在告,刷屏怎么办

应该不会 —— 默认同签名 10min 最多 3 条 + 全局 60/min。如果真在刷:
- 调小 `dating.alert.throttle.max-per-window`(比如 1)
- 把对应 logger 加进 `ignore-loggers` 黑名单
- 看是不是 lambda 包裹导致栈帧漂移、签名不稳(可在 `WeWorkAppenderTest` 里复现验证)

### Q: 想 CRITICAL 发到 oncall 群、ERROR 发到 dev 群

首版只支持一组接收人。需要分级时改 `AlertProperties.WeWork` 加 `criticalToUser` / `criticalToParty` 字段,在 `WeWorkMessageClient` 按 level 路由。**改动量 ~30 行,不破坏现有 API**。

### Q: 多实例部署,签名窗口不共享,实际告警数是 N 倍

是的。N 实例 × 3 条/窗口 = 3N 条/窗口。可接受范围内不处理;真要去重,后续接 Redis 共享计数。

### Q: 想接入钉钉 / 邮件 / Slack

`AlertSender` 是接口,可以新加 `AlertChannel`(钉钉/邮件)实现,`AsyncAlertSender` 持有 `List<AlertChannel>` 广播。各通道 `@ConditionalOnProperty(prefix = "dating.alert.<channel>", name = "enabled")` 装配。首版不做,接口预留。

---

## 风险与首版限制

| 项 | 说明 |
|---|---|
| 多 JVM 实例 token 各自缓存 | corp 限 200/天,N=100 实例每天滚动重启 1 次 = 100/天,远低于上限 |
| 多 JVM 实例签名窗口不共享 | N 实例 × 3 条 = 3N 条/窗口。首版接受 |
| `enabled` 改配置不重启不生效 | 首版无 `@RefreshScope`,Nacos 改完滚动重启 |
| `corp-secret` 泄漏后果严重 | 严格走 Nacos,**不入仓**;命名空间按环境隔离 |
| 队列满 / webhook 长期 5xx 丢消息 | 业务零阻塞优先于告警可靠性;后续可加 circuit breaker |
| top frame 因 lambda 包裹漂移导致签名不稳 | 限流是兜底不是绝对去重,接受少量重复 |

---

## 关键文件

```
dating-common/src/main/java/com/dating/common/alert/
├── AlertAutoConfiguration.java          # @AutoConfiguration 入口
├── AlertProperties.java                 # dating.alert.* 配置
├── AlertNotifier.java                   # 显式 API 门面(业务侧唯一依赖点)
├── DefaultAlertNotifier.java
├── AlertEvent.java / SummaryEvent.java  # 不可变事件 record
├── AlertEventFactory.java
├── AlertLevel.java                      # enum {CRITICAL, ERROR, WARN}
├── HostInfo.java
├── throttle/
│   ├── AlertThrottler.java
│   ├── SignatureWindow.java
│   ├── ThrottleRegistry.java
│   ├── TokenBucket.java
│   └── ExceptionSignature.java
├── send/
│   ├── AlertSender.java
│   ├── AsyncAlertSender.java
│   ├── WeWorkAccessTokenManager.java
│   ├── WeWorkMessageClient.java
│   ├── MessageRenderer.java
│   └── SummaryEvent.java
└── logback/
    ├── WeWorkAppender.java              # UnsynchronizedAppenderBase<ILoggingEvent>
    └── AppenderRegistrar.java           # ApplicationStartedEvent → root logger.addAppender
```
