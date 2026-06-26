package com.dating.payment.controller;

import com.dating.payment.grpc.CoinGrpcService;
import com.dating.payment.grpc.GrpcAdapter;
import com.dating.youjianxin.proto.payment.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1")
public class CoinController {

    private static final Logger log = LoggerFactory.getLogger(CoinController.class);

    private final CoinGrpcService coinGrpcService;

    public CoinController(CoinGrpcService coinGrpcService) {
        this.coinGrpcService = coinGrpcService;
    }

    @PostMapping("/coins/balance")
    public ResponseEntity<GetCoinsResponse> getCoins(@RequestBody Map<String, Object> body) {
        log.info("[COIN] getCoins request: {}", body);
        long userId = toLong(body.get("user_id"));
        GetCoinsResponse response = GrpcAdapter.invoke(obs ->
                coinGrpcService.getCoins(GetCoinsRequest.newBuilder().setUserId(userId).build(), obs));
        log.info("[COIN] getCoins response: {}", response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/coins/ledger")
    public ResponseEntity<GetCoinLedgerResponse> getCoinLedger(@RequestBody Map<String, Object> body) {
        log.info("[COIN] getCoinLedger request: {}", body);
        long userId = toLong(body.get("user_id"));
        int page = toInt(body.getOrDefault("page", 1));
        int size = toInt(body.getOrDefault("size", 20));
        GetCoinLedgerResponse response = GrpcAdapter.invoke(obs ->
                coinGrpcService.getCoinLedger(GetCoinLedgerRequest.newBuilder()
                        .setUserId(userId).setPage(page).setSize(size).build(), obs));
        log.info("[COIN] getCoinLedger response: {}", response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/coins/add")
    public ResponseEntity<AddCoinsResponse> addCoins(@RequestBody Map<String, Object> body) {
        log.info("[COIN] addCoins request: {}", body);
        long userId = toLong(body.get("user_id"));
        long amount = toLong(body.get("amount"));
        String reason = (String) body.getOrDefault("reason", "");
        @SuppressWarnings("unchecked")
        Map<String, String> extra = (Map<String, String>) body.get("extra");
        AddCoinsRequest.Builder builder = AddCoinsRequest.newBuilder()
                .setUserId(userId).setAmount(amount).setReason(reason);
        if (extra != null) {
            builder.putAllExtra(extra);
        }
        AddCoinsResponse response = GrpcAdapter.invoke(obs ->
                coinGrpcService.addCoins(builder.build(), obs));
        log.info("[COIN] addCoins response: {}", response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/coins/consume")
    public ResponseEntity<ConsumeCoinsResponse> consumeCoins(@RequestBody Map<String, Object> body) {
        log.info("[COIN] consumeCoins request: {}", body);
        long userId = toLong(body.get("user_id"));
        long amount = toLong(body.get("amount"));
        String reason = (String) body.getOrDefault("reason", "");
        @SuppressWarnings("unchecked")
        Map<String, String> extra = (Map<String, String>) body.get("extra");
        ConsumeCoinsRequest.Builder builder = ConsumeCoinsRequest.newBuilder()
                .setUserId(userId).setAmount(amount).setReason(reason);
        if (extra != null) {
            builder.putAllExtra(extra);
        }
        ConsumeCoinsResponse response = GrpcAdapter.invoke(obs ->
                coinGrpcService.consumeCoins(builder.build(), obs));
        log.info("[COIN] consumeCoins response: {}", response);
        return ResponseEntity.ok(response);
    }

    private static long toLong(Object val) {
        if (val instanceof Number) return ((Number) val).longValue();
        if (val instanceof String) return Long.parseLong((String) val);
        return 0L;
    }

    private static int toInt(Object val) {
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) return Integer.parseInt((String) val);
        return 0;
    }
}
