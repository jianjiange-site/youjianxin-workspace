package com.dating.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dating.payment.entity.CoinAccount;
import com.dating.payment.entity.CoinLedger;
import com.dating.payment.mapper.CoinAccountMapper;
import com.dating.payment.mapper.CoinLedgerMapper;
import com.google.protobuf.Timestamp;
import com.dating.youjianxin.proto.payment.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CoinService {

    private static final Logger log = LoggerFactory.getLogger(CoinService.class);

    private final CoinAccountMapper coinAccountMapper;
    private final CoinLedgerMapper coinLedgerMapper;

    public CoinService(CoinAccountMapper coinAccountMapper, CoinLedgerMapper coinLedgerMapper) {
        this.coinAccountMapper = coinAccountMapper;
        this.coinLedgerMapper = coinLedgerMapper;
    }

    public GetCoinsResponse getCoins(GetCoinsRequest request) {
        CoinAccount account = coinAccountMapper.selectById(request.getUserId());
        long total = account != null ? account.getBalance() + account.getPaidBalance() : 0L;
        return GetCoinsResponse.newBuilder()
                .setBase(BaseResponse.newBuilder().setCode(0).setMessage("OK"))
                .setUserId(request.getUserId())
                .setBalance(total)
                .build();
    }

    public GetCoinLedgerResponse getCoinLedger(GetCoinLedgerRequest request) {
        Page<CoinLedger> page = new Page<>(request.getPage(), request.getSize());
        LambdaQueryWrapper<CoinLedger> wrapper = new LambdaQueryWrapper<CoinLedger>()
                .eq(CoinLedger::getUserId, request.getUserId())
                .orderByDesc(CoinLedger::getCreatedAt);
        Page<CoinLedger> result = coinLedgerMapper.selectPage(page, wrapper);

        List<CoinLedgerEntry> entries = result.getRecords().stream()
                .map(this::toProtoEntry)
                .collect(Collectors.toList());

        return GetCoinLedgerResponse.newBuilder()
                .setBase(BaseResponse.newBuilder().setCode(0).setMessage("OK"))
                .addAllEntries(entries)
                .setTotal((int) result.getTotal())
                .build();
    }

    @Transactional
    public AddCoinsResponse addCoins(AddCoinsRequest request) {
        long userId = request.getUserId();
        long amount = request.getAmount();
        CoinAccount account = coinAccountMapper.selectById(userId);
        long freeAfter;
        long paidAfter;
        if (account == null) {
            account = new CoinAccount();
            account.setUserId(userId);
            account.setBalance(amount);
            account.setPaidBalance(0L);
            account.setVersion(0);
            coinAccountMapper.insert(account);
            freeAfter = amount;
            paidAfter = 0L;
        } else {
            freeAfter = account.getBalance() + amount;
            paidAfter = account.getPaidBalance();
            CoinAccount update = new CoinAccount();
            update.setUserId(userId);
            update.setBalance(freeAfter);
            update.setVersion(account.getVersion() + 1);
            int rows = coinAccountMapper.update(update, new LambdaQueryWrapper<CoinAccount>()
                    .eq(CoinAccount::getUserId, userId)
                    .eq(CoinAccount::getVersion, account.getVersion()));
            if (rows == 0) {
                throw new RuntimeException("Coin update conflict, retry");
            }
        }

        CoinLedger ledger = new CoinLedger();
        ledger.setUserId(userId);
        ledger.setType("INCOME");
        ledger.setAmount(amount);
        ledger.setPaidAmount(0L);
        ledger.setBalanceAfter(freeAfter);
        ledger.setPaidBalanceAfter(paidAfter);
        ledger.setReason(request.getReason());
        ledger.setExtra(request.getExtraMap());
        coinLedgerMapper.insertCoinLedger(ledger);

        log.info("[COIN] addCoins: userId={}, free={}, paid={}, total={}, reason={}",
                userId, amount, 0, freeAfter + paidAfter, request.getReason());
        return AddCoinsResponse.newBuilder()
                .setBase(BaseResponse.newBuilder().setCode(0).setMessage("OK"))
                .setBalance(freeAfter + paidAfter)
                .build();
    }

    @Transactional
    public AddCoinsResponse addPaidCoins(AddCoinsRequest request) {
        long userId = request.getUserId();
        long amount = request.getAmount();
        CoinAccount account = coinAccountMapper.selectById(userId);
        long freeAfter;
        long paidAfter;
        if (account == null) {
            account = new CoinAccount();
            account.setUserId(userId);
            account.setBalance(0L);
            account.setPaidBalance(amount);
            account.setVersion(0);
            coinAccountMapper.insert(account);
            freeAfter = 0L;
            paidAfter = amount;
        } else {
            freeAfter = account.getBalance();
            paidAfter = account.getPaidBalance() + amount;
            CoinAccount update = new CoinAccount();
            update.setUserId(userId);
            update.setPaidBalance(paidAfter);
            update.setVersion(account.getVersion() + 1);
            int rows = coinAccountMapper.update(update, new LambdaQueryWrapper<CoinAccount>()
                    .eq(CoinAccount::getUserId, userId)
                    .eq(CoinAccount::getVersion, account.getVersion()));
            if (rows == 0) {
                throw new RuntimeException("Coin update conflict, retry");
            }
        }

        CoinLedger ledger = new CoinLedger();
        ledger.setUserId(userId);
        ledger.setType("INCOME");
        ledger.setAmount(0L);
        ledger.setPaidAmount(amount);
        ledger.setBalanceAfter(freeAfter);
        ledger.setPaidBalanceAfter(paidAfter);
        ledger.setReason(request.getReason());
        ledger.setExtra(request.getExtraMap());
        coinLedgerMapper.insertCoinLedger(ledger);

        log.info("[COIN] addPaidCoins: userId={}, free={}, paid={}, total={}, reason={}",
                userId, 0, amount, freeAfter + paidAfter, request.getReason());
        return AddCoinsResponse.newBuilder()
                .setBase(BaseResponse.newBuilder().setCode(0).setMessage("OK"))
                .setBalance(freeAfter + paidAfter)
                .build();
    }

    @Transactional
    public ConsumeCoinsResponse consumeCoins(ConsumeCoinsRequest request) {
        long userId = request.getUserId();
        long amount = request.getAmount();
        // 幂等:idempotency_key 非空时先查历史流水,命中直接返上次结果(防 match-service SuperHi 重发重复扣)
        String idemKey = request.getIdempotencyKey();
        if (idemKey != null && !idemKey.isBlank()) {
            CoinLedger existing = coinLedgerMapper.findByIdempotencyKey(userId, idemKey);
            if (existing != null) {
                long lastTotal = existing.getBalanceAfter() + existing.getPaidBalanceAfter();
                log.info("[COIN] consumeCoins idempotent hit: userId={}, key={}, balanceAfter={}",
                        userId, idemKey, lastTotal);
                return ConsumeCoinsResponse.newBuilder()
                        .setBase(BaseResponse.newBuilder().setCode(0).setMessage("OK"))
                        .setBalance(lastTotal)
                        .build();
            }
        }

        CoinAccount account = coinAccountMapper.selectById(userId);
        long total = account != null ? account.getBalance() + account.getPaidBalance() : 0L;
        if (account == null || total < amount) {
            return ConsumeCoinsResponse.newBuilder()
                    .setBase(BaseResponse.newBuilder()
                            .setCode(3001)
                            .setMessage("Insufficient coins"))
                    .setBalance(total)
                    .build();
        }

        long freeTake;
        long paidTake;
        long freeAfter;
        long paidAfter;

        if (account.getBalance() >= amount) {
            freeTake = amount;
            paidTake = 0L;
            freeAfter = account.getBalance() - amount;
            paidAfter = account.getPaidBalance();
        } else {
            freeTake = account.getBalance();
            paidTake = amount - freeTake;
            freeAfter = 0L;
            paidAfter = account.getPaidBalance() - paidTake;
        }

        CoinAccount update = new CoinAccount();
        update.setUserId(userId);
        update.setBalance(freeAfter);
        update.setPaidBalance(paidAfter);
        update.setVersion(account.getVersion() + 1);
        int rows = coinAccountMapper.update(update, new LambdaQueryWrapper<CoinAccount>()
                .eq(CoinAccount::getUserId, userId)
                .eq(CoinAccount::getVersion, account.getVersion()));
        if (rows == 0) {
            throw new RuntimeException("Coin update conflict, retry");
        }

        CoinLedger ledger = new CoinLedger();
        ledger.setUserId(userId);
        ledger.setType("EXPENSE");
        ledger.setAmount(freeTake);
        ledger.setPaidAmount(paidTake);
        ledger.setBalanceAfter(freeAfter);
        ledger.setPaidBalanceAfter(paidAfter);
        ledger.setReason(request.getReason());
        ledger.setExtra(request.getExtraMap());
        if (idemKey != null && !idemKey.isBlank()) {
            ledger.setIdempotencyKey(idemKey);
        }
        try {
            coinLedgerMapper.insertCoinLedger(ledger);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // 并发重发:同一 idempotency_key 已经被另一线程扣过 → UNIQUE 约束兜底,反查上次结果
            log.warn("[COIN] consumeCoins idempotent race lost: userId={}, key={}, fallback to existing",
                    userId, idemKey, dup);
            CoinLedger existing = coinLedgerMapper.findByIdempotencyKey(userId, idemKey);
            if (existing == null) {
                throw dup;
            }
            long lastTotal = existing.getBalanceAfter() + existing.getPaidBalanceAfter();
            // 注:account 已被 update,但事务会回滚(throw)── 这里改为不抛,返回 existing 数据
            return ConsumeCoinsResponse.newBuilder()
                    .setBase(BaseResponse.newBuilder().setCode(0).setMessage("OK"))
                    .setBalance(lastTotal)
                    .build();
        }

        log.info("[COIN] consumeCoins: userId={}, freeTake={}, paidTake={}, totalAfter={}, reason={}, idemKey={}",
                userId, freeTake, paidTake, freeAfter + paidAfter, request.getReason(), idemKey);
        return ConsumeCoinsResponse.newBuilder()
                .setBase(BaseResponse.newBuilder().setCode(0).setMessage("OK"))
                .setBalance(freeAfter + paidAfter)
                .build();
    }

    private CoinLedgerEntry toProtoEntry(CoinLedger ledger) {
        CoinLedgerEntry.Builder builder = CoinLedgerEntry.newBuilder()
                .setId(ledger.getId())
                .setUserId(ledger.getUserId())
                .setType(ledger.getType())
                .setAmount(ledger.getAmount() + ledger.getPaidAmount())
                .setBalanceAfter(ledger.getBalanceAfter() + ledger.getPaidBalanceAfter())
                .setReason(ledger.getReason())
                .setCreatedAt(toProtoTimestamp(ledger.getCreatedAt()));
        if (ledger.getExtra() != null) {
            builder.putAllExtra(ledger.getExtra());
        }
        return builder.build();
    }

    private static Timestamp toProtoTimestamp(OffsetDateTime odt) {
        Instant instant = odt.toInstant();
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
