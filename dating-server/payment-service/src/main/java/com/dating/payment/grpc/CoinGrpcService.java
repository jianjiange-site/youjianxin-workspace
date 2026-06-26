package com.dating.payment.grpc;

import com.dating.payment.service.CoinService;
import com.dating.youjianxin.proto.payment.*;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class CoinGrpcService extends CoinServiceGrpc.CoinServiceImplBase {

    private final CoinService coinService;

    public CoinGrpcService(CoinService coinService) {
        this.coinService = coinService;
    }

    @Override
    public void getCoins(GetCoinsRequest request,
                         StreamObserver<GetCoinsResponse> responseObserver) {
        responseObserver.onNext(coinService.getCoins(request));
        responseObserver.onCompleted();
    }

    @Override
    public void getCoinLedger(GetCoinLedgerRequest request,
                              StreamObserver<GetCoinLedgerResponse> responseObserver) {
        responseObserver.onNext(coinService.getCoinLedger(request));
        responseObserver.onCompleted();
    }

    @Override
    public void addCoins(AddCoinsRequest request,
                         StreamObserver<AddCoinsResponse> responseObserver) {
        responseObserver.onNext(coinService.addCoins(request));
        responseObserver.onCompleted();
    }

    @Override
    public void addPaidCoins(AddCoinsRequest request,
                             StreamObserver<AddCoinsResponse> responseObserver) {
        responseObserver.onNext(coinService.addPaidCoins(request));
        responseObserver.onCompleted();
    }

    @Override
    public void consumeCoins(ConsumeCoinsRequest request,
                             StreamObserver<ConsumeCoinsResponse> responseObserver) {
        responseObserver.onNext(coinService.consumeCoins(request));
        responseObserver.onCompleted();
    }
}
