package com.dating.payment.controller;

import com.dating.payment.service.WithdrawService;
import com.dating.youjianxin.proto.payment.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1")
public class WithdrawController {

    private final WithdrawService withdrawService;

    public WithdrawController(WithdrawService withdrawService) {
        this.withdrawService = withdrawService;
    }

    @PostMapping("/withdraw/accounts")
    public ResponseEntity<BindAccountResponse> bindAccount(@RequestBody Map<String, Object> body) {
        String accountTypeStr = (String) body.getOrDefault("type", "BANK_CARD");
        String accountIdentifier = (String) body.getOrDefault("account_identifier", "");
        String holderName = (String) body.getOrDefault("holder_name", "");

        WithdrawAccountType accountType;
        try {
            accountType = WithdrawAccountType.valueOf(accountTypeStr);
        } catch (IllegalArgumentException e) {
            accountType = WithdrawAccountType.BANK_CARD;
        }

        BindAccountRequest request = BindAccountRequest.newBuilder()
                .setType(accountType)
                .setAccountIdentifier(accountIdentifier)
                .setHolderName(holderName)
                .build();
        return ResponseEntity.ok(withdrawService.bindWithdrawAccount(request));
    }

    @PostMapping("/withdraw/request")
    public ResponseEntity<WithdrawResponse> withdraw(@RequestBody Map<String, Object> body) {
        long amountCent = body.containsKey("amount_cent")
                ? ((Number) body.get("amount_cent")).longValue() : 0;
        String accountId = (String) body.getOrDefault("account_id", "");
        String idempotencyKey = (String) body.getOrDefault("idempotency_key", "");

        WithdrawRequest request = WithdrawRequest.newBuilder()
                .setAmountCent(amountCent)
                .setAccountId(accountId)
                .setIdempotencyKey(idempotencyKey)
                .build();
        return ResponseEntity.ok(withdrawService.withdraw(request));
    }

    @GetMapping("/history")
    public ResponseEntity<GetHistoryResponse> getHistory(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String type) {
        GetHistoryRequest.Builder builder = GetHistoryRequest.newBuilder()
                .setPage(page)
                .setSize(size);
        if (type != null) {
            builder.setType(type);
        }
        return ResponseEntity.ok(withdrawService.getHistory(builder.build()));
    }
}
