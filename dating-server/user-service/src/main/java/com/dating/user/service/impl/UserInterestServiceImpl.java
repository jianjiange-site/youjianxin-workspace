package com.dating.user.service.impl;

import com.dating.user.entity.UserInterest;
import com.dating.user.exception.BizException;
import com.dating.user.exception.ErrorCodes;
import com.dating.user.manager.UserInterestManager;
import com.dating.user.service.UserInterestService;
import com.jianjiange.proto.user.UserInterestInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

// ReplaceUserInterests:全量替换 (DELETE + INSERT 同事务,manager.replaceAll 处理)。
// 限制:图 ≤ 9 / 文字 ≤ 50;判定 picObjectKey 非空 = 图,空 = 文字。
@Slf4j
@Service
@RequiredArgsConstructor
public class UserInterestServiceImpl implements UserInterestService {

    private static final int MAX_PIC = 9;
    private static final int MAX_TEXT = 50;

    private final UserInterestManager interestManager;

    @Override
    public int replaceAll(long callerUserId, List<UserInterestInput> inputs) {
        if (callerUserId <= 0) {
            throw new BizException(ErrorCodes.UNAUTHENTICATED, "missing caller user id");
        }
        List<UserInterest> entities = validateAndConvert(inputs);
        return interestManager.replaceAll(callerUserId, entities);
    }

    private List<UserInterest> validateAndConvert(List<UserInterestInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        int picCount = 0;
        int textCount = 0;
        List<UserInterest> out = new ArrayList<>(inputs.size());
        for (UserInterestInput in : inputs) {
            if (in == null) continue;
            String tabKey = in.getTabKey();
            String tagKey = in.getTagKey();
            String picKey = in.getPicObjectKey();
            if (tabKey == null || tabKey.isBlank() || tagKey == null || tagKey.isBlank()) {
                throw new BizException(ErrorCodes.INVALID_ARGUMENT, "interest tab_key / tag_key required");
            }
            boolean isPic = picKey != null && !picKey.isBlank();
            if (isPic) {
                picCount++;
            } else {
                textCount++;
            }
            UserInterest e = new UserInterest();
            e.setTabKey(tabKey);
            e.setTagKey(tagKey);
            e.setPicKey(isPic ? picKey : null);
            out.add(e);
        }
        if (picCount > MAX_PIC) {
            throw new BizException(ErrorCodes.INTEREST_PIC_LIMIT_EXCEEDED,
                    "pic interests exceed " + MAX_PIC + ": " + picCount);
        }
        if (textCount > MAX_TEXT) {
            throw new BizException(ErrorCodes.INTEREST_TEXT_LIMIT_EXCEEDED,
                    "text interests exceed " + MAX_TEXT + ": " + textCount);
        }
        return out;
    }
}
