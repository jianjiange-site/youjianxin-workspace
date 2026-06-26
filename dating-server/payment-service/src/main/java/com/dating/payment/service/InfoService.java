package com.dating.payment.service;

import com.dating.youjianxin.proto.payment.GetBalanceRequest;
import com.dating.youjianxin.proto.payment.GetBalanceResponse;
import com.dating.youjianxin.proto.payment.GetProductsRequest;
import com.dating.youjianxin.proto.payment.GetProductsResponse;
import com.dating.youjianxin.proto.payment.ProductInfo;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class InfoService {

    private record ProductDef(String id, String title, long priceCent, long coinAmount) {}
    private record SubscriptionDef(short tier, long durationDays) {}

    private static final List<ProductDef> PRODUCT_DEFS = List.of(
            // 金币直充
            new ProductDef("1", "100 金币",   99,   100),
            new ProductDef("2", "550 金币",   499,  550),
            new ProductDef("3", "1150 金币",  999,  1150),
            new ProductDef("4", "2400 金币",  1999, 2400),
            new ProductDef("5", "6250 金币",  4999, 6250),
            new ProductDef("6", "13000 金币", 9999, 13000),
            // 订阅商品：付费金币 + 档位升级 + 时效顺延
            new ProductDef("sub-weekly",  "周卡订阅 +1000付费金币",  999,  1000),
            new ProductDef("sub-monthly", "月卡订阅 +3000付费金币",  2999, 3000),
            new ProductDef("sub-yearly",  "年卡订阅 +8000付费金币",  7999, 8000)
    );

    private static final Map<String, SubscriptionDef> SUBSCRIPTION_MAP = Map.of(
            "sub-weekly",  new SubscriptionDef((short) 2, 7),
            "sub-monthly", new SubscriptionDef((short) 3, 30),
            "sub-yearly",  new SubscriptionDef((short) 4, 365)
    );

    private static final Map<String, ProductDef> PRODUCT_MAP = PRODUCT_DEFS.stream()
            .collect(Collectors.toMap(ProductDef::id, p -> p));

    public GetProductsResponse getProducts(GetProductsRequest request) {
        return GetProductsResponse.newBuilder()
                .setBase(Responses.ok())
                .addAllProducts(PRODUCT_DEFS.stream().map(p -> ProductInfo.newBuilder()
                        .setProductId(p.id())
                        .setTitle(p.title())
                        .setPriceCent(p.priceCent())
                        .setCurrency("USD")
                        .build()).toList())
                .build();
    }

    public GetBalanceResponse getBalance(GetBalanceRequest request) {
        return GetBalanceResponse.newBuilder()
                .setBase(Responses.notImplemented())
                .build();
    }

    public long getPriceCent(String productId) {
        ProductDef p = PRODUCT_MAP.get(productId);
        return p != null ? p.priceCent() : 99;
    }

    public long getCoinAmount(String productId) {
        ProductDef p = PRODUCT_MAP.get(productId);
        return p != null ? p.coinAmount() : 0;
    }

    public String getProductTitle(String productId) {
        ProductDef p = PRODUCT_MAP.get(productId);
        return p != null ? p.title() : productId;
    }

    public short getSubscriptionTier(String productId) {
        SubscriptionDef s = SUBSCRIPTION_MAP.get(productId);
        return s != null ? s.tier() : 0;
    }

    public long getSubscriptionDurationDays(String productId) {
        SubscriptionDef s = SUBSCRIPTION_MAP.get(productId);
        return s != null ? s.durationDays() : 0;
    }

    public boolean isSubscriptionProduct(String productId) {
        return SUBSCRIPTION_MAP.containsKey(productId);
    }
}
