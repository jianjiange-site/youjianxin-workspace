package com.dating.mobilegateway.service.impl;

import com.dating.mobilegateway.client.PaymentClient;
import com.dating.mobilegateway.dto.BindAccountReq;
import com.dating.mobilegateway.dto.CreateOrderReq;
import com.dating.mobilegateway.dto.VerifyPaymentReq;
import com.dating.mobilegateway.dto.WithdrawReq;
import com.dating.mobilegateway.service.PaymentService;
import com.dating.mobilegateway.vo.BalanceVO;
import com.dating.mobilegateway.vo.CoinLedgerVO;
import com.dating.mobilegateway.vo.CoinsVO;
import com.dating.mobilegateway.vo.HistoryVO;
import com.dating.mobilegateway.vo.OrderVO;
import com.dating.mobilegateway.vo.ProductVO;
import com.dating.mobilegateway.vo.SubscriptionVO;
import com.dating.youjianxin.proto.payment.GetBalanceResponse;
import com.dating.youjianxin.proto.payment.GetCoinLedgerResponse;
import com.dating.youjianxin.proto.payment.GetCoinsResponse;
import com.dating.youjianxin.proto.payment.GetHistoryResponse;
import com.dating.youjianxin.proto.payment.GetProductsResponse;
import com.dating.youjianxin.proto.payment.GetSubscriptionResponse;
import com.dating.youjianxin.proto.payment.OrderStatus;
import com.dating.youjianxin.proto.payment.PaymentMethod;
import com.dating.youjianxin.proto.payment.SubscriptionInfo;
import com.dating.youjianxin.proto.payment.WithdrawAccountType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentClient paymentClient;

    @Override
    public List<ProductVO> getProducts() {
        GetProductsResponse resp = paymentClient.getProducts();
        List<ProductVO> products = new ArrayList<>(resp.getProductsCount());
        for (var p : resp.getProductsList()) {
            products.add(new ProductVO(
                    p.getProductId(), p.getTitle(), p.getDescription(),
                    p.getPriceCent(), p.getCurrency()));
        }
        return products;
    }

    @Override
    public BalanceVO getBalance() {
        GetBalanceResponse resp = paymentClient.getBalance();
        return new BalanceVO(resp.getCurrency(),
                resp.getAvailableBalanceCent(), resp.getFrozenBalanceCent());
    }

    @Override
    public OrderVO createOrder(CreateOrderReq req, long userId) {
        PaymentMethod method = PaymentMethod.forNumber(req.paymentMethod());
        if (method == null || method == PaymentMethod.UNRECOGNIZED) {
            method = PaymentMethod.PAYPAL;
        }
        var resp = paymentClient.createOrder(req.productId(), method,
                req.currency(), req.platform(), userId);
        return new OrderVO(resp.getOrderId(),
                resp.getStatus().name(), resp.getStatusValue());
    }

    @Override
    public OrderVO verifyPayment(VerifyPaymentReq req) {
        var resp = paymentClient.verifyPayment(req.orderId(), req.receiptData(),
                req.signature() != null ? req.signature() : "",
                req.extOrderId() != null ? req.extOrderId() : "",
                req.paymentMethod());
        return new OrderVO(resp.getOrderId(),
                resp.getStatus().name(), resp.getStatusValue());
    }

    @Override
    public SubscriptionVO getSubscription(long userId) {
        GetSubscriptionResponse resp = paymentClient.getSubscription(userId);
        SubscriptionInfo sub = resp.hasSubscription() ? resp.getSubscription() : null;
        if (sub == null) {
            return new SubscriptionVO("FREE", 0, true, null);
        }
        Long expiresAtSeconds = sub.hasExpiresAt()
                ? sub.getExpiresAt().getSeconds() : null;
        return new SubscriptionVO(sub.getTier().name(), sub.getTierValue(),
                sub.getIsActive(), expiresAtSeconds);
    }

    @Override
    public String bindWithdrawAccount(BindAccountReq req) {
        WithdrawAccountType type = WithdrawAccountType.forNumber(req.type());
        if (type == null || type == WithdrawAccountType.UNRECOGNIZED) {
            type = WithdrawAccountType.PAYPAL_ACCOUNT;
        }
        var resp = paymentClient.bindWithdrawAccount(type,
                req.accountIdentifier(), req.holderName());
        return resp.getAccountId();
    }

    @Override
    public String withdraw(WithdrawReq req) {
        var resp = paymentClient.withdraw(req.amountCent(),
                req.accountId(), req.idempotencyKey());
        return resp.getWithdrawId();
    }

    @Override
    public List<HistoryVO> getHistory(int page, int size) {
        GetHistoryResponse resp = paymentClient.getHistory(page, size);
        List<HistoryVO> items = new ArrayList<>(resp.getItemsCount());
        for (var h : resp.getItemsList()) {
            items.add(new HistoryVO(h.getId(), h.getAmountCent(), h.getStatus(),
                    h.getType(),
                    h.hasCreateTime() ? h.getCreateTime().getSeconds() : null));
        }
        return items;
    }

    @Override
    public CoinsVO getCoins(long userId) {
        GetCoinsResponse resp = paymentClient.getCoins(userId);
        return new CoinsVO(resp.getUserId(), resp.getBalance());
    }

    @Override
    public List<CoinLedgerVO> getCoinLedger(long userId, int page, int size) {
        GetCoinLedgerResponse resp = paymentClient.getCoinLedger(userId, page, size);
        List<CoinLedgerVO> entries = new ArrayList<>(resp.getEntriesCount());
        for (var e : resp.getEntriesList()) {
            entries.add(new CoinLedgerVO(e.getId(), e.getUserId(),
                    e.getType(), e.getAmount(), e.getBalanceAfter(), e.getReason()));
        }
        return entries;
    }
}
