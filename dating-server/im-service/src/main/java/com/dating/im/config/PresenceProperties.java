package com.dating.im.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Presence(在线状态)配置,绑定前缀 {@code im.presence}。
 */
@Component
@ConfigurationProperties(prefix = "im.presence")
public class PresenceProperties {

    private final Sweep sweep = new Sweep();

    public Sweep getSweep() {
        return sweep;
    }

    /** 孤儿在线会话兜底清扫(只 online 没 offline 的会一直留在在线集合,让在线人数虚高)。 */
    public static class Sweep {
        /** 总开关:false 时不跑定时清扫。 */
        private boolean enabled = true;
        /** 在线超过该小时数仍未下线即视为孤儿,按阈值封顶时长收口。需足够大以免误杀真实长连。 */
        private int maxOnlineHours = 26;
        /** 清扫 cron(默认每 30 分钟)。 */
        private String cron = "0 */30 * * * *";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxOnlineHours() {
            return maxOnlineHours;
        }

        public void setMaxOnlineHours(int maxOnlineHours) {
            this.maxOnlineHours = maxOnlineHours;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }
    }
}
