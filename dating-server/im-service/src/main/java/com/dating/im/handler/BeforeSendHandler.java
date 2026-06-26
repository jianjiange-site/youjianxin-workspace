package com.dating.im.handler;

import com.dating.im.client.PaymentGrpcClient;
import com.dating.im.client.UserProfileGrpcClient;
import com.dating.im.config.ImMessageProperties;
import com.dating.im.model.CallbackResult;
import com.dating.im.model.ImMessage;
import com.dating.im.model.event.MessageBeforeSendEvent;
import com.dating.im.util.ContactInfoDetector;
import com.dating.im.util.UserIdUtils;
import com.dating.youjianxin.proto.im.MessageType;
import com.dating.youjianxin.proto.user.UserType;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Synchronous before-send hook (OpenIM {@code callbackBeforeSendSingleMsgCommand}).
 *
 * <p>返回 provider-中立的 {@link CallbackResult}:{@code ok()} 放行,{@code reject(code, msg)} 拦截
 * (reject code 取 OpenIM 合法区间 5000-9999,由 gateway 翻译成引擎拦截应答)。
 *
 * <p>职责(顺序固定):
 * <ol>
 *   <li><b>DH 放行</b>:数字人的 AI 回复(DH→BH)也会经 before-send 回调,先识别并放行,
 *       不检测、不扣币 —— 否则会拦 / 扣 AI 自己的回复。</li>
 *   <li><b>反导流</b>:仅 TEXT 消息,正则检测站外联系方式 / 社交账号,命中即拒发(不扣币)。</li>
 *   <li><b>付费聊天</b>:真人(BH)发消息扣金币(金币数 / 开关走 Nacos)。默认 {@code charge.async=true}:
 *       同步只读余额做<b>准入</b>(余额不足或读失败拒发),扣减交 {@link CoinChargeDispatcher} <b>异步</b>执行
 *       —— 避免带事务的扣币写压在 OpenIM 同步回调热路径(5s 预算)上击穿 deadline 拦消息。
 *       {@code charge.async=false} 回退老的同步扣(fail-close)。幂等键 = {@code im-msg:<messageId>}。</li>
 * </ol>
 *
 * 设计与边界详见 {@code docs/im-before-send-charge-anti-funnel.md}。
 */
@Component
public class BeforeSendHandler {

    private static final Logger log = LoggerFactory.getLogger(BeforeSendHandler.class);

    // reject code(OpenIM 5000-9999):由 gateway 直通成引擎拦截应答
    private static final int REJECT_CONTACT_INFO = 5002;
    private static final int REJECT_INSUFFICIENT_COINS = 5003;
    private static final int REJECT_PAYMENT_UNAVAILABLE = 5004;

    private static final String MSG_CONTACT_INFO = "Sharing off-platform contact info isn't allowed.";
    private static final String MSG_INSUFFICIENT_COINS = "You don't have enough coins to send this message.";
    private static final String MSG_PAYMENT_UNAVAILABLE = "Something went wrong. Please try again later.";

    /** Micrometer 计数:监控 / 告警口子(reason 维度区分拒发原因)。 */
    private static final String METRIC_REJECT = "im_before_send_reject_total";

    private final UserProfileGrpcClient userProfileClient;
    private final PaymentGrpcClient paymentClient;
    private final CoinChargeDispatcher coinChargeDispatcher;
    private final ContactInfoDetector contactInfoDetector;
    private final ImMessageProperties properties;
    private final MeterRegistry meterRegistry;

    public BeforeSendHandler(UserProfileGrpcClient userProfileClient,
                             PaymentGrpcClient paymentClient,
                             CoinChargeDispatcher coinChargeDispatcher,
                             ContactInfoDetector contactInfoDetector,
                             ImMessageProperties properties,
                             MeterRegistry meterRegistry) {
        this.userProfileClient = userProfileClient;
        this.paymentClient = paymentClient;
        this.coinChargeDispatcher = coinChargeDispatcher;
        this.contactInfoDetector = contactInfoDetector;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public CallbackResult handle(MessageBeforeSendEvent event) {
        ImMessage msg = event.message();
        Long senderId = UserIdUtils.parseLong(msg.fromUserId());

        // 1) 无法解析发送方:无法识别类型 / 扣费,放行(极少见,留计数兜底)
        if (senderId == null) {
            log.warn("before-send (allow): unparseable sender msgId={} from={}",
                    msg.messageId(), msg.fromUserId());
            countReject("sender_unparseable");
            return CallbackResult.ok();
        }

        // 2) 数字人(DH)发出的消息(AI 回复)直接放行:不检测、不扣币
        if (isSenderDH(senderId)) {
            log.info("before-send (allow): DH sender msgId={} from={}", msg.messageId(), senderId);
            return CallbackResult.ok();
        }

        // 3) 反导流:仅 TEXT 检测站外联系方式 / 社交账号
        if (properties.getAntiFunnel().isEnabled() && msg.type() == MessageType.TEXT) {
            String hit = contactInfoDetector.detect(msg.content());
            if (hit != null) {
                log.info("before-send (reject): contact-info msgId={} from={} to={} hit={}",
                        msg.messageId(), senderId, msg.toUserId(), hit);
                countReject("contact_info");
                return CallbackResult.reject(REJECT_CONTACT_INFO, MSG_CONTACT_INFO);
            }
        }

        // 4) 真人(BH)扣金币(fail-close);幂等键 = im-msg:<messageId>
        if (properties.getCharge().isEnabled()) {
            CallbackResult charge = chargeCoins(senderId, msg);
            if (charge != null) {
                return charge;
            }
        }

        log.info("before-send (allow): msgId={} from={} to={}",
                msg.messageId(), senderId, msg.toUserId());
        return CallbackResult.ok();
    }

    /** 扣币;成功(放行)返回 {@code null},失败返回对应 reject 结果。 */
    private CallbackResult chargeCoins(long senderId, ImMessage msg) {
        ImMessageProperties.Charge charge = properties.getCharge();
        String idempotencyKey = "im-msg:" + msg.messageId();

        // 回退路径(Nacos charge.async=false):老的同步扣,在回调线程里直接 consumeCoins
        if (!charge.isAsync()) {
            PaymentGrpcClient.ConsumeStatus status = paymentClient.consumeCoins(
                    senderId, charge.getCoinCost(), charge.getReason(), idempotencyKey);
            return mapConsumeStatus(status, senderId, msg);
        }

        // 默认路径:同步只读余额做准入(读路径稳、deadline 短),扣减异步化,不阻塞 before-send 热路径
        PaymentGrpcClient.BalanceResult balance = paymentClient.getBalance(senderId);
        if (!balance.ok()) {
            // 读失败:fail-close 拒发(与老行为一致,payment 读不到即拦),概率远低于写事务
            log.error("[CHARGE_FAIL] before-send reject: balance precheck failed msgId={} from={}",
                    msg.messageId(), senderId);
            countReject("charge_precheck_fail");
            return CallbackResult.reject(REJECT_PAYMENT_UNAVAILABLE, MSG_PAYMENT_UNAVAILABLE);
        }
        if (balance.balance() < charge.getCoinCost()) {
            log.info("before-send (reject): insufficient coins (precheck) msgId={} from={} balance={} cost={}",
                    msg.messageId(), senderId, balance.balance(), charge.getCoinCost());
            countReject("insufficient");
            return CallbackResult.reject(REJECT_INSUFFICIENT_COINS, MSG_INSUFFICIENT_COINS);
        }
        // 准入通过:放行,扣减交给异步执行器(尽力扣,失败告警,详见 CoinChargeDispatcher)
        coinChargeDispatcher.chargeAsync(senderId, charge.getCoinCost(), charge.getReason(), msg.messageId());
        return null;
    }

    /** 把同步 {@link PaymentGrpcClient.ConsumeStatus} 映射为放行(null)/ reject 结果。 */
    private CallbackResult mapConsumeStatus(PaymentGrpcClient.ConsumeStatus status, long senderId, ImMessage msg) {
        switch (status) {
            case OK:
                return null;
            case INSUFFICIENT:
                log.info("before-send (reject): insufficient coins msgId={} from={}", msg.messageId(), senderId);
                countReject("insufficient");
                return CallbackResult.reject(REJECT_INSUFFICIENT_COINS, MSG_INSUFFICIENT_COINS);
            case FAILED:
            default:
                // fail-close:payment-service 不可用也拒发;ERROR 日志 + 计数供告警
                log.error("[CHARGE_FAIL] before-send reject: payment-service unavailable msgId={} from={}",
                        msg.messageId(), senderId);
                countReject("charge_fail");
                return CallbackResult.reject(REJECT_PAYMENT_UNAVAILABLE, MSG_PAYMENT_UNAVAILABLE);
        }
    }

    private boolean isSenderDH(long senderId) {
        Map<Long, UserType> types = userProfileClient.batchGetUserType(List.of(senderId));
        // 缺失 = 用户不存在 / user-service RPC 失败,回退 BH(继续扣币)
        return types.get(senderId) == UserType.USER_TYPE_DH;
    }

    private void countReject(String reason) {
        meterRegistry.counter(METRIC_REJECT, "reason", reason).increment();
    }
}
