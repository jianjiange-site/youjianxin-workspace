package com.dating.payment.grpc;

import com.dating.payment.service.InfoService;
import com.dating.payment.service.PaymentService;
import com.dating.payment.service.SubscriptionService;
import com.dating.payment.service.WithdrawService;
import com.dating.youjianxin.proto.payment.*;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class PaymentGrpcService extends PaymentServiceGrpc.PaymentServiceImplBase {

    private final InfoService infoService;
    private final PaymentService paymentService;
    private final WithdrawService withdrawService;
    private final SubscriptionService subscriptionService;

    public PaymentGrpcService(InfoService infoService,
                              PaymentService paymentService,
                              WithdrawService withdrawService,
                              SubscriptionService subscriptionService) {
        this.infoService = infoService;
        this.paymentService = paymentService;
        this.withdrawService = withdrawService;
        this.subscriptionService = subscriptionService;
    }

    @Override
    public void getProducts(GetProductsRequest request,
                            StreamObserver<GetProductsResponse> responseObserver) {
        responseObserver.onNext(infoService.getProducts(request));
        responseObserver.onCompleted();
    }

    @Override
    public void getBalance(GetBalanceRequest request,
                           StreamObserver<GetBalanceResponse> responseObserver) {
        responseObserver.onNext(infoService.getBalance(request));
        responseObserver.onCompleted();
    }

    @Override
    public void createOrder(CreateOrderRequest request,
                            StreamObserver<CreateOrderResponse> responseObserver) {
        responseObserver.onNext(paymentService.createOrder(request.getUserId(), request, "", ""));
        responseObserver.onCompleted();
    }

    @Override
    public void verifyPayment(PaymentVerifyRequest request,
                              StreamObserver<PaymentVerifyResponse> responseObserver) {
        responseObserver.onNext(paymentService.verifyPayment(request));
        responseObserver.onCompleted();
    }

    @Override
    public void handleWebhook(WebhookRequest request,
                              StreamObserver<WebhookResponse> responseObserver) {
        responseObserver.onNext(paymentService.handleWebhook(request));
        responseObserver.onCompleted();
    }

    @Override
    public void bindWithdrawAccount(BindAccountRequest request,
                                    StreamObserver<BindAccountResponse> responseObserver) {
        responseObserver.onNext(withdrawService.bindWithdrawAccount(request));
        responseObserver.onCompleted();
    }

    @Override
    public void withdraw(WithdrawRequest request,
                         StreamObserver<WithdrawResponse> responseObserver) {
        responseObserver.onNext(withdrawService.withdraw(request));
        responseObserver.onCompleted();
    }

    @Override
    public void getHistory(GetHistoryRequest request,
                           StreamObserver<GetHistoryResponse> responseObserver) {
        responseObserver.onNext(withdrawService.getHistory(request));
        responseObserver.onCompleted();
    }

    @Override
    public void getSubscription(GetSubscriptionRequest request,
                                StreamObserver<GetSubscriptionResponse> responseObserver) {
        responseObserver.onNext(subscriptionService.getSubscription(request));
        responseObserver.onCompleted();
    }
}
