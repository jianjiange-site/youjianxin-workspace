package com.dating.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.payment.entity.CoinLedger;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CoinLedgerMapper extends BaseMapper<CoinLedger> {

    @Insert("INSERT INTO coin_ledger (user_id, type, amount, paid_amount, balance_after, paid_balance_after, reason, extra, idempotency_key) " +
            "VALUES (#{userId}, #{type}, #{amount}, #{paidAmount}, #{balanceAfter}, #{paidBalanceAfter}, #{reason}, " +
            "CAST(#{extra,typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler} AS jsonb), #{idempotencyKey})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertCoinLedger(CoinLedger ledger);

    /**
     * 按 (user_id, idempotency_key) 查找历史流水 —— ConsumeCoins 幂等检查。
     * idempotency_key 在 V6 加 UNIQUE 索引,正常情况下最多 1 条。
     */
    @Select("SELECT * FROM coin_ledger WHERE user_id = #{userId} AND idempotency_key = #{key} LIMIT 1")
    CoinLedger findByIdempotencyKey(@Param("userId") Long userId, @Param("key") String key);
}
