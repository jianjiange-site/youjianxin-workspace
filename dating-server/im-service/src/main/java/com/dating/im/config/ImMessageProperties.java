package com.dating.im.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * before-send 业务规则配置(发消息扣金币 + 反导流)。
 *
 * <p>绑定前缀 {@code im.message};Nacos {@code refresh-enabled: true} 下 @ConfigurationProperties
 * bean 会被 Spring Cloud 自动 rebind,改 {@code coin-cost} / 开关不需重启。
 */
@Component
@ConfigurationProperties(prefix = "im.message")
public class ImMessageProperties {

    private final Charge charge = new Charge();
    private final AntiFunnel antiFunnel = new AntiFunnel();

    public Charge getCharge() {
        return charge;
    }

    public AntiFunnel getAntiFunnel() {
        return antiFunnel;
    }

    /** 发消息扣金币。 */
    public static class Charge {
        /** 总开关:false 时不扣币(放行)。 */
        private boolean enabled = true;
        /** 每条消息扣的金币数。 */
        private long coinCost = 1L;
        /** 扣币流水的业务原因(写入 coin_ledger,供审计)。 */
        private String reason = "im_message_send";
        /**
         * 扣币模式:true(默认)= before-send 同步只读余额准入 + 进程内异步扣减(热路径不压 DB 写);
         * false = 老的同步扣(在回调线程里直接 consumeCoins),作为出问题时的回退路径。
         * Nacos 热刷新,改后无需重启。
         */
        private boolean async = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getCoinCost() {
            return coinCost;
        }

        public void setCoinCost(long coinCost) {
            this.coinCost = coinCost;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public boolean isAsync() {
            return async;
        }

        public void setAsync(boolean async) {
            this.async = async;
        }
    }

    /** 反导流(站外联系方式 / 社交账号检测)。 */
    public static class AntiFunnel {
        /** 总开关:false 时不做正则检测(放行)。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
