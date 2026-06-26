package com.dating.mobilegateway.controller;

import com.dating.mobilegateway.constant.JwtClaims;
import com.dating.mobilegateway.dto.BindAccountReq;
import com.dating.mobilegateway.dto.CreateOrderReq;
import com.dating.mobilegateway.dto.VerifyPaymentReq;
import com.dating.mobilegateway.dto.WithdrawReq;
import com.dating.mobilegateway.exception.BizException;
import com.dating.mobilegateway.exception.ErrorCodes;
import com.dating.mobilegateway.exception.Result;
import com.dating.mobilegateway.service.PaymentService;
import com.dating.mobilegateway.vo.BalanceVO;
import com.dating.mobilegateway.vo.CoinLedgerVO;
import com.dating.mobilegateway.vo.CoinsVO;
import com.dating.mobilegateway.vo.HistoryVO;
import com.dating.mobilegateway.vo.OrderVO;
import com.dating.mobilegateway.vo.ProductVO;
import com.dating.mobilegateway.vo.SubscriptionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/payment")
@RequiredArgsConstructor
@Tag(name = "Payment", description = "支付 / 商品 / 余额 / 提现 / 订阅")
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping("/products")
    @Operation(summary = "获取商品列表")
    public Result<List<ProductVO>> products() {
        return Result.ok(paymentService.getProducts());
    }

    @GetMapping("/balance")
    @Operation(summary = "查询余额")
    public Result<BalanceVO> balance(HttpServletRequest http) {
        return Result.ok(paymentService.getBalance());
    }

    @PostMapping("/order")
    @Operation(summary = "创建支付订单")
    public Result<OrderVO> createOrder(@Valid @RequestBody CreateOrderReq req,
                                        HttpServletRequest http) {
        long userId = requireSelf(http);
        return Result.ok(paymentService.createOrder(req, userId));
    }

    @PostMapping("/verify")
    @Operation(summary = "验证支付结果")
    public Result<OrderVO> verifyPayment(@Valid @RequestBody VerifyPaymentReq req,
                                          HttpServletRequest http) {
        requireSelf(http);
        return Result.ok(paymentService.verifyPayment(req));
    }

    @GetMapping("/subscription")
    @Operation(summary = "查询订阅状态")
    public Result<SubscriptionVO> subscription(HttpServletRequest http) {
        long userId = requireSelf(http);
        return Result.ok(paymentService.getSubscription(userId));
    }

    @PostMapping("/withdraw/bind")
    @Operation(summary = "绑定提现账户")
    public Result<String> bindWithdraw(@Valid @RequestBody BindAccountReq req,
                                        HttpServletRequest http) {
        requireSelf(http);
        return Result.ok(paymentService.bindWithdrawAccount(req));
    }

    @PostMapping("/withdraw")
    @Operation(summary = "发起提现")
    public Result<String> withdraw(@Valid @RequestBody WithdrawReq req,
                                    HttpServletRequest http) {
        requireSelf(http);
        return Result.ok(paymentService.withdraw(req));
    }

    @GetMapping("/history")
    @Operation(summary = "提现 / 支付历史")
    public Result<List<HistoryVO>> history(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size,
                                           HttpServletRequest http) {
        requireSelf(http);
        return Result.ok(paymentService.getHistory(page, size));
    }

    @GetMapping("/coins")
    @Operation(summary = "查询金币余额")
    public Result<CoinsVO> coins(HttpServletRequest http) {
        long userId = requireSelf(http);
        return Result.ok(paymentService.getCoins(userId));
    }

    @GetMapping("/coins/ledger")
    @Operation(summary = "金币流水")
    public Result<List<CoinLedgerVO>> coinLedger(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  HttpServletRequest http) {
        long userId = requireSelf(http);
        return Result.ok(paymentService.getCoinLedger(userId, page, size));
    }

    private static long requireSelf(HttpServletRequest http) {
        Object selfAttr = http.getAttribute(JwtClaims.REQUEST_ATTR_USER_ID);
        if (!(selfAttr instanceof Long selfUserId) || selfUserId <= 0) {
            throw new BizException(ErrorCodes.UNAUTHENTICATED, "missing user context");
        }
        return selfUserId;
    }
}
