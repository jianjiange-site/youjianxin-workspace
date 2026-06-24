# match-service PRD + 技术设计

> 配套:`user-service-design.md`(身份/颜值分/人种/位置)、`im-service-design.md`(匹配后建会话/系统消息)、`digital-human-design.md`(DH 池)、`payment` 服务(订阅 + 金币)。

## 1. Context

Vibe App 首页是一个"卡片右划即喜欢"的 dating feed。本模块负责:

1. **卡片队列**:给每个用户每天生成一个 D1 推荐队列;新用户走 D0 冷启动。
2. **划卡 + 配额**:左/右划、Super Hi 三种动作,按订阅档位限额。
3. **匹配触发**:BH↔BH 看对方是否互相右划即时配对;BH↔DH 必须**延迟**配对(避免一秒回应露馅);Super Hi 是付费跳过条件的"硬匹配"。

平台用户分两类:
- **BH (Biological Human)**:真人用户。`user.user_type = BH`。
- **DH (Digital Human)**:数字人。`user.user_type = DH`,由 AI 后台驱动会话。

模块归属:**新增 `match-service` 微服务**(`com.dating.match`),独占自己的 PG schema + Redis 前缀,不与现有服务共表。

## 2. 上下游

```
                  ┌─ user-service ─┐       (查 profile / 颜值分 / 人种 / 位置)
                  │                │
[mobile-gateway]──┤                │
                  │   gRPC         │
                  └─► match-service◄────► payment-service   (查订阅档位 / 扣金币)
                       │
                       ├─► im-service        (匹配成功后建会话 + 系统消息)
                       ├─► PG (match 自有库)
                       └─► Redis (match: 前缀)

                  D1 cron ──► match-service (内部 @Scheduled)
```

**红线**:match-service 不直连 user 表、不直连 payment 表;只走 gRPC。

## 3. 业务规则

### 3.1 订阅档位 + 配额

订阅档位由 payment-service 维护,match-service 启动时通过 `payment.GetSubscription(user_id)` 查询并缓存(TTL 5 分钟,订阅变更时由 payment-service 主动推送 invalidate 事件)。

| 档位 | 每日右划次数 | 每日可划卡片 | 每日 Super Hi |
|---|---|---|---|
| FREE(普通) | 5 | 50 | 0 |
| WEEKLY(周) | 10 | 80 | 0 |
| MONTHLY(月) | 15 | 120 | 1 |
| YEARLY(年) | 15 | 120 | 1 |

**字段释义**(关键 ─ 避免歧义):
- **每日右划次数**:用户主动右划(喜欢)的硬上限。到上限后右划返回 `QUOTA_RIGHT_SWIPE_EXCEEDED`,Super Hi 也会消耗 1 次右划配额(因为本质就是高优先级喜欢)。
- **每日可划卡片**:用户当日 feed 整体消费**硬上限**(左划 + 右划 + Super Hi 全部计数)。到上限后 `GetTodayFeed` 返回空,前端展示"今天已经看完啦"。**与 D1 队列容量(240)不同** —— 队列是池子,配额是消费上限,池子留余量是为了召回多样性和命中率。
- **每日 Super Hi**:订阅赠送的免费 Super Hi 数。月度/年度每日 1 次,周度不送。**用完之后仍可花 100 金币购买 Super Hi**(走 payment-service 扣金币流程,不占订阅赠送配额)。
- **配额刷新**:每天 **UTC 00:00** 整点(对应美东 EST 19:00 / EDT 20:00 前一日)。本模块全 UTC 存取,展示层由 App 转用户时区。

### 3.2 划卡动作

| 动作 | 含义 | 消耗 |
|---|---|---|
| LEFT_SWIPE | 不喜欢 | 1 张卡 |
| RIGHT_SWIPE | 喜欢 | 1 张卡 + 1 次右划 |
| SUPER_HI | 不管对方喜欢与否直接配对 | 1 张卡 + 1 次右划 + (1 次订阅赠送 / 100 金币) |

**幂等**(只为防客户端网络重试 / 重复 RPC):同一 `(user_id, target_user_id)` 第二次 swipe 返回上次结果,不重复扣配额。

> **正常情况下用户根本不会触发幂等分支** ── 召回阶段已过滤 `user_swipe_history`,看过的 target 不会再出现在 feed 里(见 4.1 / 4.2.2)。所以幂等只覆盖"App 收 timeout 误重发"这种纯传输层场景。如果同一 `(user, target)` 短时间内被 server 看到 swipe 第 N>1 次且**间隔超过 RPC 重试窗口**,打 WARN 日志(疑似召回过滤失效或 App bug);**触发 ON CONFLICT 创建 match 时升级为 ERROR**(见 5.3,这才是真正的报警信号)。

## 4. 卡片队列

### 4.1 D0 冷启动队列

**目标**:新注册 / 没历史划卡数据的用户首次进入首页时有内容可看。

**设计原则(简化)**:**不预先分桶,不维护静态种子表**,与 D1 共享一套召回流程,只是"偏好来源"不同 ── D0 拿不到用户历史右划画像,就用**用户自身画像作为 prior 偏好**(年龄、颜值分、人种)。

**生成时机**:每次用户拉 feed **实时** 双池召回 + 按比例 merge。新用户首登 → 走 D0;有历史划卡的用户 → D1 已经离线生成,直接读 D1。

**两池独立召回(各目标 240,DH 池渐进扩范围,BH 池严格不放宽)**

D0 的过滤条件严了取不到 240,松了又失去"贴近用户"的语义。**DH 池采用渐进扩范围保证一定凑满 240**;**BH 池严格按最严条件,不够就不够 ── 4.2.5 的 merge 阶段 DH 自动补齐**,绝不靠放宽 BH 条件去凑 240(会引入低质量真人,体验更差)。

- **DH 池**(目标 240,**支持渐进扩范围**):调 `user-service.listDhCandidates`(支持多层条件,返回去重累积)。**永远固定排除**:
  - `gender != oppositeGender(用户 gender)` 的 DH(异性恋假设,**任何层级都不放开**)
  - `user_swipe_history` 里所有 target_user_id(任何方向都排除)

  渐进层级(每层失败补足后停):

  | Level | age 范围 | beauty 范围 | race | 说明 |
  |---|---|---|---|---|
  | L0 | 用户 ±5 | 用户 ±15 | 同人种 | 最严,理想态 |
  | L1 | 用户 ±5 | 用户 ±15 | **不限** | 放开人种 |
  | L2 | 用户 ±10 | 用户 ±25 | 不限 | 扩大年龄/颜值 |
  | L3 | 不限 | 不限 | 不限 | 兜底,只剩异性 + 排除 swipe |

- **BH 池**(目标 240,**严格条件,不放宽**):调 `user-service.nearbyUsers`,**只调一次**,固定条件:
  - `gender = oppositeGender(用户 gender)`(异性恋假设)
  - `age ∈ 用户年龄 ±5`
  - `beauty_score ∈ 用户颜值分 ±15`
  - 同人种
  - 距离 ≤ 100 km
  - 最近 7 天活跃
  - 排除 `user_swipe_history` 里所有 target_user_id
  - 排除互相拉黑

  实际数量 0~240,**不够就不够**。这是设计意图 ── 用户身边没有符合条件的优质真人时,宁愿用更多 DH 补齐(4.2.5 merge 阶段),也不放低真人质量门槛。

  阈值放 Nacos `match.cold_start.bh.*`(距离 100km / age window 5 / beauty window 15 / active days 7),运营可调,但**不**配多层 ── 单层。

  DH 池分层阈值放 Nacos `match.cold_start.levels.dh[]`,运营可调。

**池内排序**(D0 专用,**不**走 4.2.3 的 D1 打分公式 ── D0 用户没行为数据,直接走可解释的多级 demographic 排序):

**BH 池排序键**(4 级字典序):

```
1. is_new_bh(c)              desc    # c.user_type == BH AND (now - c.created_at) <= match.score.new_bh_window_days(默认 3),新人靠前
2. same_race_as_user(c)      desc    # 严格条件下都满足,作为占位保留(放宽时不用改排序代码)
3. abs(c.age - user.age)     asc     # 年龄差距小靠前
4. c.beauty_score            desc    # 颜值分高靠前
```

**DH 池排序键**(3 级字典序,DH 没有"新人"概念):

```
1. same_race_as_user(c)      desc    # 同人种优先(渐进扩范围后跨人种的候选排后面)
2. abs(c.age - user.age)     asc
3. c.beauty_score            desc
```

取各自池 top 240(BH 池 ≤240 全取)。

> **新 BH bonus 在 D0 的体现**:把"新 BH"作为最高优先级排序键(第 1 级)替代 D1 里的"加性分数",效果一致 ── 3 天内新真人永远在 BH 池最前面。运营开关复用 `match.score.new_bh_window_days`。

**运营开关 + 按比例 merge**

- Nacos key `match.cold_start.bh_ratio`(默认 `0.20`)。**与 D1 的 `match.d1.bh_ratio` 完全独立**。
- merge 算法同 D1(见 4.2.5):`target_bh = round(feed_size × bh_ratio)`,BH 不足 → DH 补齐 → feed 总数恒定。
- 两池内分别按位置取,交错插入(每 `round(1/bh_ratio)` 张 DH 间塞 1 张 BH,BH 用完后纯 DH 排到底)。

**与 D1 的核心差异**

| 维度 | D0 | D1 |
|---|---|---|
| 触发 | 每次拉 feed 实时召回 + RPUSH 到 Redis LIST | 美东 03:00 离线 DEL + RPUSH 覆盖 Redis LIST |
| 偏好来源 | 用户自身画像 (prior) | 用户 30 天右划画像分布 |
| 持久化 | 不持久,每次重算 + push Redis LIST | Redis LIST(无 PG 表持久化) |
| 适用人群 | 无划卡历史 / 昨天无划卡行为 | 昨天有划卡行为 |
| 池子上限 | DH 240 + BH 240 | DH 240 + BH 240 |
| BH 不足策略 | DH 补齐 | DH 补齐 |
| 排序键 | **4 级字典序**(新 BH → 同人种 → 年龄差 → 颜值,见 4.1 末尾) | **4.2.3 打分公式**(基于右划画像分布的连续分数) |

### 4.2 D1 日更队列

**触发**:`@Scheduled` 在 match-service 内部跑,**每日美东 03:00**(EDT 03:00 / EST 03:00 PRD 口径)。

> **时区约定**:**所有 PRD 文档、运营沟通、值班排程一律用美东时间(`America/New_York`)描述,代码 cron 一律用 UTC 表达式**。本节及后续提到的时间都是美东。代码侧 `@Scheduled(cron = "0 0 7 * * *", zone = "UTC")` 在 DST 期间会比 PRD 描述早 1 小时(美东 02:00 而非 03:00),这是 trade-off:用固定 UTC 避免冬令时切换日漂移,牺牲半年期 1 小时偏移。运营如需精确美东 03:00,改为 `zone = "America/New_York"` 让 Spring 处理 DST 切换。本文档**默认采用固定 UTC**(参考下文 7.5)。

**前置条件**:用户**昨天**有过任何划卡行为(查 `user_swipe_history WHERE swiped_at >= yesterday_start AND user_id = ?`)。否则跳过这个用户的 D1,继续延用 D0 / 上一份 D1。

**容量**:**240 张/人**(D1 队列固定容量,与订阅档位无关)。订阅档位决定**当日可消费上限**(50/80/120/120,见 3.1 配额表),队列容量大于消费上限,留余量给推荐多样性 + 适应用户跳卡片节奏。

**推荐逻辑**(两池独立召回 → 池内排序 → 按比例 merge):

#### 4.2.1 偏好建模

从最近 30 天 `user_swipe_history` 聚合该用户的右划画像分布:

```
preference = {
    age_mean:     avg(target.age) of right-swiped,
    age_std:      std(target.age) of right-swiped,
    beauty_mean:  avg(target.beauty_score),
    beauty_std:   std(target.beauty_score),
    race_dist:    {Asian: 0.4, White: 0.5, Latino: 0.1},   # 各人种占比
    dh_bh_ratio:  right-swiped DH / right-swiped (DH + BH),
}
```

样本数 < 10 张时回退用 D0 偏好(用户自身画像作为 prior),避免噪声主导。

#### 4.2.2 两池独立召回(各目标 240)

D1 与 D0 共用同一套召回流程,**只在偏好来源不同**。两池始终独立,不混在一起召回。

**DH 池**(目标 240):
- 调 `user-service.listDhCandidates`,入参含异性别 + 年龄/颜值/人种宽过滤 + `exclude_user_ids`(从 `user_swipe_history` 取所有该用户的 target_user_id,任何方向都排除)。
- 按 4.2.3 的打分公式在 service 层排序,取 top 240。
- DH 池量级充足,基本能稳定取满 240。

**BH 池**(目标 240):
- 调 `user-service.nearbyUsers` 拉地理候选(`radius_km=200`,`last_active_within_days=7`),入参同样含 `exclude_user_ids`(`user_swipe_history` 所有方向 + 互相拉黑名单)。
- service 层进一步排除条件:
  1. 同性别(异性恋假设,全平台一致)
- **只召回 7 天内登录的 BH**(由 `last_active_within_days=7` 在 user-service 端硬过滤,不在 match-service 内存里二次筛 ── 避免 RPC 拉一堆不活跃数据)。
- 按 4.2.3 的打分公式排序,取 top 240(**不足则全取**,merge 阶段 DH 补齐)。

> **过滤"已 swipe"的统一性**:D0 与 D1 召回时,排除条件完全一致 ── 用户对 target **已 LEFT / RIGHT / SUPER_HI 中任一动作过** 都不再出现。`user_swipe_history` 是唯一权威源。LIST 内的"已下发但未 swipe"卡片由 LPOP 即弹出保证不重复,不需要单独的 seen SET。

#### 4.2.3 池内打分

> **仅用于 D1**。D0 没有用户右划画像,改走 4.1 末尾的"4 级字典序"硬排序(新 BH → 同人种 → 年龄差 → 颜值)。这里的打分公式只覆盖 D1 召回结果。

对每个候选 c,score(BH/DH 只有部分项差异):

```
S(c) = base_score(c) + mutual_like_bonus(c) + new_bh_bonus(c)

base_score(c) =
       0.45 * preference_similarity(c, user_pref)     # 偏好相似度(权重和 1.0 的主体)
     + 0.30 * normalize(c.beauty_score)               # 颜值分 0-1 归一化
     + 0.15 * distance_decay(c, user)                 # exp(-d/50km);DH 无距离,该项固定 0.5
     + 0.10 * activity_score(c)                       # BH: exp(-(now - last_active_at) 天数 / 7),越近上线越高;DH 固定 0.5(中性),不参与活跃度比较
```

**两个加性 bonus**(不参与 base 权重和,直接叠加在总分上,显著拉升信号):

```
mutual_like_bonus(c) = match.score.mutual_like_bonus           # 默认 +0.20
                       if c.user_type == BH
                          AND c 曾对当前用户做过 RIGHT_SWIPE / SUPER_HI 动作(查 user_swipe_history 反查)
                       else 0

new_bh_bonus(c)      = match.score.new_bh_bonus                # 默认 +0.20(本轮 0.15 → 0.20)
                       if c.user_type == BH
                          AND (now - c.created_at) <= match.score.new_bh_window_days   # 默认 3 天
                       else 0
```

- 两个 bonus **仅作用于 BH**;DH 永远 0。
- 同一张 BH 可以**同时**触发两个 bonus(新人 + 对方喜欢过我 = +0.40),最大化曝光。
- 设计理由:
  - `mutual_like_bonus`:对方右划过我说明明确互相感兴趣,匹配命中率高,优先推上去能直接产出 BH-BH 互划 match。
  - `new_bh_bonus`:新 BH 缺历史信号(没人对她右划过、活跃天数少),base 分先天低,加性 bonus 抵消系统偏差。

`preference_similarity`:age 取高斯 PDF(`|c.age - pref.age_mean| / pref.age_std`),beauty 同理,race 取 `race_dist[c.race]`。三项乘积。

权重 0.45/0.30/0.15/0.10 + 两个 bonus +0.20/+0.20 是经验值,**全部走 Nacos 配置**(`match.score.bh_weights` / `match.score.dh_weights` / `match.score.mutual_like_bonus` / `match.score.new_bh_bonus` / `match.score.new_bh_window_days`),运营可调。

#### 4.2.4 取 top 240(不做重排序)

每个池子内**直接按 4.2.3 的 score 降序排序,取 top 240**。

- **不**做 MMR 多样性重排。
- **不**做 ε-greedy 探索(理由:打分公式里已经有 `preference_similarity` 高斯软约束,样本不足时会自然回退到 prior;并且 D1 队列容量 240 远大于配额 50/80/120,本身就有足够采样面)。

每个池子输出一个排好序的 ≤240 长候选队列:`DH_queue` 和 `BH_queue`,直接进入 4.2.5 merge。

#### 4.2.5 按比例 merge(独立运营开关 + 可选个性化偏移 + DH 补齐)

**比例公式**(两层):

```
final_bh_ratio = clamp(
    L1 运营基础比例 (match.d1.bh_ratio, 默认 0.40)
    + L2 个性化偏移 (基于用户 30 天右划 dh_bh_ratio, 上限 ±match.d1.preference_offset, 默认 ±0.20),
  0, 1)
```

**L1 运营开关**(全局)
- Nacos key:`match.d1.bh_ratio`,默认 `0.40`(D1 队列 40% 真人 / 60% DH)。
- 与 D0 的 `match.cold_start.bh_ratio` **完全独立**,运营可分别调:早期 D0 真人少(0.10),D1 已经积累互动数据可以多放真人(0.50)。

**L2 个性化偏移**(可选,默认开)
- Nacos 开关 `match.d1.preference_enabled`,默认 `true`。关掉后所有用户都用 L1 固定比例。
- 偏移量 = `(0.5 - user.dh_bh_ratio) × match.d1.preference_offset × 2`,clamp 到 `[-offset, +offset]`。
  - 用户最近 30 天右划全是 BH(`dh_bh_ratio = 0.0`)→ 偏移 +0.20(final BH 比例 = 0.60)
  - 用户右划全是 DH(`dh_bh_ratio = 1.0`)→ 偏移 -0.20(final BH 比例 = 0.20)
  - 样本数 < 10 张时不偏移(L2 退化为 0)

**Merge 算法**(D0 / D1 共用):

```
target_bh = round(240 × final_bh_ratio)
target_dh = 240 - target_bh

actual_bh = min(target_bh, len(BH_queue))    # BH 不足时,按实际池子大小封顶
short     = target_bh - actual_bh            # 缺口
actual_dh = target_dh + short                # 全部用 DH 顶替缺口

# 从 BH_queue 取前 actual_bh 张,DH_queue 取前 actual_dh 张,交错插入
result = interleave(BH_queue[:actual_bh], DH_queue[:actual_dh])
```

**关键不变量**:
- 队列容量恒 240 张,**绝不缩短**。
- BH 优先用足,DH 永远当兜底(DH 池假设充足)。
- 监控:`match.d1.bh_short_ratio` = short / target_bh,持续 > 30% 触发报警(说明 BH 池跟不上运营比例,需要加大召回半径或降低 bh_ratio)。

> **没有"D1 失败兜底队列"**:D1 cron 失败 / 数据缺失,Redis LIST 不被覆盖,继续消费旧队列;旧队列耗尽 → GetTodayFeed 触发实时重建(走 4.1 D0 召回流程)。详见 4.3。

### 4.3 队列消费(LPOP 弹出 + 二次过滤 + 空了自动重建)

**Redis 数据结构**:
- `match:feed:<user_id>` 用 **LIST**(每条 = `"<target_user_id>:<target_user_type>"`,冒号分隔),TTL 7 天。
- `match:swiped:<user_id>` 用 **SET**(永久),装该用户**所有已 swipe 过的 target_user_id**,由 swipe 接口同步 SADD(见 7.6)。作为消费阶段二次过滤的快速 lookup。`user_swipe_history` 仍是权威源,Redis SET 是缓存,丢了可以从 PG 重建。

`GetTodayFeed(user_id, count=5)`(移动端固定每次拉 5 张):

```
1. 检查当日 swipe 配额(cards_used >= card_limit) → 是 → 返回 exhausted=true, cards=[]
2. need = min(count, card_limit - cards_used)
3. result = []
4. while len(result) < need:
     batch = LPOP("match:feed:<user_id>", need - len(result))   # Redis 7.0 LPOP 支持 count
     if len(batch) == 0:                                         # LIST 已空
         ColdStartService.buildAndPush(user_id)                  # 实时双池召回 → RPUSH 240 张
         batch = LPOP("match:feed:<user_id>", need - len(result))
         if len(batch) == 0:                                     # 重建后还是空,极端兜底
             break
     # 二次过滤:LPOP 出来的卡片再校验一次是否已 swipe
     target_ids = [extract_id(c) for c in batch]
     swiped_hits = SMISMEMBER("match:swiped:<user_id>", target_ids)   # 批量
     result += [c for c, hit in zip(batch, swiped_hits) if not hit]
     # 命中过滤的卡片直接丢弃,继续 while 凑足 need
5. 调 user-service.batchGetProfile 拼装 Card VO(nickname / age / photo_keys / distance)
6. 返回 result
```

**为什么消费阶段还要二次过滤**:
- 召回阶段(4.1 / 4.2.2)的 `exclude_user_ids` 是召回**生成 LIST 那一刻**的快照。卡片在 LIST 里躺着的这段时间(可能跨越分钟到小时),用户可能在另一台设备 / 另一次请求里已经 swipe 过同一 target(并发场景 / 队列重建间隔)。LPOP 出来还原样推给用户就会"刚划过又看到"。
- 二次过滤用 Redis SET SMISMEMBER 是 O(1) lookup,批量 5 个不到 1ms,代价可忽略。
- **命中过滤的卡片直接扔掉,不放回 LIST**(它确实不该再出现);通过 while 循环继续 LPOP 凑足 `need`。

**关键点**:
- **LPOP 即消费**,LIST 内不可能重复;消费阶段的二次过滤覆盖"召回快照过时"的边界 case。
- **LPOP 不消耗 swipe 配额**,只消耗 LIST 库存。配额由 SwipeCard / SuperHi 时扣减(见 7.6)。
- **被二次过滤丢弃的卡片不算 LIST 库存消耗**,但确实从 LIST 永久弹出了。这是接受的代价(极少发生)。
- **队列空 → 自动重建**:不区分"D1 cron 没跑成功"还是"用户活跃消费完了",都走同一个 `ColdStartService.buildAndPush`(就是 4.1 描述的实时双池召回 + merge)。重建后立即继续 LPOP,对 App 透明。
- **没有"兜底队列"概念**:D1 cron 失败 → Redis LIST 不被覆盖 → 旧队列继续消费;旧队列消费完 → 实时重建。整条链路没有"保留昨天队列"的特殊兜底逻辑。

**何时 RPUSH 写入 LIST**:
1. D1 cron(美东 03:00)生成后 → `DEL` + `RPUSH 240` 覆盖式重写(命中前提:用户昨天有划卡)。
2. `ColdStartService.buildAndPush` 触发时(LIST 空 / 新用户首登)→ `RPUSH` 追加,不 DEL(允许多次累积)。
3. 不再写 PG `user_daily_queue` 表(已删除,见 7.2)。Redis LIST 是唯一存储。

## 5. 匹配触发规则

### 5.1 匹配矩阵

| 我 swipe | 对方类型 | 对方对我状态 | 我使用 Super Hi | 触发结果 | match.source |
|---|---|---|---|---|---|
| RIGHT | BH | 已右划过我 | No | **立即** match,im-service 建会话 | SWIPE_MATCH |
| RIGHT | BH | 未划过 / 左划过我 | No | 仅写 swipe 历史,无 match | ─ |
| RIGHT | DH | - | No | **延迟** match(15s ~ 2min 后,Spring TaskScheduler 内存调度) | SWIPE_MATCH |
| SUPER_HI | BH | - | Yes | **立即** match,无视对方意愿(对方收到 system 消息"X 用 Super Hi 喜欢了你") | SWIPE_SUPER_HI |
| SUPER_HI | DH | - | Yes | **立即** match,im-service 立刻触发 ai-chat 开场白 | SWIPE_SUPER_HI |
| LEFT | * | * | No | 仅写 swipe 历史,后续 24h 内不再推该用户 | ─ |

> source 与 BH/DH 正交,见 7.2 match 表 source 枚举说明。BH/DH 维度需要时由 user_id 反查 `user-service.user_type`。

### 5.2 DH 延迟匹配设计

**核心问题**:用户右划 DH 后,如果一秒内 DH 就来打招呼,用户立刻意识到是 bot。但等太久(>分钟级)用户已经划下一张了,失去"她也喜欢我"的即时反馈。

**适用范围**:仅普通右划 DH 走延迟。**Super Hi 对 DH 立即匹配**(5.1 已确认),理由是用户付了费就期望立即看到结果,产品意图覆盖"防穿帮"。

**延迟窗口**:**15 秒 ~ 2 分钟**,均匀随机分布。15s 下限保证"不是一秒回应",2min 上限保证用户还在 App 内就能感知到。

**实现:进程内 Spring `TaskScheduler.schedule()`**

```java
@Component
public class DhDelayedMatchService {

    private final TaskScheduler scheduler;       // 注入 Spring 自带的 ThreadPoolTaskScheduler
    private final MatchService matchService;

    /** swipe RIGHT 命中 DH 时调用,挂一个 15s-2min 后的回调。*/
    public void scheduleDelayedMatch(long userId, long dhId) {
        long delayMs = ThreadLocalRandom.current().nextLong(15_000, 120_001);
        scheduler.schedule(
            () -> matchService.createMatch(userId, dhId, MatchSource.SWIPE_MATCH),
            Instant.now().plusMillis(delayMs)
        );
    }
}
```

`MatchService.createMatch` 走 5.3 的统一流程:写 `match` 表 + 写 `match_outbox` 触发 IM 副作用(建会话 / 系统消息 / ai-chat 开场白)。

**为什么进程内调度而不是 PG 表 + cron / Redis ZSET**:
- 窗口只有 15s-2min,**不值得**为它建一张 PG 表 + 定时扫表 worker。
- `ScheduledExecutorService`(Spring TaskScheduler 底层)在内存里维护小顶堆,μs 级精度,容量轻松撑 10k+ 待执行任务。
- 量级评估:10k DAU × 5 右划/天 × 50% DH = 25k/天 ≈ 0.3/秒,任何时刻 in-flight 任务 < 600(2min 窗口),内存可忽略。

**重启丢任务的取舍**:服务重启 / 部署时,内存里的待执行任务会丢失 ── 这些用户的 DH 匹配永远不会触发。**接受这个 trade-off**,理由:
- 重启窗口本身极短,落在 15s-2min 内的概率很低。
- 即使丢了,用户感知是"我右划了她但她没喜欢我",符合 dating app 常见预期(不是每个右划都会被对方喜欢)。
- 不引入持久化兜底(原 `pending_dh_match` 表已删,不做启动重扫 `user_swipe_history` 回放)。如未来真有运营要求,再加 outbox 风格的兜底。

**Super Hi + DH 不走这里**:直接走 5.3 立即 match 流程,im-service 即刻触发 ai-chat 开场白。

### 5.3 Match 创建副作用

每次创建 match 都要:
1. **本地 PG 事务**(原子,跨服务 RPC 不入事务):
   1. INSERT `match` 表(主键 `(min(uid1,uid2), max(uid1,uid2))` 保证幂等)。
   2. `DELETE FROM like_record WHERE (from_user_id, to_user_id) IN ((low, high), (high, low))` —— 双方关系升级为 match,原有的"暗恋"like_record 失去意义。SUPER_HI / 互划即时 match 路径**本就没写过 like_record**,DELETE 是 no-op;延迟 DH match 也无 like_record(DH 计划只生成给"未匹配"用户)。这一步主要清理"A 单向 like B → B 后来也 RIGHT_SWIPE A 触发互划"的 like_record(A→B)。
2. **远程副作用**(失败 → 写 `match_outbox`,后台 retry):
   1. `im-service.EnsureConversation(user_id_1, user_id_2)` 建 IM 会话。
   2. `im-service.SendSystemMessage` 给双方各发一条系统消息("你们配对了")。
   3. DH 端额外触发 ai-chat 生成开场白(走 im-service)。

跨服务 RPC 失败 **不**让用户 UX 看到失败,outbox 异步兜底重试。

**重复 match 防御**:同一对 `(user_id_low, user_id_high)` 不应该出现第二次 match,因为:

1. 召回阶段已过滤 `user_swipe_history`,任何 LEFT/RIGHT/SUPER_HI 过的对方都不会再出现在 feed 里(见 4.1 / 4.2.2)。
2. swipe 接口本身幂等(同 user/target 第二次返回上次结果,见 7.6)。

但仍要做防御性兜底,避免召回过滤 bug / 并发竞态把已 matched 的 target 重新推给用户、用户再次 RIGHT swipe 触发 createMatch:

```java
// MatchService.createMatch
int rows = matchMapper.insertIgnoreConflict(low, high, source);
if (rows == 0) {
    // 已存在,说明上游过滤失效。打 error 日志带 user_id_low / user_id_high / source / caller_stack
    Match existing = matchMapper.selectByPair(low, high);
    log.error("Duplicate match attempt: pair=({}, {}) existing_id={} existing_source={} new_source={} ──"
              + " 上游召回过滤可能存在 bug,排查 user_swipe_history 与召回 exclude_user_ids 链路",
              low, high, existing.getId(), existing.getSource(), source);
    return existing;        // 对调用方仍然语义正确,返回已存在的 match
}
```

**关键**:不抛异常给调用方(swipe RPC),只打 error 日志 + 返回 existing match。这样 App 端 UX 不会因为 bug 看到错误,但可观测性留住了 ── 出现这类日志说明召回链路有缺陷,需要排查。

## 6. Like / Visit 互动记录

### 6.1 业务规则

每个用户能在 App 内看到两份列表:
- **Likes of me**:谁 right-swipe / Super Hi 喜欢了我(双方未配对前的"暗恋"状态)
- **Visits of me**:谁访问过我的主页

平台两类用户 (BH/DH) 对外**用户视角一致** —— App 不区分,展示成"某某 like 了你 / 访问了你"。区分仅落在数据库 `source` 字段,用于运营埋点和反向追溯。

**两个来源**:
- **真实路径**(只有 BH):用户右划 / Super Hi / 点击别人主页时产生
- **DH 模拟**(只有 DH→BH):后台调度生成,营造"被关注感"提高留存。**DH 不接收互动**(DH 不打开 App,生成无意义)

**为什么放在 match-service**:
- like 数据本质上是 swipe 行为的副产物(BH 右划 → 自动落 like_record)
- visit 与推荐 / 配对处于同一域(都是用户对用户的兴趣信号)
- 共用 `user-service.listDhCandidates` 召回 DH 候选,共用 D0/D1 召回的过滤规则(异性恋假设、年龄/颜值/人种约束)
- 不引入新服务、不开新表前缀

### 6.2 真人触发路径

| 真人动作 | 落 like_record | 落 visit_record |
|---|---|---|
| RIGHT_SWIPE(不触发 match) | ✓ source=SWIPE_RIGHT | ✗ |
| RIGHT_SWIPE(触发 BH 互划 match) | ✗ —— 直接进 match 表,不是"暗恋"状态 | ✗ |
| SUPER_HI(对 BH / 对 DH) | ✗ —— 立即 match,不需要 like_record 中转 | ✗ |
| LEFT_SWIPE | ✗ | ✗ |
| 点击别人主页 / 卡片放大查看 | ✗ | ✓ source=PROFILE_VIEW |

**核心语义**:`like_record` 只存"**单向未回应的喜欢**"。一旦两边形成 match,就由 `match` 表承载关系,like_record 阶段性使命结束。SUPER_HI / 互划即时 match 跳过 like_record,本就不存在"暗恋"窗口。

**落库时机**:
- `SwipeService.swipe(RIGHT_SWIPE)`:在写 `user_swipe_history` 的**同一事务**里,**判断对方是否已右划过我**:
  - 否(单向) → UPSERT `like_record(source=SWIPE_RIGHT)`,对方进我的"Likes of me"
  - 是(互划 → 触发 match) → 走 5.3 createMatch 流程,**不**写 like_record
- `SwipeService.superHi()`:直接走 5.3 createMatch(BH 立即 / DH 立即,见 5.1 矩阵),**不**写 like_record
- mobile-gateway 接到用户访问主页请求时调 `match.RecordVisit(viewer_user_id, target_user_id)` gRPC,服务端**异步**(独立线程池)UPSERT `visit_record`,UPSERT 同 (from, to) 累加 `visit_count` + 更新 `visited_at`

**清理已失效的 like_record**:对方对我做了 RIGHT_SWIPE / SUPER_HI 触发 match 时,如果我之前对她有一条单向 like_record(我→她),那条 like_record 就该从"她的 Likes of me"消失。**MatchService.createMatch 在 5.3 副作用列表里追加一步**:`DELETE FROM like_record WHERE (from, to) IN ((low, high), (high, low))`。同事务执行,失败回滚 match 创建。

**关键约束**:
- 自访问短路:`viewer_user_id == target_user_id` 直接返回 ok,不落库
- DH 不主动调 RecordVisit(由 mobile-gateway 在 controller 层按 user_type 拦截)
- visit 写入失败不阻塞主流程,只打 WARN 日志(列表少几条不影响产品)

### 6.3 DH 模拟计划(三个调度任务)

核心目标:让新注册 / 长期没真人互动的用户也有 like/visit 数据可看。三个 scheduler 分工:

| Scheduler | 频率 | 选人来源 | 输出 |
|---|---|---|---|
| OnlinePlanGenerator | 每 1 分钟 | `im-service.ListOnlineUserIds(sinceMs, untilMs)` | dh_interaction_task 行 (scene=ONLINE) |
| OfflinePlanGenerator | 每 20 分钟 | `im-service.ListRecentOfflineUsers(sinceMs, untilMs)` | dh_interaction_task 行 (scene=OFFLINE) |
| LikeVisitorTaskExecutor | 每 1 分钟 | dh_interaction_task WHERE execute_time ≤ now | UPSERT like_record / visit_record,删任务行 |

> **依赖**:在线/离线信号源**统一由 im-service 提供**,无单独心跳接口。OpenIM 通过 `callbackUserOnlineCommand` / `callbackUserOfflineCommand` 把每个真人用户的上下线事件打到 im-service,im-service 同时维护两份数据:Redis ZSet `im:presence:online`(member = userId,score = 上线 epoch ms,ZADD NX 写入)与 PG `user_online_session`(online_at / offline_at / duration_seconds)。match-service 走两个 gRPC 拉取(详见 7.7):**`ListOnlineUserIds` 读 ZSet**(本周期内新建会话的 BH 用户),**`ListRecentOfflineUsers` 读 user_online_session**(本周期内已收口的下线会话)。**DH 用户不会出现在 OpenIM 回调里**(DH 没有 IM 设备登录),所以两个接口天然只返回 BH,7.7 的"类型闸"省一道。**完全废弃 user-service Heartbeat 链路 / `user:online:rank` ZSet / `/v1/online/heartbeat` REST 端点 / `last_open_at` 节流写库 / nightly `ZREMRANGEBYSCORE`**,移动端不再上报心跳。

#### 6.3.1 OnlinePlanGenerator(每分钟)

> **意图**:用户正在 App 内,每隔十几分钟收到一条 DH like / visit 通知,营造"涓涓细流"的关注感。

**a. 选人**:`im-service.ListOnlineUserIds(sinceMs = cursor, untilMs = now, limit = 5000)`
- im-service 内部执行 `ZRANGEBYSCORE im:presence:online cursor now LIMIT 0 5000`,返回本周期内**新建立 OpenIM 会话**的真人 userIds(`im:presence:online` 的 score 是 `online_at`,ZADD NX 写入,会话期间不变,因此该窗口只覆盖**新上线**的用户,不包含已经在线的存量)
- 游标 `match:dh_plan:cursor:online` STRING,初始为 `now - 1min`
- 最大回看 30 分钟,超过 → **告警 + 重置 cursor = now - 1min**(防止游标长期不动堆积扫不动)
- **与旧 user:online:rank 心跳方案的语义差**:旧方案 score 每 20s 被心跳推高,同一用户在长在线会话内每过完 2h cooldown 就会被反复扫到,生成多条 ONLINE 计划;新方案 score 在 OpenIM 上线时一次性写入,**单次会话只命中一次 ONLINE 计划**(用户断开重连会拿到新的 online_at,再次落入窗口)。产品上接受:ONLINE 计划的本意是"刚进 App 后 30min 内陆陆续续收到一波 drip",长时间在线已在产品内活跃,不再需要 drip。

**b. 游标推进**:RPC 返回完成立即 `SET match:dh_plan:cursor:online = now`,同一用户一个 sweep 内只处理一次。

**c. 过滤**(三道闸):
1. **Cooldown**:`EXISTS match:dh_plan:cooldown:<user_id>` —— 在 cooldown 期内跳过(默认 2h,见 6.4)
2. **任务表去重**:`SELECT 1 FROM dh_interaction_task WHERE to_user_id = ? AND scene = ONLINE LIMIT 1` —— 已有未执行的 ONLINE 任务跳过
3. **类型闸**:to_user_id 必须是 BH(DH 不接收互动)

**d. 生成**:对每个通过过滤的 BH 用户:
- 调 `user-service.listDhCandidates`,入参用该用户偏好(D0 渐进扩范围 L0~L2 任一层即可),取 `rand(5, 10)` 个 DH —— **绝不一次塞 30 个,防穿帮**
- `exclude_user_ids` = 该 BH 已被 like_record / visit_record 关联过、或已与之 match 的所有 DH(避免同一 DH 反复出现,以及给已经"配对成功"的 DH 再补无意义的 like/visit)
- 按 60%/40% 比例分配 action:60% VISIT / 40% LIKE(visit 比 like 更日常,符合真人行为)
- `execute_time` 在 `[now, now + 30min]` 内**均匀随机分布** —— 关键:**不能密集**,让 task 在窗口内陆陆续续触发,模拟真人持续操作的节奏
- LIKE 任务的 `like_content` 从 Nacos `match.dh_plan.like_content_templates` 列表里按目标性别筛后随机抽

**e. 收尾**:
- 批量 INSERT 任务行到 `dh_interaction_task`
- 成功用户批量 `SET match:dh_plan:cooldown:<user_id> 1 EX 7200`(2h cooldown)
- 写 `SET match:dh_plan:last_scene:<user_id> ONLINE`(供 OfflinePlanGenerator 检查)

#### 6.3.2 OfflinePlanGenerator(每 20 分钟)

> **意图**:用户已离线 20 分钟+,给一波集中的 DH 互动,用户下次打开 App 时看到"我不在的时候有人喜欢/访问了我"。

**a. 选人**:`im-service.ListRecentOfflineUsers(sinceMs = minLowerBound, untilMs = now - offlineThresholdMs, limit = 5000)`
- im-service 内部:`SELECT user_id FROM user_online_session WHERE offline_at >= :since AND offline_at < :until AND deleted = false ORDER BY offline_at LIMIT :limit`(走新增的 partial index `(offline_at) WHERE offline_at IS NOT NULL`,见 7.7)
- `minLowerBound = max(cursor, now - 3h)` —— 硬底,防止扫太远历史
- `offlineThresholdMs = 20min` —— 真正离线满 20 分钟才算"离线用户"(刚下线不抓,避免和 OnlinePlanGenerator 抢用户)
- 游标 `match:dh_plan:cursor:offline` STRING,初始为 `now - 3h`
- **与旧 user:online:rank 方案的语义差**:旧方案 ZRANGEBYSCORE 拉到的是"最后心跳落在区间内"的用户(对网络抖动假离线敏感);新方案直接读 OpenIM 真实下线回调落库的 `offline_at`,语义更准。`user_online_session` 的孤儿(只上线没下线)由 im-service 自己定时清扫(`PresenceSweepJob`,详见 `im-service` 实现),封顶后 `offline_at = online_at + 阈值` 也会出现在本接口结果里。

**b. 游标推进**:本轮结束后 `SET match:dh_plan:cursor:offline = now - 20min` —— 留 20 分钟重叠窗口,保证长期离线的用户能被反复评估(直到打中 lastScene 闸为止)。

**c. 过滤**(三道闸,**没有 cooldown 检查**):
1. **lastScene 闸**:`GET match:dh_plan:last_scene:<user_id> != "OFFLINE"` —— 本次离线期内已经生成过 OFFLINE 计划的跳过(单次离线期最多 1 个 OFFLINE 计划)
2. **任务表去重**:`SELECT 1 FROM dh_interaction_task WHERE to_user_id = ? AND scene = OFFLINE LIMIT 1`
3. **类型闸**:同 6.3.1

**d. 生成**:
- 调 `user-service.listDhCandidates` 取 `rand(3, 6)` 个 DH(比 ONLINE 少 —— 离线一段时间收一波不要夸张)
- `exclude_user_ids` 同 6.3.1(like_record / visit_record / match 三路 UNION)
- 同 60%/40% VISIT/LIKE 比例
- `execute_time` 在 `[now, now + 30min]` 内均匀随机分布(执行完用户来开 App 就能看到一批"新鲜"互动)

**e. 收尾**:
- 批量 INSERT `dh_interaction_task`
- **不**写 cooldown
- 写 `SET match:dh_plan:last_scene:<user_id> OFFLINE` —— 用户重新上线后被 OnlinePlanGenerator 处理时会把 last_scene 改回 ONLINE,下次再离线满 20 分钟才会被 OfflinePlanGenerator 再次抓到

#### 6.3.3 LikeVisitorTaskExecutor(每分钟)

**扫描**:`SELECT * FROM dh_interaction_task WHERE execute_time <= now() ORDER BY execute_time LIMIT 1000`

**执行**:对每条任务用一个**独立短事务**:
- `action = LIKE` → UPSERT `like_record (from_user_id=DH, to_user_id=BH, from_user_type=DH, source=DH_PLAN_ONLINE|OFFLINE, like_content=...)`,ON CONFLICT 更新 `liked_at + source`
- `action = VISIT` → UPSERT `visit_record` 同理,`visit_count` += 1
- 成功后 `DELETE FROM dh_interaction_task WHERE id = ?`(单条任务硬删,不软删 —— 任务表是短生命周期数据,留软删 + 30 天归档没有审计价值)

**失败处理**:UPSERT 失败 → 不删任务行,下一轮继续重试。同一任务连续失败 N 次的报警放后续运营工具阶段做,MVP 不做。

**速率控制**:每分钟最多 1000 条,够 100k DAU × 5 任务/天的吞吐。超量积压(`COUNT(*) WHERE execute_time <= now() - 5min`)打 WARN,运营查 DH 池规模 / scheduler 健康。

### 6.4 真实感约束

防止 DH 模拟被用户识破的几道闸,全部走 Nacos 可调:

| 维度 | 约束 | Nacos key |
|---|---|---|
| 单次 ONLINE 计划生成的 DH 数 | 5~10 张(随机) | `match.dh_plan.online_count_range = [5,10]` |
| 单次 OFFLINE 计划生成的 DH 数 | 3~6 张(随机) | `match.dh_plan.offline_count_range = [3,6]` |
| ONLINE cooldown | 2h | `match.dh_plan.online_cooldown_seconds = 7200` |
| ONLINE 离线阈值(进 Offline 池的最小静默时长) | 20 min | `match.dh_plan.offline_threshold_seconds = 1200` |
| OFFLINE 最大回看 | 3h | `match.dh_plan.offline_lookback_seconds = 10800` |
| ONLINE execute_time 分布窗口 | now ~ now+30 min | `match.dh_plan.online_execute_window_min = 30` |
| OFFLINE execute_time 分布窗口 | now ~ now+30 min | `match.dh_plan.offline_execute_window_min = 30` |
| VISIT/LIKE 比例 | 60% / 40% | `match.dh_plan.visit_ratio = 0.6` |
| like_content 模板池 | JSON 列表 `[{content, gender_pref}]` | `match.dh_plan.like_content_templates` |
| 单 BH 24h 内 DH like 上限 | 15 条 | `match.dh_plan.daily_dh_like_cap = 15` |
| 单 BH 24h 内 DH visit 上限 | 25 条 | `match.dh_plan.daily_dh_visit_cap = 25` |

24h 上限在 generator 阶段检查:`SELECT COUNT(*) FROM like_record WHERE to_user_id = ? AND from_user_type = DH AND liked_at >= now() - interval '24h'`,超了就**减少本轮生成数**或干脆跳过。

**还有两点防穿帮**:
1. **同一 DH 不反复 like 同一 BH**:`like_record` UNIQUE`(from, to)` 天然兜底,UPSERT 不会重复落新行;但展示层只看"最新一次",所以反复 UPSERT 也无意义,generator 阶段已通过 `exclude_user_ids` 排除
2. **DH 候选避免重复抽 / 不抽已 match 的人**:listDhCandidates 入参
   ```sql
   exclude_user_ids = (SELECT from_user_id FROM like_record   WHERE to_user_id = ? AND deleted = false)
                UNION (SELECT from_user_id FROM visit_record  WHERE to_user_id = ? AND deleted = false)
                UNION (SELECT CASE WHEN user_id_low = ? THEN user_id_high ELSE user_id_low END
                         FROM match
                         WHERE (user_id_low = ? OR user_id_high = ?) AND deleted = false)
   ```
   理由:已经 like / visit 过的 DH 再次出现会显得密集刷;已经 match 的 DH 走 DH 计划再"暗送秋波"逻辑割裂(我们俩已经在聊了,她为什么还 like / 访问我?)。

### 6.5 接口语义

**ListLikesOfMe**:
- 入参:`user_id, page_size (≤ 50), page_token`
- 返回:按 `liked_at DESC` 分页,每条含 liker 的 `target_user_id / nickname / age / photo_keys / liked_at_unix_ms`,**不下发 from_user_type / source**(对外屏蔽 BH/DH)
- 内部:`SELECT * FROM like_record WHERE to_user_id=? AND deleted=false ORDER BY liked_at DESC LIMIT N OFFSET M`,service 层批量 `user-service.batchGetProfile(from_user_ids)` 拼装 VO

**ListVisitsOfMe**:语义同 ListLikesOfMe,读 `visit_record`,排序 `visited_at DESC`,额外返回 `visit_count`(同人多次访问累积值)。

**RecordVisit**:
- 入参:`viewer_user_id, target_user_id`
- 行为:UPSERT `visit_record`(自访问短路);**异步**线程池写入(线程池满拒绝时仅 WARN 日志,不阻塞调用方)
- 返回:`ok` 表示已收;不等待落库

gRPC proto 详见 7.4,表结构详见 7.2。

---

## 7. 技术设计

### 7.1 服务结构

```
match-service/                 # 新仓库目录,Spring Boot 3.3.5 + Java 21
├── Dockerfile
├── pom.xml
└── src/main/java/com/dating/match/
    ├── controller/            # REST 入口(mobile-gateway 调)
    ├── grpc/                  # gRPC 入口
    │   └── MatchGrpcService
    ├── service/
    │   ├── FeedService        # GetTodayFeed
    │   ├── SwipeService       # SwipeCard, SuperHi(写 user_swipe_history 同事务 UPSERT like_record)
    │   ├── MatchService       # 创建 match + 副作用
    │   ├── DhDelayedMatchService  # 进程内 15s-2min TaskScheduler 延迟
    │   ├── QuotaService       # 配额查/扣
    │   ├── ColdStartService   # D0 实时召回 + merge(调 user-service.listDhCandidates / nearbyUsers)
    │   └── LikeVisitService   # ListLikesOfMe / ListVisitsOfMe / RecordVisit(见 6.5)
    ├── recommend/
    │   ├── D1Generator        # 每日凌晨 cron
    │   ├── PreferenceBuilder  # 偏好建模
    │   ├── CandidateRecaller  # DH/BH 召回
    │   ├── Ranker             # 打分
    │   └── (无重排序模块,直接按 score 取 top 240,见 4.2.4)
    ├── scheduler/
    │   ├── D1QueueScheduler             # @Scheduled cron = "0 0 7 * * *" UTC
    │   ├── MatchOutboxRetry             # @Scheduled fixedDelay = 30_000
    │   ├── OnlinePlanGenerator          # @Scheduled fixedDelay = 60_000(见 6.3.1)
    │   ├── OfflinePlanGenerator         # @Scheduled fixedDelay = 1_200_000(见 6.3.2)
    │   └── LikeVisitorTaskExecutor      # @Scheduled fixedDelay = 60_000(见 6.3.3)
    ├── manager/
    │   ├── QuotaManager
    │   ├── SwipeHistoryManager
    │   ├── DailyQueueManager
    │   ├── MatchManager
    │   ├── LikeRecordManager            # like_record CRUD + UPSERT
    │   ├── VisitRecordManager           # visit_record CRUD + UPSERT
    │   └── DhInteractionTaskManager     # dh_interaction_task 批量写 + 扫描 + 删除
    ├── mapper/                # MyBatis-Plus
    ├── entity/                # @TableLogic deleted
    ├── client/
    │   ├── UserServiceClient
    │   ├── PaymentServiceClient
    │   └── ImServiceClient
    ├── config/
    └── exception/
```

### 7.2 数据库 schema

所有表 UTC、`TIMESTAMPTZ`、雪花 ID、`deleted` 逻辑删除(参考 `standards.md`)。

> **D0 无桶**:D0 冷启动不再使用静态分桶表,直接调 `user-service.listDhCandidates` 实时召回(见 4.1)。原先设计的 `cold_start_bucket` / `cold_start_bucket_member` 两张表已**取消**。

```sql
-- 用户当日配额只在 Redis HASH 存储(`match:quota:<user_id>:<yyyymmdd>`,见 7.3),
-- 不再设 PG 表持久化:Redis AOF 已经够稳,单日配额丢失最多让用户多刷几张卡,
-- 不值得维护 PG 表 + 双写 + reconcile 链路。

-- 划卡历史(高写入,按 user_id hash 分区,30 天 TTL 归档)
CREATE TABLE user_swipe_history (
    id                  BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    target_user_id      BIGINT NOT NULL,
    target_user_type    SMALLINT NOT NULL,         -- 1=BH, 2=DH
    direction           SMALLINT NOT NULL,         -- 1=LEFT, 2=RIGHT, 3=SUPER_HI
    swiped_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted             BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (user_id, target_user_id)
);
CREATE INDEX idx_swipe_user_time ON user_swipe_history (user_id, swiped_at DESC);
CREATE INDEX idx_swipe_target_dir ON user_swipe_history (target_user_id, direction) WHERE deleted = false;

-- D1 / D0 队列只在 Redis LIST 存储,不再有 PG 持久化表(原 user_daily_queue 已取消)

-- DH 延迟匹配队列:已删除。15s-2min 延迟由进程内 Spring TaskScheduler 调度
-- (内存小顶堆,见 5.2);服务重启丢失 in-flight 任务是接受的 trade-off,
-- 不引入 PG 持久化兜底。

-- 匹配关系(主键保证 (a,b) 与 (b,a) 视为同一)
CREATE TABLE match (
    id              BIGINT PRIMARY KEY,
    user_id_low     BIGINT NOT NULL,                 -- min(uid1, uid2)
    user_id_high    BIGINT NOT NULL,                 -- max(uid1, uid2)
    matched_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    source          VARCHAR(30) NOT NULL,            -- 入口动作维度,见下表(不携带 BH/DH 信息,需要时由 user_id 反查 user.user_type)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (user_id_low, user_id_high)
);
CREATE INDEX idx_match_low_time  ON match (user_id_low,  matched_at DESC);
CREATE INDEX idx_match_high_time ON match (user_id_high, matched_at DESC);

-- match.source 枚举(入口动作维度,与 BH/DH 正交):
--   SWIPE_MATCH     首页划卡常规匹配(BH 互划 / DH 延迟回调,用户视角都是"和你匹配了")
--   SWIPE_SUPER_HI  首页划卡 Super Hi(订阅赠送或金币购买,无视对方意愿)
--   TBD             待定:推荐位 / 活动入口 / 系统配对 等后续接入时再加 enum,代码用 String 不用 Java enum 避免改一次跑一次 migration
-- BH 还是 DH 由 user_id_low / user_id_high 反查 user-service.user_type 得到,不冗余进 source。

-- 失败副作用 retry
CREATE TABLE match_outbox (
    id            BIGINT PRIMARY KEY,
    match_id      BIGINT NOT NULL,
    action        VARCHAR(40) NOT NULL,              -- ENSURE_CONVERSATION | SYSTEM_MSG | DH_OPENING
    payload_json  JSONB NOT NULL,
    attempts      INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL,
    status        VARCHAR(20) NOT NULL,              -- PENDING | DONE | DEAD
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted       BOOLEAN NOT NULL DEFAULT false
);

-- Like 记录(谁单向喜欢了我,未配对):真人 RIGHT_SWIPE 单向时落 + DH 任务执行落
-- 一旦触发 match,5.3 createMatch 同事务 DELETE 双向 like_record(因为关系升级为 match 了)
-- UNIQUE (from, to):同 liker→likee 全局唯一;UPSERT 更新 source / liked_at
CREATE TABLE like_record (
    id              BIGINT PRIMARY KEY,
    from_user_id    BIGINT NOT NULL,
    to_user_id      BIGINT NOT NULL,
    from_user_type  SMALLINT NOT NULL,               -- 1=BH 2=DH
    source          SMALLINT NOT NULL,               -- 1=SWIPE_RIGHT 2=DH_PLAN_ONLINE 3=DH_PLAN_OFFLINE  (SUPER_HI / 互划即时 match 不落 like_record)
    like_content    VARCHAR(200),                    -- DH 任务携带的文案;真人 swipe 为 NULL
    liked_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (from_user_id, to_user_id)
);
CREATE INDEX idx_like_to_user_time ON like_record (to_user_id, liked_at DESC) WHERE deleted = false;
CREATE INDEX idx_like_to_user_type ON like_record (to_user_id, from_user_type, liked_at DESC) WHERE deleted = false;  -- 24h DH 上限查询用

-- Visit 记录(谁访问了谁):真人主页访问 + DH 任务执行
-- UNIQUE (from, to):同 visitor→visitee UPSERT 累加 visit_count
CREATE TABLE visit_record (
    id              BIGINT PRIMARY KEY,
    from_user_id    BIGINT NOT NULL,
    to_user_id      BIGINT NOT NULL,
    from_user_type  SMALLINT NOT NULL,
    source          SMALLINT NOT NULL,               -- 1=PROFILE_VIEW 2=DH_PLAN_ONLINE 3=DH_PLAN_OFFLINE
    visit_count     INT NOT NULL DEFAULT 1,          -- UPSERT 时 +1
    visited_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (from_user_id, to_user_id)
);
CREATE INDEX idx_visit_to_user_time ON visit_record (to_user_id, visited_at DESC) WHERE deleted = false;
CREATE INDEX idx_visit_to_user_type ON visit_record (to_user_id, from_user_type, visited_at DESC) WHERE deleted = false;

-- DH 模拟互动任务(短生命周期,Executor 执行后硬删;不软删、不冗余审计)
-- generator 写、executor 读+删;任意时刻表内只装"未执行 + 失败重试中"两类行
CREATE TABLE dh_interaction_task (
    id              BIGINT PRIMARY KEY,
    from_user_id    BIGINT NOT NULL,                 -- DH user_id(发起方)
    to_user_id      BIGINT NOT NULL,                 -- 真人 user_id(接收方)
    action          SMALLINT NOT NULL,               -- 1=LIKE 2=VISIT
    scene           SMALLINT NOT NULL,               -- 1=ONLINE 2=OFFLINE
    execute_time    TIMESTAMPTZ NOT NULL,            -- 计划执行时刻;executor 扫 WHERE execute_time <= now()
    like_content    VARCHAR(200),                    -- action=LIKE 才填;VISIT 为 NULL
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dh_task_execute_time ON dh_interaction_task (execute_time);                -- executor 扫表用
CREATE INDEX idx_dh_task_to_user_scene ON dh_interaction_task (to_user_id, scene);          -- generator 去重查询用
```

> ⚠️ 持久层禁止多表 JOIN(`CLAUDE.md` 红线 1)。涉及 user_id → profile 的拼装在 service 层调 `user-service.batchGetProfile`。

### 7.3 Redis Key 设计

按 `<service>:<domain>:<id>` 规范(`docs/standards.md#redis`):

| Key | 类型 | TTL | 用途 |
|---|---|---|---|
| `match:quota:<user_id>:<yyyymmdd>` | HASH | 36h | `right_swipe`, `cards`, `super_hi` 字段累加;原子 HINCRBY |
| `match:feed:<user_id>` | LIST | 7 天 | 元素 = `"<target_user_id>:<target_user_type>"`,RPUSH 入队,LPOP 弹出消费;空了 → 实时召回重建 |
| `match:swiped:<user_id>` | SET | 永久(白名单注释) | 该用户所有已 swipe 过的 target_user_id。swipe 接口同步 SADD(见 7.6),GetTodayFeed 用 SMISMEMBER 做消费阶段二次过滤(见 4.3)。`user_swipe_history` 是权威源,Redis SET 丢了启动时 lazy 重建 |
| `match:pref:<user_id>` | HASH | 24h | 用户偏好画像 cache,D1 生成后写入 |
| `lock:match:d1:<yyyymmdd>` | Redisson | 1h | D1 cron 分布式锁,防止多实例重复跑 |
| `lock:match:swipe:<user_id>:<target_id>` | Redisson | 5s | 单次 swipe 串行化 |
| `user:online:rank` | ~~ZSet~~ | **已废弃** | 旧"全平台在线状态"ZSet 由 user-service 心跳维护;v2 起信号源换成 im-service 的 `im:presence:online`(im-service 由 OpenIM 上下线回调被动维护),且**不再允许跨服务直读**,统一走 `im-service.ListOnlineUserIds` / `ListRecentOfflineUsers` gRPC(见 7.7) |
| `match:dh_plan:cursor:online` | STRING | 永久 | OnlinePlanGenerator 游标(epoch ms),初始 `now-1min`,最大回看 30 min,见 6.3.1 |
| `match:dh_plan:cursor:offline` | STRING | 永久 | OfflinePlanGenerator 游标(epoch ms),初始 `now-3h`,见 6.3.2 |
| `match:dh_plan:cooldown:<user_id>` | STRING | 2h | OnlinePlanGenerator 生成成功后 SET;过期前同用户不重复生成 ONLINE 计划 |
| `match:dh_plan:last_scene:<user_id>` | STRING | 永久 | 值 `ONLINE` / `OFFLINE`;OfflinePlanGenerator 通过该值跳过本次离线期已处理用户 |
| `lock:match:dh_plan:online_sweep` | Redisson | 60s | OnlinePlanGenerator 多实例分布式锁 |
| `lock:match:dh_plan:offline_sweep` | Redisson | 30min | OfflinePlanGenerator 多实例分布式锁 |
| `lock:match:dh_plan:executor` | Redisson | 60s | LikeVisitorTaskExecutor 多实例分布式锁 |

**Redis LIST 关键约定**:
- TTL 7 天,长期不活跃用户回归时旧队列已过期 → GetTodayFeed 触发实时重建。
- LIST 内由 LPOP 即消费保证不重复;消费阶段还要用 `match:swiped` SET 做二次过滤(覆盖召回快照过时的边界 case,见 4.3)。"下发但未 swipe"的卡片不在 `match:swiped` 里,允许在下次重建时再次进入候选池 ── 这是有意为之,不算重复推荐。
- D1 cron 用 `DEL match:feed:<user_id>` + `RPUSH 240 张` 覆盖;`ColdStartService.buildAndPush` 用 `RPUSH` 不带 DEL(允许追加)。

### 7.4 gRPC API

proto 放仓库 [`gitee.com/jianjiange-site/proto`](https://gitee.com/jianjiange-site/proto) 下新增 `match/match.proto`,版本号 `0.1.0` 发到 Nexus(Maven `com.jianjiange.proto:match-proto`)。

```protobuf
syntax = "proto3";

package match;

service MatchService {
  // 拉当日 feed 下一批
  rpc GetTodayFeed(GetTodayFeedReq) returns (GetTodayFeedResp);
  // 提交一次 swipe
  rpc Swipe(SwipeReq) returns (SwipeResp);
  // Super Hi(独立 API,语义不同于普通 swipe)
  rpc SuperHi(SuperHiReq) returns (SuperHiResp);
  // 我的 match 列表
  rpc ListMatches(ListMatchesReq) returns (ListMatchesResp);
  // 当前配额
  rpc GetQuota(GetQuotaReq) returns (GetQuotaResp);
}

message GetTodayFeedReq  { int64 user_id = 1; int32 count = 2; }   // count <= 20
message GetTodayFeedResp { repeated Card cards = 1; bool exhausted = 2; }

message Card {
  int64 target_user_id = 1;
  int32 target_user_type = 2;     // 1=BH 2=DH
  string nickname = 3;
  int32 age = 4;
  repeated string photo_keys = 5; // object_key,不签 URL
  string bio = 6;
  double distance_km = 7;         // BH 才有,DH = -1
  // 不下发 score / beauty_score / race,避免泄露
}

message SwipeReq  { int64 user_id = 1; int64 target_user_id = 2; SwipeDirection direction = 3; }
enum SwipeDirection { SWIPE_UNSPECIFIED = 0; LEFT = 1; RIGHT = 2; }
message SwipeResp { 
  int64 match_id = 2;                   // > 0 即匹配成功(BH 互划场景);LEFT / 单向 RIGHT / DH 延迟均 = 0
  reserved 1, 3, 4;                     // ex matched / remaining_right_swipes / remaining_cards;App 不再展示
  reserved "matched", "remaining_right_swipes", "remaining_cards";
}

message SuperHiReq  { int64 user_id = 1; int64 target_user_id = 2; }
message SuperHiResp {
  int64 match_id = 2;                   // SuperHi BH/DH 都立即创建 match;0 仅出现在幂等回放场景
  int32 coins_used = 4;                 // 用了金币才有值(订阅赠送时为 0)
  reserved 1, 3, 5, 6;                  // ex matched / remaining_super_hi / remaining_right_swipes / remaining_cards
  reserved "matched", "remaining_super_hi", "remaining_right_swipes", "remaining_cards";
}

message ListMatchesReq  { int64 user_id = 1; int32 page_size = 2; string page_token = 3; }
message ListMatchesResp { repeated MatchVO matches = 1; string next_page_token = 2; }
message MatchVO {
  int64 match_id = 1;
  int64 partner_user_id = 2;
  string partner_nickname = 3;
  repeated string partner_photo_keys = 4;
  int64 matched_at_unix_ms = 5;
  string source = 6;                    // SWIPE_MATCH / SWIPE_SUPER_HI / ...(入口动作,与 BH/DH 正交)
}

message GetQuotaReq  { int64 user_id = 1; }
message GetQuotaResp {
  int32 daily_right_swipe_limit = 1;
  int32 daily_right_swipe_used  = 2;
  int32 daily_card_limit        = 3;
  int32 daily_card_used         = 4;
  int32 daily_super_hi_limit    = 5;    // 订阅赠送上限
  int32 daily_super_hi_used     = 6;
  int32 super_hi_coin_price     = 7;    // 100
  string subscription_tier      = 8;    // FREE/WEEKLY/MONTHLY/YEARLY
}
```

**Like / Visit 接口**(同 service,proto 同包,见 6.5 接口语义 / 7.2 表结构):

```protobuf
service MatchService {
  // ... 上面 5 个 rpc 省略 ...

  // 谁 like 了我(按 liked_at DESC 分页)
  rpc ListLikesOfMe(ListLikesOfMeReq) returns (ListLikesOfMeResp);
  // 谁访问了我(按 visited_at DESC 分页;同人多次访问累积 visit_count)
  rpc ListVisitsOfMe(ListVisitsOfMeReq) returns (ListVisitsOfMeResp);
  // 上报主页访问(异步落 visit_record;mobile-gateway 在用户点别人主页时调)
  rpc RecordVisit(RecordVisitReq) returns (RecordVisitResp);
}

message ListLikesOfMeReq  { int64 user_id = 1; int32 page_size = 2; string page_token = 3; }   // page_size ≤ 50
message ListLikesOfMeResp { repeated LikeVO likes = 1; string next_page_token = 2; int32 total_unread = 3; }
message LikeVO {
  int64 from_user_id = 1;          // liker
  string nickname = 2;
  int32 age = 3;
  repeated string photo_keys = 4;  // object_key,前端自拼 CDN URL
  int64 liked_at_unix_ms = 5;
  string like_content = 6;         // DH 任务携带的文案;真人 swipe 为空
  // 不下发 from_user_type / source —— 对外屏蔽 BH/DH 差异
}

message ListVisitsOfMeReq  { int64 user_id = 1; int32 page_size = 2; string page_token = 3; }
message ListVisitsOfMeResp { repeated VisitVO visits = 1; string next_page_token = 2; int32 total_unread = 3; }
message VisitVO {
  int64 from_user_id = 1;          // visitor
  string nickname = 2;
  int32 age = 3;
  repeated string photo_keys = 4;
  int64 visited_at_unix_ms = 5;
  int32 visit_count = 6;           // 同 visitor→viewer 累积访问次数
}

message RecordVisitReq  { int64 viewer_user_id = 1; int64 target_user_id = 2; }
message RecordVisitResp { bool ok = 1; }                                          // ok 仅表示已收;实际落库异步,失败仅打 WARN
```

### 7.5 调度任务

| Job | 触发 | 备注 |
|---|---|---|
| `D1QueueScheduler.run()` | `@Scheduled(cron = "0 0 7 * * *", zone = "UTC")` | 对应美东 EDT 03:00 / EST 02:00。**约定:cron 用 UTC,PRD 文档统一描述为"美东 03:00"**;DST 期间实际跑早 1h 是接受的偏移(避免冬令时切换日跑两次或跳过) |
| `MatchOutboxRetry.run()` | `@Scheduled(fixedDelay = 30_000)` | 副作用失败 retry,exponential backoff |
| `OnlinePlanGenerator.run()` | `@Scheduled(fixedDelay = 60_000)` | 每 1 分钟扫在线 ZSet 增量,给在线用户生成 DH like/visit 任务,见 6.3.1。**Redisson 锁 `lock:match:dh_plan:online_sweep` 多实例只跑一份** |
| `OfflinePlanGenerator.run()` | `@Scheduled(fixedDelay = 1_200_000)` | 每 20 分钟扫离线区间,给离线 ≥ 20 min 的用户生成 DH 任务,见 6.3.2。**Redisson 锁 `lock:match:dh_plan:offline_sweep`** |
| `LikeVisitorTaskExecutor.run()` | `@Scheduled(fixedDelay = 60_000)` | 每 1 分钟扫到期任务并执行 UPSERT + 删,见 6.3.3。**Redisson 锁 `lock:match:dh_plan:executor`** |

实际配置:

```java
// PRD: 每日美东 03:00 跑 D1 队列生成
// 实现: UTC 07:00 固定 cron (DST 期间实际美东 02:00, 接受半年期 1h 漂移)
@Scheduled(cron = "0 0 7 * * *", zone = "UTC")
public void runDailyQueueGen() { ... }
```

> **为什么不用 `zone = "America/New_York"`**:Spring Boot 6 的 `@Scheduled(zone=)` 在 DST 切换日有边界 case(春令时跳过的 02:00-03:00 那个小时不触发,秋令时回拨可能触发两次)。生产环境优先确定性,选固定 UTC。

### 7.6 关键并发问题

#### 配额扣减

`SwipeService.swipe()` 必须串行化每个用户对同一 target 的并发请求。

```java
RLock lock = redissonClient.getLock("lock:match:swipe:" + userId + ":" + targetId);
if (!lock.tryLock(5, 3, TimeUnit.SECONDS)) {
    throw new BizException(CONCURRENT_SWIPE);
}
try {
    if (swipeHistoryManager.exists(userId, targetId)) {
        return last result;  // 幂等
    }
    long right = redis.hincrBy("match:quota:" + userId + ":" + today, "right_swipe", 1);
    if (right > limit.rightSwipe) {
        redis.hincrBy(... , "right_swipe", -1);   // 回滚
        throw new BizException(QUOTA_RIGHT_SWIPE_EXCEEDED);
    }
    // ... 同样扣 cards
    // 在一个 @Transactional 里:
    //   1. 写 user_swipe_history
    //   2. SADD match:swiped:<userId> targetId          ← 消费阶段二次过滤的 cache,见 4.3
    //   3. 可能的 match(BH 互划 / SUPER_HI / DH 延迟回调入队)
} finally {
    lock.unlock();
}
```

Redis 计数即权威:HINCRBY 原子累加,超额则 HINCRBY -1 回滚 + 抛 `QUOTA_*_EXCEEDED`。Redis 用 AOF `appendfsync everysec` 持久化,最坏丢 1s 写入(可接受 ── 用户至多多刷几张卡)。**不**做 PG 强校验 / EOD reconcile。

#### D1 cron 防重

多实例 match-service 同时跑 cron。用 Redisson 锁 `lock:match:d1:<date>`,只有抢到锁的实例跑。

#### Match 创建幂等

`match` 表 `(user_id_low, user_id_high)` 唯一约束,INSERT ... ON CONFLICT DO NOTHING。**正常情况下不应触发 ON CONFLICT**(召回阶段已经过滤 `user_swipe_history`,看过的人不会再出现,自然不会再次 swipe 同一对方);如果触发,说明上游过滤链路有 bug → 打 ERROR 日志报警,见 5.3。

#### DH 计划三个 scheduler 的并发

三个 scheduler 都用 Redisson 锁兜底多实例分布式部署:

- `OnlinePlanGenerator` / `OfflinePlanGenerator`:抢不到锁的实例直接 skip 本轮(锁内已有人在跑),不阻塞等待。游标推进是单写者,无 race。
- `LikeVisitorTaskExecutor`:抢不到锁的实例 skip。`SELECT ... ORDER BY execute_time LIMIT 1000` 内不锁行,允许两实例理论上读到同一批 —— 但锁保证同一时刻只一个 executor 在跑,实际不会撞;即使撞了,UPSERT 是幂等(ON CONFLICT 更新),DELETE 重复也无害(行已删返回 0)。

#### like_record / visit_record UPSERT

`(from, to)` UNIQUE 索引兜底:
- SwipeService 同事务 UPSERT 不需要加锁(swipe 接口本身已经在 5s `lock:match:swipe:<user>:<target>` 内)
- LikeVisitorTaskExecutor UPSERT 无并发(锁兜底,见上)
- RecordVisit 异步线程池写入,两个并发 visit 同一对 (viewer, target) 时:PG 内核保证 INSERT ... ON CONFLICT (from, to) DO UPDATE 原子;两次 UPSERT 累加 visit_count + 2,语义正确

### 7.7 与现有服务的调用

- `user-service.batchGetProfile(user_ids)`:拿 nickname/age/photo_keys/distance,**不缓存**(见 [[feedback-no-cache-on-user-service-rpc]])。
- `user-service.listDhCandidates(req)`:**待新增 RPC**。D0/D1 共用的 DH 召回入口,入参:
  ```protobuf
  message ListDhCandidatesReq {
    int32 target_gender = 1;        // 1=MALE 2=FEMALE
    int32 age_min = 2;
    int32 age_max = 3;
    int32 beauty_min = 4;
    int32 beauty_max = 5;
    repeated string races = 6;      // 优先人种,空表示不限
    repeated int64 exclude_user_ids = 7;  // 已 seen / 已 swipe 的 DH 排除
    int32 limit = 8;                // 通常 240
  }
  ```
  D0 调用方(`ColdStartService`)在 match-service 内部按 4.1 的 L0~L3 分层,**多次调用此 RPC**(每层不同的 age/beauty/races 入参,各自 limit = 240 - 已累积数),累积去重直到 240 张或 L3 兜底用完。**RPC 本身不感知层级**,只负责按入参做单次过滤召回。返回字段必须含 `created_at`(用于 4.1 D0 的 `is_new_bh` 排序键和 4.2.3 D1 的 `new_bh_bonus`)、`beauty_score`、`age`、`race`、`gender`。内部按 `user_type = DH AND deleted = false` 过滤,推荐 user-service 用 PG 范围扫 + 索引 `(user_type, gender, age, beauty_score)` 实现。
- `user-service.nearbyUsers(user_id, radius_km, age_min, age_max, beauty_min, beauty_max, races, last_active_within_days, limit, exclude_user_ids)`:**待新增 RPC**,基于 Redis GEO,D0/D1 BH 召回必需。**D0 单次调用**,严格条件(±5 / ±15 / 同人种 / ≤100km / 7 天活跃)不放宽,不够就不够,merge 阶段 DH 补齐;**D1 单次调用**,radius_km=200,排除条件由 service 层进一步过滤。返回字段必须含 `created_at`(用于 4.1 D0 的 `is_new_bh` 排序键和 4.2.3 D1 的 `new_bh_bonus`)、`beauty_score`、`age`、`race`、`gender`、`last_active_at`、`distance_km`。
- `payment-service.getSubscription(user_id)`:返回 `tier + expire_at`。match-service 缓存 5min。
- `payment-service.consumeCoins(user_id, amount, reason="SUPER_HI")`:Super Hi 用金币时调,内部走 payment 自己的事务。返回 `ok / INSUFFICIENT_COINS`。
- `im-service.ensureConversation(user_id_a, user_id_b)`:幂等建会话。
- `im-service.sendSystemMessage(to_user_id, payload)`:配对成功后双方各发一条。
- `im-service.triggerDhOpening(dh_user_id, target_user_id)`:**新增**,让 im-service 调 ai-chat 走 DH 主动开场流程。
- `im-service.ListOnlineUserIds(since_ms, until_ms, limit)`:**待新增 RPC**。OnlinePlanGenerator(6.3.1)用。im-service 内部执行 `ZRANGEBYSCORE im:presence:online since_ms until_ms LIMIT 0 limit`,返回本窗口内新建立 OpenIM 会话的真人 userId 列表(DH 没有 IM 设备登录,天然不会进 `im:presence:online`,所以返回值天然不含 DH)。
  ```protobuf
  rpc ListOnlineUserIds(ListOnlineUserIdsReq) returns (ListOnlineUserIdsResp);
  message ListOnlineUserIdsReq  {
    int64 since_ms = 1;   // 闭区间下界(epoch ms)
    int64 until_ms = 2;   // 开区间上界
    int32 limit    = 3;   // 默认 5000,上限 50000
  }
  message ListOnlineUserIdsResp { repeated int64 user_ids = 1; }
  ```
- `im-service.ListRecentOfflineUsers(since_ms, until_ms, limit)`:**待新增 RPC**。OfflinePlanGenerator(6.3.2)用。im-service 内部:`SELECT user_id FROM user_online_session WHERE offline_at >= :since AND offline_at < :until AND deleted = false ORDER BY offline_at LIMIT :limit`;**需新增 partial index**:
  ```sql
  CREATE INDEX idx_uos_offline_at
      ON user_online_session (offline_at) WHERE offline_at IS NOT NULL AND NOT deleted;
  ```
  proto:
  ```protobuf
  rpc ListRecentOfflineUsers(ListRecentOfflineUsersReq) returns (ListRecentOfflineUsersResp);
  message ListRecentOfflineUsersReq  {
    int64 since_ms = 1;
    int64 until_ms = 2;
    int32 limit    = 3;   // 默认 5000
  }
  message ListRecentOfflineUsersResp { repeated int64 user_ids = 1; }
  ```
- **user-service 不再维护任何在线状态信号**:无 `Heartbeat` RPC、无 `/v1/online/heartbeat` REST、无 `user:online:rank` ZSet、无 `user:hb:db_sync:*` 节流 token、无 nightly `ZREMRANGEBYSCORE`,移动端不上报心跳。全平台在线状态权威信号源**收敛到 im-service**:实时态在 ZSet `im:presence:online`,历史态在 PG `user_online_session`,由 OpenIM 上下线回调被动驱动;**所有跨服务消费一律走 im-service gRPC**,不直读 Redis / 不跨库读 PG,符合 CLAUDE.md 第 2/3 条红线。
- `user-service.listDhCandidates(req)`:Like/Visit DH 计划在 6.3.1 / 6.3.2 复用此 RPC,**入参 `exclude_user_ids` 为目标 BH 已被 like / visit 关联过、或已与之 match 的所有 DH**(避免同一 DH 反复出现、以及给已配对的 DH 再补无意义互动,详见 6.4)。RPC 本身无变化。

## 8. 边界与异常

| 场景 | 处理 |
|---|---|
| 用户拉 feed 时配额耗尽 | 返回 `exhausted=true, cards=[]`,App 展示"今日已看完" |
| Super Hi 时金币不足且无订阅赠送 | `BizException(INSUFFICIENT_COINS)`,App 引导充值 |
| 重复 swipe 同一 target | 幂等返回上次结果,不扣配额。**正常情况不发生**(召回过滤兜底);非传输层重试导致的重复打 WARN 日志,真正升级为 ERROR 的是触发 match 重复(见下一行) |
| 划卡 target 已注销 | swipe 写入历史但跳过 match 触发 |
| D1 生成时该用户已注销 | 跳过该用户 |
| DH 延迟回调时 user / dh 已注销 | `MatchService.createMatch` 内做存在性校验,失败丢日志,不抛 |
| 同一对用户重复触发 match(已 matched 还要再 match) | `match` 表 UNIQUE 兜底,触发 ON CONFLICT → 打 **ERROR 日志报警**(说明召回过滤链路有 bug)+ 返回 existing match。正常情况不应发生,见 5.3 |
| 队列耗尽但配额还有 | `GetTodayFeed` LPOP 不足 → 触发 `ColdStartService.buildAndPush` 实时重建 240 张 → 继续 LPOP。对 App 透明,无降级日志(常规路径) |
| 召回排序结果包含用户性取向之外的 target | 召回阶段已硬过滤,理论上不会发生;若发生丢监控 |
| Like/Visit RecordVisit 自访问 | controller 层短路返回 `ok=true`,不落库 |
| Like/Visit RecordVisit 异步写入失败 | 仅打 WARN 日志,不抛给调用方;visit 列表少几条不影响产品 |
| Like/Visit DH 计划 generator cursor 长期不动(超 30 min) | 告警 + 重置 cursor 为 `now - 1min`(ONLINE) / `now - 3h`(OFFLINE) |
| Like/Visit DH 计划生成时 DH 池为空(listDhCandidates 返回 0) | 跳过该用户,不报错。运营查 DH 池规模 |
| Like/Visit Executor 任务持续积压(`execute_time + 5min < now()` 的行数 > 阈值) | 打 WARN,运营查 executor 健康 / DB 写入瓶颈 |
| Like/Visit DH 24h 上限达成 | generator 阶段查询 `like_record` / `visit_record` 24h 计数,达限直接跳过本次生成,不报错 |

## 9. 落地步骤 / 里程碑

1. **proto + 库**:在 proto 仓库加 `match.proto`,发版 `0.1.0`;match-service 工程骨架(参考 `example-service`)。
2. **基础 CRUD**:配额/划卡/match 表 + Mapper + Manager + Service,单测覆盖。
3. **D0 实时召回**:`ColdStartService`,依赖 user-service 新 RPC(`listDhCandidates` + `nearbyUsers`)。无导入工具,完全实时。
4. **D1 cron + 推荐**:`D1QueueScheduler` + `PreferenceBuilder/CandidateRecaller/Ranker`。
5. **DH 延迟匹配**:`DhDelayedMatchService` 进程内 TaskScheduler + `im-service.TriggerDhOpening` 新 RPC。
6. **payment 接入**:`PaymentServiceClient` + 5min cache。
7. **mobile-gateway 编排**:REST 端点 `/match/feed`、`/match/swipe`、`/match/super-hi`、`/match/likes`、`/match/visits`、`/match/visit/record` 转发到 match-service。
8. **Like/Visit 体系**:like_record / visit_record / dh_interaction_task 三张表 + LikeVisitService + 三个 scheduler(OnlinePlanGenerator / OfflinePlanGenerator / LikeVisitorTaskExecutor)。**依赖 im-service 新增两个 gRPC**:`ListOnlineUserIds(sinceMs, untilMs)` 读 `im:presence:online`,`ListRecentOfflineUsers(sinceMs, untilMs)` 读 PG `user_online_session`(新增 partial index `(offline_at) WHERE offline_at IS NOT NULL`);信号源完全由 OpenIM 上下线回调被动驱动,**不再走 user-service 心跳链路**。
9. **压测**:1k QPS swipe / 200ms p99;DH 计划 scheduler 单实例 10k 用户/分钟 sweep 不超时。
10. **运营工具**:DH 池管理(在 user-service admin 端)、bh_ratio Nacos 实时调、配额运营干预接口(放 admin 工具,不入主 API)、`match.dh_plan.like_content_templates` 文案模板管理 UI。

## 10. 待定决策点(等产品确认)

已 confirm(2026-06-03):
- ✅ 周度档**不**送 Super Hi
- ✅ Super Hi 对 DH **立即匹配**(无延迟)
- ✅ cron 用 **UTC 表达式**;**PRD 文档统一用美东时间描述**
- ✅ "可划卡片"是**每日上限**;D1 队列容量固定 **240** 张/人
- ✅ **全平台异性恋假设**,不存 `gender_seeking` 字段,服务端按 `oppositeGender(user.gender)` 反推目标性别
- ✅ **D0 / D1 各自独立 `bh_ratio` Nacos 开关**(`match.cold_start.bh_ratio` / `match.d1.bh_ratio`),BH 不足时 DH 补齐
- ✅ **D0 / D1 都走"两池独立 240 → 按比例 merge"模型**,不再有"先 240 召回后切"或一锅出
- ✅ **取消冷启动分桶表**(`cold_start_bucket` / `cold_start_bucket_member`),D0 与 D1 共用 `user-service.listDhCandidates` 实时召回,只是偏好来源不同
- ✅ **Redis LIST + LPOP 模型**:`match:feed:<user_id>` 用 LIST,移动端每次 LPOP 5 张;队列空 → 自动调实时召回重建。**不维护 seen SET**,不持久化 `user_daily_queue` PG 表,不做 D1 失败兜底
- ✅ **召回阶段过滤 `user_swipe_history`**:任何方向(LEFT/RIGHT/SUPER_HI)都排除,确保"看过的人不再出现"
- ✅ **不做运营白名单**(原 D10):明星 DH / 测试位 DH 完全交给召回排序,不引入 `priority_dh` Nacos 列表
- ✅ **不维护 `user_daily_quota` PG 表**:配额只在 Redis HASH 存,AOF 持久化兜底;不做双写、不做 EOD reconcile
- ✅ **DH 延迟匹配窗口 15s ~ 2min**,Spring `TaskScheduler` 进程内调度;**不**建 `pending_dh_match` PG 表,**不**跑扫表 cron,接受重启丢 in-flight 任务
- ✅ **3 天内新注册 BH 加权曝光**:打分公式新增加性 `new_bh_bonus`(默认 +0.15,窗口默认 3 天,均走 Nacos);仅作用于 BH,DH 不变;D0 / D1 都生效
- ✅ **已 matched 对不再 match**:`match` 表 UNIQUE 兜底,触发 ON CONFLICT 打 ERROR 日志(召回过滤 bug 信号)+ 返回 existing match,不抛给 swipe RPC
- ✅ **match 成功 BH 端必然收到通知**:双方都通过 `im-service.SendSystemMessage` 收到"你们配对了"系统消息(在 5.3 的副作用列表里,不可关闭)
- ✅ **D0 召回:DH 池支持渐进扩范围 L0~L3(必须凑 240),BH 池严格 L0 单层(±5 / ±15 / 同人种 / ≤100km / 7 天活跃,不够就不够,DH 补齐)**
- ✅ **D0 排序键改为字典序硬排序**:BH 池 4 级(新 BH → 同人种 → 年龄差 → 颜值),DH 池 3 级(同人种 → 年龄差 → 颜值);不走 4.2.3 的 D1 打分公式;D1 仍用打分公式
- ✅ **消费阶段二次过滤**:`GetTodayFeed` LPOP 后用 `match:swiped:<user_id>` Redis SET 做 SMISMEMBER 校验,命中的卡片直接丢弃,继续 LPOP 凑足 `need`;swipe 接口同步 SADD 维护该 SET
- ✅ **取消 4.2.4 重排序**:删除 MMR 多样性重排 + ε-greedy 探索;直接按 4.2.3 score 取 top 240
- ✅ **D1 BH 池只召回 7 天内活跃**:`last_active_within_days=7` 由 user-service 端硬过滤;match-service 不在内存里二次筛
- ✅ **D1 打分加大对方右划/新 BH 信号**:`mutual_signal` 从加权项(0.10)升级为加性 bonus `mutual_like_bonus` 默认 +0.20;`new_bh_bonus` 默认值 0.15 → +0.20;两个 bonus 不参与权重和,直接叠加显著拉升
- ✅ **删除 DH 延迟匹配深夜规避**:15s-2min 窗口不涉及跨时段,无需用户本地时间逻辑
- ✅ **match.source 简化为入口动作维度**:只有 `SWIPE_MATCH` / `SWIPE_SUPER_HI`,BH/DH 信息由 user_id 反查 `user_type` 不冗余进 source;后续新入口(推荐位 / 活动 / 系统配对)再加 enum
- ✅ **打分权重 / bonus 数值不在文档纠结**:0.45 / 0.30 / 0.15 / 0.10 base + 0.20/0.20 bonus + new_bh_window_days=3 全部走 Nacos,上线后看数据 A/B 调

Like / Visit 互动记录 confirm(2026-06-04):
- ✅ **Like/Visit 归入 match-service**,不开独立服务、不开独立 schema;共用 user-service.listDhCandidates 召回 DH
- ✅ **三个 scheduler 分工**:OnlinePlanGenerator(每 1 min)/ OfflinePlanGenerator(每 20 min)/ LikeVisitorTaskExecutor(每 1 min),全部走 Redisson 锁支持多实例分布式部署
- ✅ **ONLINE 用 cooldown(2h)+ ZSet 游标增量**,OFFLINE 用 lastScene 闸(单次离线期最多 1 个计划)+ 离线满 20 min 才抓
- ✅ **真实感约束全部走 Nacos**:单次生成 5~10 / 3~6 张、execute_time 在 30 min / 30 min 窗口均匀随机分布、24h DH like/visit 上限 15/25 条、visit/like 比例 60%/40%
- ✅ **DH 候选过滤已 match 用户**:listDhCandidates 入参 `exclude_user_ids` 在原有"已 like/visit 关联过的 DH"基础上,再 UNION `match` 表中已与该 BH 配对的所有用户;避免给已配对的 DH 再补 like/visit(逻辑割裂)
- ✅ **like_content 文案池放 Nacos**(`match.dh_plan.like_content_templates`,JSON 列表),运营可热刷;后期接 admin UI
- ✅ **DH 任务表执行后硬删**,不软删、不审计;失败保留行下一轮重试
- ✅ **`im:presence:online` ZSet + `user_online_session` PG 是全平台在线状态权威信号源**,**由 im-service 通过 OpenIM `callbackUserOnlineCommand` / `callbackUserOfflineCommand` 回调被动维护**,**无单独心跳接口**;所有跨服务消费走 `im-service.ListOnlineUserIds` / `ListRecentOfflineUsers` gRPC,**禁止直读其 Redis key / 直查其 PG 表**。完全废弃旧 `user:online:rank` ZSet / `/v1/online/heartbeat` REST / `user-service.Heartbeat` RPC / `user:hb:db_sync:*` 节流 token / nightly `ZREMRANGEBYSCORE`。OnlinePlanGenerator / OfflinePlanGenerator 单次会话内同一用户只触发一次 ONLINE 计划(score = `online_at`,不再随心跳推进),产品上接受。
- ✅ **like_record 只存"单向未回应的喜欢"**:SUPER_HI 立即 match、RIGHT_SWIPE 触发互划即时 match 都**不**落 like_record(没有"暗恋"窗口);只有 RIGHT_SWIPE 单向(对方未右划过我)才落 source=SWIPE_RIGHT
- ✅ **match 创建时同事务清理双向 like_record**:`DELETE WHERE (from, to) IN ((low,high), (high,low))`,保证"已 match 的人不再出现在 Likes of me 列表";SUPER_HI / 即时互划路径下 DELETE 为 no-op
- ✅ **like_record / visit_record UNIQUE (from, to)**:同 (liker→likee) 全局一条,UPSERT 更新;visit 累加 `visit_count`
- ✅ **接口对外屏蔽 BH/DH**:ListLikesOfMe / ListVisitsOfMe 不下发 `from_user_type` / `source`,用户视角一致
- ✅ **RecordVisit 异步落库**:mobile-gateway 同步调,服务端线程池写入,失败仅 WARN 不阻塞;自访问短路

无待定项。

---

**作者**:dating-server team / 2026-06-03(Like/Visit 章节追加 2026-06-04)  
**Status**:**APPROVED** ── 所有决策点 confirm,可以进入实施。
