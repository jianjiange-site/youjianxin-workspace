package com.dating.im.handler;

import com.dating.im.client.PaymentGrpcClient;
import com.dating.im.client.PaymentGrpcClient.BalanceResult;
import com.dating.im.client.PaymentGrpcClient.ConsumeStatus;
import com.dating.im.client.UserProfileGrpcClient;
import com.dating.im.config.ImMessageProperties;
import com.dating.im.model.CallbackResult;
import com.dating.im.model.ImMessage;
import com.dating.im.model.event.MessageBeforeSendEvent;
import com.dating.im.util.ContactInfoDetector;
import com.dating.youjianxin.proto.im.MessageType;
import com.dating.youjianxin.proto.user.UserType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BeforeSendHandler} — DH-allow / anti-funnel / coin-charge paths.
 *
 * <p>默认 {@code charge.async=true}:同步只读余额准入(够则放行)+ 扣减交 {@link CoinChargeDispatcher}
 * 异步;{@code charge.async=false} 回退老的同步扣。两条路径都覆盖。
 * Uses the real {@link ContactInfoDetector} + {@link ImMessageProperties}; gRPC clients / dispatcher mocked.
 */
@ExtendWith(MockitoExtension.class)
class BeforeSendHandlerTest {

    private static final long BH = 100L;
    private static final long DH = 200L;

    @Mock private UserProfileGrpcClient userProfileClient;
    @Mock private PaymentGrpcClient paymentClient;
    @Mock private CoinChargeDispatcher coinChargeDispatcher;

    private ImMessageProperties properties;
    private BeforeSendHandler handler;

    @BeforeEach
    void setUp() {
        properties = new ImMessageProperties();
        handler = new BeforeSendHandler(userProfileClient, paymentClient, coinChargeDispatcher,
                new ContactInfoDetector(), properties, new SimpleMeterRegistry());
        // 默认:发送方是 BH(查不到 → 空 map → 回退 BH)
        lenient().when(userProfileClient.batchGetUserType(List.of(BH))).thenReturn(Map.of());
        // 默认 async 路径:余额充足放行
        lenient().when(paymentClient.getBalance(anyLong())).thenReturn(BalanceResult.ok(100L));
    }

    @Test
    void dhSenderAllowedWithoutCheckOrCharge() {
        when(userProfileClient.batchGetUserType(List.of(DH)))
                .thenReturn(Map.of(DH, UserType.USER_TYPE_DH));

        CallbackResult r = handler.handle(beforeSend(text(DH, "call me 234-567-8901")));

        assertEquals(0, r.code());
        assertTrue(r.success());
        // DH 放行:既不查正则也不预检/扣币
        verify(paymentClient, never()).getBalance(anyLong());
        verify(coinChargeDispatcher, never()).chargeAsync(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void bhContactInfoRejectedWithoutCharge() {
        CallbackResult r = handler.handle(beforeSend(text(BH, "add me on instagram.com/john")));

        assertEquals(5002, r.code());
        verify(paymentClient, never()).getBalance(anyLong());
        verify(coinChargeDispatcher, never()).chargeAsync(anyLong(), anyLong(), anyString(), anyString());
    }

    // --- 默认 async 路径:读余额准入 + 异步扣 ---

    @Test
    void bhNormalTextPassesPrecheckAndChargesAsync() {
        CallbackResult r = handler.handle(beforeSend(text(BH, "hello there")));

        assertEquals(0, r.code());
        // 准入通过 → 放行 + 异步扣(messageId 透传,幂等键在 dispatcher 内拼)
        verify(coinChargeDispatcher).chargeAsync(eq(BH), eq(1L), eq("im_message_send"), eq("m1"));
        verify(paymentClient, never()).consumeCoins(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void insufficientBalancePrecheckRejected() {
        when(paymentClient.getBalance(BH)).thenReturn(BalanceResult.ok(0L)); // cost=1 > 0

        CallbackResult r = handler.handle(beforeSend(text(BH, "hello there")));

        assertEquals(5003, r.code());
        verify(coinChargeDispatcher, never()).chargeAsync(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void precheckReadFailureFailsClosed() {
        when(paymentClient.getBalance(BH)).thenReturn(BalanceResult.failed());

        CallbackResult r = handler.handle(beforeSend(text(BH, "hello there")));

        assertEquals(5004, r.code());
        verify(coinChargeDispatcher, never()).chargeAsync(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void antiFunnelDisabledStillChargesAsync() {
        properties.getAntiFunnel().setEnabled(false);

        CallbackResult r = handler.handle(beforeSend(text(BH, "add me on instagram.com/john")));

        // 反导流关闭:联系方式不再拦,正常进准入放行 + 异步扣
        assertEquals(0, r.code());
        verify(coinChargeDispatcher).chargeAsync(eq(BH), eq(1L), anyString(), eq("m1"));
    }

    @Test
    void nonTextMessageSkipsAntiFunnelButChargesAsync() {
        // 图片消息不做正则检测,但 BH 仍走准入 + 异步扣
        CallbackResult r = handler.handle(beforeSend(
                ImMessage.builder().messageId("m1").fromUserId(String.valueOf(BH)).toUserId("200")
                        .type(MessageType.IMAGE).content("234-567-8901").build()));

        assertEquals(0, r.code());
        verify(coinChargeDispatcher).chargeAsync(eq(BH), eq(1L), anyString(), eq("m1"));
    }

    // --- 回退 sync 路径(charge.async=false):回调线程里直接 consumeCoins ---

    @Test
    void syncModeChargesInlineAndAllows() {
        properties.getCharge().setAsync(false);
        when(paymentClient.consumeCoins(anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(ConsumeStatus.OK);

        CallbackResult r = handler.handle(beforeSend(text(BH, "hello there")));

        assertEquals(0, r.code());
        verify(paymentClient).consumeCoins(eq(BH), eq(1L), eq("im_message_send"), eq("im-msg:m1"));
        verify(coinChargeDispatcher, never()).chargeAsync(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void syncModeInsufficientRejected() {
        properties.getCharge().setAsync(false);
        when(paymentClient.consumeCoins(anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(ConsumeStatus.INSUFFICIENT);

        CallbackResult r = handler.handle(beforeSend(text(BH, "hello there")));

        assertEquals(5003, r.code());
    }

    @Test
    void syncModePaymentUnavailableFailsClosed() {
        properties.getCharge().setAsync(false);
        when(paymentClient.consumeCoins(anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(ConsumeStatus.FAILED);

        CallbackResult r = handler.handle(beforeSend(text(BH, "hello there")));

        assertEquals(5004, r.code());
    }

    // --- 其它 ---

    @Test
    void unparseableSenderAllowed() {
        CallbackResult r = handler.handle(beforeSend(
                ImMessage.builder().messageId("m1").fromUserId("not-a-number").toUserId("200")
                        .type(MessageType.TEXT).content("hi").build()));

        assertEquals(0, r.code());
        verify(paymentClient, never()).getBalance(anyLong());
        verify(coinChargeDispatcher, never()).chargeAsync(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void chargeDisabledSkipsPayment() {
        properties.getCharge().setEnabled(false);

        CallbackResult r = handler.handle(beforeSend(text(BH, "hello there")));

        assertEquals(0, r.code());
        verify(paymentClient, never()).getBalance(anyLong());
        verify(coinChargeDispatcher, never()).chargeAsync(anyLong(), anyLong(), anyString(), anyString());
    }

    // --- helpers ---

    private static MessageBeforeSendEvent beforeSend(ImMessage msg) {
        return new MessageBeforeSendEvent(msg, "callbackBeforeSendSingleMsgCommand");
    }

    private static ImMessage text(long fromUserId, String content) {
        return ImMessage.builder()
                .messageId("m1")
                .fromUserId(String.valueOf(fromUserId))
                .toUserId("200")
                .type(MessageType.TEXT)
                .content(content)
                .provider("openim")
                .conversationType("C2C")
                .build();
    }
}
