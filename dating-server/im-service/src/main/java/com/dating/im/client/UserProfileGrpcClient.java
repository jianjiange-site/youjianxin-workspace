package com.dating.im.client;

import com.dating.youjianxin.proto.user.BatchGetProfileRequest;
import com.dating.youjianxin.proto.user.BatchGetProfileResponse;
import com.dating.youjianxin.proto.user.UserProfile;
import com.dating.youjianxin.proto.user.UserProfileServiceGrpc;
import com.dating.youjianxin.proto.user.UserType;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * gRPC client for calling user-service to fetch user types.
 *
 * <p>Used by {@link com.dating.im.handler.MessageSentHandler} to determine
 * whether a userId is DH (digital human) or BH (real human).
 */
@Component
public class UserProfileGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(UserProfileGrpcClient.class);

    /** deadline:此调用在 before-send 同步回调热路径上,必须有界,避免 user-service 卡死整个 5s 预算。 */
    private static final long CALL_TIMEOUT_MS = 800L;

    @GrpcClient("user-service")
    private UserProfileServiceGrpc.UserProfileServiceBlockingStub userProfileStub;

    /**
     * Batch-fetches user_type for the given userIds.
     * Missing / soft-deleted users are absent from the returned map.
     * On RPC failure returns empty map; caller falls back to BH default.
     */
    public Map<Long, UserType> batchGetUserType(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        BatchGetProfileRequest req = BatchGetProfileRequest.newBuilder()
                .addAllTargetUserIds(userIds)
                .build();
        try {
            BatchGetProfileResponse resp = userProfileStub
                    .withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .batchGetProfile(req);
            Map<Long, UserType> result = new HashMap<>(resp.getProfilesCount());
            for (UserProfile p : resp.getProfilesList()) {
                result.put(p.getUserId(), p.getUserType());
            }
            return result;
        } catch (StatusRuntimeException e) {
            log.error("user-service batchGetProfile failed userIds={} status={}", userIds, e.getStatus(), e);
            return Collections.emptyMap();
        }
    }
}
