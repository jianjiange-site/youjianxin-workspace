package com.dating.post.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * user-service 调用方(post-service-design §11)。
 * <p>
 * <strong>当前为纯桩实现</strong>:
 * <ul>
 *   <li>{@link #getFriendUserIds(Long)} 返空 —— 写扩散 no-op,所有用户只走全网池/冷启动池</li>
 *   <li>{@link #isMale(Long)} 用 {@code userId % 2 == 0} —— 测试用,真实分桶等 user-service 上线后替换</li>
 *   <li>{@link #getGenders(Collection)} 同 {@link #isMale(Long)} 规则填 map</li>
 * </ul>
 * <p>
 * TODO 等 user-service 实现 {@code GetFriendList} / {@code GetProfile.gender} 后:
 * <ol>
 *   <li>pom 加 {@code com.dating.youjianxin.proto:user-proto:0.1.0} 依赖</li>
 *   <li>{@code @GrpcClient("user-service")} 注入 {@code UserServiceBlockingStub}</li>
 *   <li>把下面方法体替换为 stub 调用,fallback 走 try-catch 返当前桩值</li>
 *   <li>Caffeine 缓存继续保留(削峰 RPC 风暴)</li>
 * </ol>
 * <p>
 * 设计约束(red lines 2 / 11):
 * <ul>
 *   <li><b>本地 Caffeine 短 TTL 缓存可以加</b>(进程内,挂了不影响一致性)</li>
 *   <li><b>但 user-service 返回的资料不要再缓存到 Redis</b>(user-service 自己已有 {@code user:profile:*} 缓存,二级缓存只让一致性更难)</li>
 * </ul>
 */
@Component
public class UserClient {

    /** 性别缓存,FeedScoreJob 重建池时同一 userId 会被多帖反复查询。 */
    private final Cache<Long, Boolean> genderCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    /**
     * 取「userId 的关注者列表」,用于发帖写扩散。
     * <p>
     * 调用方约束:user-service 不可用时返空(降级 no-op),绝不让发帖失败(design §11)。
     */
    public List<Long> getFriendUserIds(Long userId) {
        // TODO 替换为 stub.getFriendList(userId).getUserIdsList()
        return Collections.emptyList();
    }

    /**
     * 判性别,用于 Feed 池分桶。
     * <p>
     * fallback false(归到女性池,可接受不准)。
     */
    public boolean isMale(Long userId) {
        if (userId == null) {
            return false;
        }
        return genderCache.get(userId, k -> stubIsMale(k));
    }

    /** 批量取性别,FeedScoreJob 重建池时**必须批量**,单条 N 次会压垮 user-service。 */
    public Map<Long, Boolean> getGenders(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Boolean> result = new HashMap<>(userIds.size() * 2);
        for (Long uid : userIds) {
            result.put(uid, isMale(uid));
        }
        return result;
    }

    /** 桩规则:userId 偶数视为男性。 */
    private boolean stubIsMale(Long userId) {
        return userId != null && userId % 2 == 0;
    }
}
