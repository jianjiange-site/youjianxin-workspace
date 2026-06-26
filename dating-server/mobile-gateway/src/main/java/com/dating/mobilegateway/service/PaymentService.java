package com.dating.mobilegateway.service;

import com.dating.mobilegateway.dto.BindAccountReq;
import com.dating.mobilegateway.dto.CreateOrderReq;
import com.dating.mobilegateway.dto.VerifyPaymentReq;
import com.dating.mobilegateway.dto.WithdrawReq;
import com.dating.mobilegateway.vo.BalanceVO;
import com.dating.mobilegateway.vo.CoinLedgerVO;
import com.dating.mobilegateway.vo.CoinsVO;
import com.dating.mobilegateway.vo.HistoryVO;
import com.dating.mobilegateway.vo.OrderVO;
import com.dating.mobilegateway.vo.ProductVO;
import com.dating.mobilegateway.vo.SubscriptionVO;

import java.util.List;

public interface PaymentService {

    List<ProductVO> getProducts();

    BalanceVO getBalance();

    OrderVO createOrder(CreateOrderReq req, long userId);

    OrderVO verifyPayment(VerifyPaymentReq req);

    SubscriptionVO getSubscription(long userId);

    String bindWithdrawAccount(BindAccountReq req);

    String withdraw(WithdrawReq req);

    List<HistoryVO> getHistory(int page, int size);

    CoinsVO getCoins(long userId);

    List<CoinLedgerVO> getCoinLedger(long userId, int page, int size);
}
