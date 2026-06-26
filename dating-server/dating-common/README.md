# dating-common

dating-server 公共模块。**首版仅包含 `BizIdGenerator`**（业务主键生成器），后续按需扩展（Result / BizException / 工具类等）。

Maven 坐标：

```xml
<dependency>
    <groupId>com.dating</groupId>
    <artifactId>dating-common</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

> 本期未配 Jenkins / Nexus 自动发布，开发者本地 `mvn install` 装到 `~/.m2` 即可被其他服务解析。

---

## BizIdGenerator

### 业务主键格式

```
环境(1) + 日期 YYMMDD(6) + 当天序号(默认 4 位) = 默认 11 位 BIGINT
例:测试环境 2026-05-27 第 1 个 = 12605270001
   生产环境 2026-05-27 第 1 个 = 22605270001
```

- 环境位:`1` = test,`2` = prod,由部署侧 `.env` 注入
- 当天序号默认 4 位(9999/天);**超出不报错,自动扩位**(第 10000 个 = 12 位 `126052710000`),无固定长度上限
- ID 数值单调递增 ⇒ "ID 大 = 创建晚"
  - 同表同天:永远成立
  - 同表跨天:只要那一天没溢出 9999 就成立;若某天序号涨到 5 位+,该天后段 ID 会大于次日前段(跨天顺序在溢出窗口内会反转)。低频表碰不到;高频表若在意跨天顺序,自行评估
- 仅保留 ~1e12/天 的硬上限(防 BIGINT 溢出成负数),实际碰不到

### 序号怎么生成

PG 表 `biz_id_seq(table_name, date_part, seq)`,一句 `INSERT ... ON CONFLICT DO UPDATE RETURNING seq` 完成原子自增。**不依赖 Redis**。

---

## 接入步骤

### 1. 加依赖

`pom.xml`:

```xml
<dependency>
    <groupId>com.dating</groupId>
    <artifactId>dating-common</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### 2. 复制建表脚本到自己的 Flyway

把本模块的 `src/main/resources/sql/biz_id_seq.sql` 拷到调用服务的 Flyway 目录:

```
<service>/src/main/resources/db/migration/V<n>__biz_id_seq.sql
```

> 多个服务接入同一个 PG 库时,**只有最先接入的那个服务**负责落 Flyway 脚本;后接入者依靠库里已有的表即可,不要重复加迁移(会因为 V 号冲突或表已存在而出错)。

### 3. 配置环境前缀

`application.yml`:

```yaml
dating:
  bizid:
    env-prefix: ${BIZID_ENV_PREFIX}
```

`deploy/.env.deploy` 加:

```bash
BIZID_ENV_PREFIX=1   # 测试
# BIZID_ENV_PREFIX=2 # 生产
```

没配 / 配错(不是 1 或 2)会导致**应用启动失败**(`IllegalStateException`),避免环境串号。

### 4. 业务代码使用

```java
@RequiredArgsConstructor
public class UserInfoManager {
    private final BizIdGenerator bizIdGenerator;
    private final UserInfoMapper userInfoMapper;

    public void create(UserInfo userInfo) {
        userInfo.setUserId(bizIdGenerator.next("user_info"));
        userInfoMapper.insert(userInfo);
    }
}
```

业务 entity 加业务主键字段(不替换物理主键 `id`):

```java
@TableField("user_id")
private Long userId;
```

DB 加列(写在业务表的下一个 Flyway 脚本里):

```sql
ALTER TABLE user_info ADD COLUMN user_id BIGINT;
ALTER TABLE user_info ADD CONSTRAINT uk_user_info_user_id UNIQUE (user_id);
-- 接入完成、确认所有插入路径都设了 user_id 后,再 ALTER ... SET NOT NULL
```

---

## 异常

`BizIdOverflowException` —— 仅当某张表单日序号越过 BIGINT 安全上限(~1e12/天)时抛出,正常业务量碰不到,纯防御 Long 溢出。日常超过 9999/天**不再抛异常**,而是自动扩位。

---

## 限制 / 已知约束

- 单数据源:Mapper 走应用默认 `DataSource`,多数据源场景未支持
- 单环境前缀:每个应用实例只能是 test 或 prod;跨环境写同一 PG 实例是反模式,不在范围内
- 时区固定 Asia/Shanghai:跨时区部署需要扩展
- YYMMDD 在 2099→2100 跨世纪会撞;73 年后的事
