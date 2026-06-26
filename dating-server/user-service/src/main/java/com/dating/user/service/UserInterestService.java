package com.dating.user.service;

import com.jianjiange.proto.user.UserInterestInput;

import java.util.List;

// 兴趣全量替换:事务 DELETE + INSERT;图 ≤ 9 / 文字 ≤ 50。
// 返回实际入库条数 (saved_count)。
public interface UserInterestService {

    int replaceAll(long callerUserId, List<UserInterestInput> inputs);
}
