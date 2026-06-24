# Nacos 配置粘贴目录

每个服务一个文件,**直接复制粘贴到 Nacos 控制台**就能跑。

## 命名规范

- **文件名 = `<spring.application.name>.yaml`**(就是 Spring 默认 Data ID 格式),不带学员前缀也不带 env,因为 namespace 已经隔离了
- **namespace = `<拼音>-dating-<env>`**(本仓库 dev 用 `youjianxin-dating-dev`),所有学员/环境隔离都靠这一层

完整规则见 `CLAUDE.md §个人隔离前缀`。

## 怎么用

1. 浏览器开 [http://38.76.188.242:8848/nacos](http://38.76.188.242:8848/nacos),账号 `nacos` / 密码 `jianjiange`(见 `docs/dev-onboarding.md §0`)
2. 左侧「命名空间 → 新建命名空间」(只在第一次做):
   - 命名空间名 + ID 都填 **`youjianxin-dating-dev`**
3. 切到 `youjianxin-dating-dev` namespace,「配置管理 → 新建配置」:
   - **Data ID**:跟本目录下的文件名**完全一致**(包含 `.yaml` 后缀)。例如 `post-service.yaml`
   - **Group**:`DEFAULT_GROUP`(默认,不改)
   - **配置格式**:`YAML`
   - **配置内容**:打开对应文件,**整文件复制粘贴**(最上面的 5 行注释带不带都行)
4. 「发布」保存,服务下次启动会自动拉

## 现有文件

| 服务 | Data ID | namespace |
|---|---|---|
| post-service | `post-service.yaml` | `youjianxin-dating-dev` |

未来加新服务就在本目录下放对应的 `<service>.yaml`。
prod 环境用同样文件内容(把真值改成 prod),贴到 `youjianxin-dating-prod` namespace 即可,Data ID 仍然是 `post-service.yaml`。

## 为什么真值直接进 git

CLAUDE.md 红线 #1 已经放宽:**共享 dev 凭据**(`38.76.188.242` 那套)允许写入这个目录,方便学员零编辑粘贴。**生产环境凭据仍严禁进 git**,prod 配置不在本目录,由运维侧直接在 Nacos 控制台维护。
