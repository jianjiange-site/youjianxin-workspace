package com.dating.mobilegateway.client;

import com.dating.youjianxin.proto.match.GetQuotaRequest;
import com.dating.youjianxin.proto.match.GetQuotaResponse;
import com.dating.youjianxin.proto.match.GetTodayFeedRequest;
import com.dating.youjianxin.proto.match.GetTodayFeedResponse;
import com.dating.youjianxin.proto.match.ListMatchesRequest;
import com.dating.youjianxin.proto.match.ListMatchesResponse;
import com.dating.youjianxin.proto.match.ListVisitsOfMeRequest;
import com.dating.youjianxin.proto.match.ListVisitsOfMeResponse;
import com.dating.youjianxin.proto.match.MatchServiceGrpc;
import com.dating.youjianxin.proto.match.RecordVisitRequest;
import com.dating.youjianxin.proto.match.RecordVisitResponse;
import com.dating.youjianxin.proto.match.SuperHiRequest;
import com.dating.youjianxin.proto.match.SuperHiResponse;
import com.dating.youjianxin.proto.match.SwipeDirection;
import com.dating.youjianxin.proto.match.SwipeRequest;
import com.dating.youjianxin.proto.match.SwipeResponse;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * match-service MatchService 客户端封装。
 *
 * <p>caller userId 由 GrpcClientMetadataInterceptor 从 RequestContextHolder 注入 x-user-id metadata,
 * 入参不出现 caller。
 *
 * <p>异常统一走 {@link GrpcStatusMapper} 转 BizException;Controller 由 GlobalExceptionHandler 兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchClient {

    private static final long CALL_TIMEOUT_MS = 3000L;

    @GrpcClient("match-service")
    private MatchServiceGrpc.MatchServiceBlockingStub stub;

    public GetTodayFeedResponse getTodayFeed(int count) {
        try {
            return stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .getTodayFeed(GetTodayFeedRequest.newBuilder().setCount(count).build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public SwipeResponse swipe(long targetUserId, SwipeDirection direction) {
        try {
            return stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .swipe(SwipeRequest.newBuilder()
                            .setTargetUserId(targetUserId)
                            .setDirection(direction)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public SuperHiResponse superHi(long targetUserId, String clientRequestId) {
        try {
            return stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .superHi(SuperHiRequest.newBuilder()
                            .setTargetUserId(targetUserId)
                            .setClientRequestId(clientRequestId)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public ListMatchesResponse listMatches(int pageSize, String pageToken) {
        try {
            ListMatchesRequest.Builder req = ListMatchesRequest.newBuilder().setPageSize(pageSize);
            if (pageToken != null) req.setPageToken(pageToken);
            return stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .listMatches(req.build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public GetQuotaResponse getQuota() {
        try {
            return stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .getQuota(GetQuotaRequest.newBuilder().build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public RecordVisitResponse recordVisit(long targetUserId) {
        try {
            return stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .recordVisit(RecordVisitRequest.newBuilder()
                            .setTargetUserId(targetUserId)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public ListVisitsOfMeResponse listVisitsOfMe(int pageSize, String pageToken) {
        try {
            ListVisitsOfMeRequest.Builder req = ListVisitsOfMeRequest.newBuilder().setPageSize(pageSize);
            if (pageToken != null) req.setPageToken(pageToken);
            return stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .listVisitsOfMe(req.build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }
}
