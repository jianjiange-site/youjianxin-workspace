package com.dating.match.it;

import com.dating.match.client.ImClient;
import com.dating.match.client.PaymentClient;
import com.dating.match.client.UserClient;
import com.dating.match.scheduler.D1QueueScheduler;
import com.dating.match.scheduler.LikeVisitorTaskExecutor;
import com.dating.match.scheduler.MatchOutboxRetry;
import com.dating.match.scheduler.OfflinePlanGenerator;
import com.dating.match.scheduler.OnlinePlanGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类:直连 dev 154.217.241.155 PG/Redis(共享基建)。
 *
 * <ul>
 *   <li>仅在环境变量 {@code IT_DB_PASSWORD} 显式提供时跑(避免 CI / 默认 mvn test 误跑污染 dev)</li>
 *   <li>@MockBean 把所有 scheduler 替换掉,防止后台 cron 在测试期间扫表干扰</li>
 *   <li>@MockBean 把 ImClient / UserClient / PaymentClient 替换掉,无需 Nacos discovery
 *       (子类用 {@code @Autowired} 引用这三个 mock 注入测试期望)</li>
 *   <li>测试数据 ID 预留高位段 {@link #ID_RANGE_LO}~{@link #ID_RANGE_HI},每个 @Test 前后清理</li>
 *   <li>Flyway 启动时会把仓库里 V4 migration 自动 apply 到 dev 库(只新增列/表,幂等)</li>
 * </ul>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IT_DB_PASSWORD", matches = ".+")
public abstract class DevInfraITSupport {

    /** 测试数据保留 ID 段(避免与真实 BH/DH 撞 user_id) */
    protected static final long ID_RANGE_LO = 9_990_000_001L;
    protected static final long ID_RANGE_HI = 9_999_999_999L;

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected StringRedisTemplate redis;

    // ── 关掉所有后台 cron,避免与 IT 同时操作业务表
    @MockBean protected OnlinePlanGenerator onlinePlanGenerator;
    @MockBean protected OfflinePlanGenerator offlinePlanGenerator;
    @MockBean protected LikeVisitorTaskExecutor likeVisitorTaskExecutor;
    @MockBean protected MatchOutboxRetry matchOutboxRetry;
    @MockBean protected D1QueueScheduler d1QueueScheduler;

    // ── 跨服务 RPC mock,断 Nacos 依赖
    @MockBean protected UserClient userClient;
    @MockBean protected ImClient imClient;
    @MockBean protected PaymentClient paymentClient;

    @BeforeEach
    @AfterEach
    void cleanupTestData() {
        jdbc.update("DELETE FROM dh_interaction_task WHERE to_user_id BETWEEN ? AND ? OR from_user_id BETWEEN ? AND ?",
                ID_RANGE_LO, ID_RANGE_HI, ID_RANGE_LO, ID_RANGE_HI);
        jdbc.update("DELETE FROM like_record WHERE to_user_id BETWEEN ? AND ? OR from_user_id BETWEEN ? AND ?",
                ID_RANGE_LO, ID_RANGE_HI, ID_RANGE_LO, ID_RANGE_HI);
        jdbc.update("DELETE FROM visit_record WHERE to_user_id BETWEEN ? AND ? OR from_user_id BETWEEN ? AND ?",
                ID_RANGE_LO, ID_RANGE_HI, ID_RANGE_LO, ID_RANGE_HI);

        // 也清掉本测试范围内可能落下的 cooldown / last_scene / cursor key,避免污染下一轮
        for (long uid = ID_RANGE_LO; uid < ID_RANGE_LO + 50; uid++) {
            redis.delete("match:dh_plan:cooldown:" + uid);
            redis.delete("match:dh_plan:last_scene:" + uid);
        }
    }
}
