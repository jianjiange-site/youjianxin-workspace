# Nacos 配置粘贴目录

每个服务一个文件,**直接复制粘贴到 Nacos 控制台**就能跑。

## 怎么用

1. 浏览器开 [http://38.76.188.242:8848/nacos](http://38.76.188.242:8848/nacos),账号 `nacos` / 密码 `jianjiange`(见 `docs/dev-onboarding.md §0`)。
2. 左上角切到 namespace **`dev-youjianxin`**(没有就按 `dev-onboarding §4.2` 建一个)。
3. 「配置管理 → 配置列表 → +」,填:
   - **Data ID**:跟本目录下的文件名**完全一致**(包含 `.yaml` 后缀)。例如 `dating-youjianxin-post-service-dev.yaml`。
   - **Group**:`DEFAULT_GROUP`(默认值,不用改)。
   - **配置格式**:`YAML`。
   - **配置内容**:把对应文件的内容**整文件粘进去**(去掉文件最上面的 5 行注释也行,无所谓)。
4. 保存,服务下次启动会自动拉。

## 现有文件

| 服务 | Data ID | 说明 |
|---|---|---|
| post-service | `dating-youjianxin-post-service-dev.yaml` | DB / Redis / MinIO / RocketMQ 完整连接信息 |

未来加新服务就在本目录下放对应的 `<spring.application.name>-<profile>.yaml`。

## 为什么真值直接进 git

CLAUDE.md 红线 #1 已经放宽:**共享 dev 凭据**(`38.76.188.242` 那套)允许写入这个目录,方便学员零编辑粘贴。**生产环境凭据仍严禁进 git**,这个目录如果未来要承载生产配置必须改成占位模板。
