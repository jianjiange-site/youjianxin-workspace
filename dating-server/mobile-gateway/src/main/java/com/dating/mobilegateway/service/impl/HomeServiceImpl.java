package com.dating.mobilegateway.service.impl;

import com.dating.mobilegateway.client.UserProfileClient;
import com.dating.mobilegateway.converter.HomeCardConverter;
import com.dating.mobilegateway.exception.BizException;
import com.dating.mobilegateway.exception.ErrorCodes;
import com.dating.mobilegateway.service.HomeService;
import com.dating.mobilegateway.vo.HomeCardVO;
import com.dating.mobilegateway.vo.UserProfileVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

// BFF 聚合 —— 目前只有一个下游 (UserProfile),为了演示并发 + 给后续 relation / im 留扩展骨架,
// 仍用 CompletableFuture.supplyAsync(bffExecutor) 包裹;单下游路径运行成本几乎为 0。
//
// 异常处理:CompletableFuture.join() 抛 CompletionException(cause=BizException) →
// 取 cause 再抛,GlobalExceptionHandler 兜底转 Result。
@Slf4j
@Service
public class HomeServiceImpl implements HomeService {

    private final UserProfileClient userProfileClient;
    private final ExecutorService bffExecutor;

    public HomeServiceImpl(UserProfileClient userProfileClient,
                           @Qualifier("bffExecutor") ExecutorService bffExecutor) {
        this.userProfileClient = userProfileClient;
        this.bffExecutor = bffExecutor;
    }

    @Override
    public HomeCardVO getCard(Long selfUserId, Long targetUserId) {
        if (targetUserId == null || targetUserId <= 0) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT, "targetUserId required");
        }
        CompletableFuture<UserProfileVO> profileF = CompletableFuture.supplyAsync(
                () -> userProfileClient.getProfile(targetUserId), bffExecutor);

        // 留扩展:future 列表多源时 CompletableFuture.allOf(profileF, relationF, ...).join()
        try {
            UserProfileVO target = profileF.get();
            if (target == null) {
                throw new BizException(ErrorCodes.NOT_FOUND, "target user not found");
            }
            return HomeCardConverter.assemble(selfUserId, target);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof BizException be) throw be;
            if (cause instanceof RuntimeException re) throw re;
            throw new BizException(ErrorCodes.SYSTEM_ERROR, "home card fetch failed");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCodes.SYSTEM_ERROR, "home card interrupted");
        }
    }
}
