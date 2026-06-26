package com.dating.payment.service;

import com.dating.youjianxin.proto.payment.BindAccountRequest;
import com.dating.youjianxin.proto.payment.BindAccountResponse;
import com.dating.youjianxin.proto.payment.GetHistoryRequest;
import com.dating.youjianxin.proto.payment.GetHistoryResponse;
import com.dating.youjianxin.proto.payment.WithdrawRequest;
import com.dating.youjianxin.proto.payment.WithdrawResponse;
import org.springframework.stereotype.Service;

/**
 * 提现业务：提现账户绑定、提现申请、历史查询。
 */
@Service
public class WithdrawService {

    public BindAccountResponse bindWithdrawAccount(BindAccountRequest request) {
        // TODO: 保存用户提现账号（加密存储）
        return BindAccountResponse.newBuilder()
                .setBase(Responses.notImplemented())
                .build();
    }

    public WithdrawResponse withdraw(WithdrawRequest request) {
        // TODO: 检查余额 → 冻结 → 创建提现记录 → 触发异步转账
        return WithdrawResponse.newBuilder()
                .setBase(Responses.notImplemented())
                .build();
    }

    public GetHistoryResponse getHistory(GetHistoryRequest request) {
        // TODO: 分页查询 user_wallet_entries 流水
        return GetHistoryResponse.newBuilder()
                .setBase(Responses.notImplemented())
                .build();
    }
}
