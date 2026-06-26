package com.dating.im.job;

import com.dating.im.service.PresenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 孤儿在线会话兜底清扫:只上线没下线(im-service 重启 / OpenIM 漏发 offline / 回调投递失败)的会话会
 * 一直留在在线集合,让在线人数虚高。定时把超阈值({@code im.presence.sweep.max-online-hours})的孤儿
 * 收口 + 移出集合。
 *
 * <p>受 {@code im.presence.sweep.enabled} 开关控制(默认开)。依赖 {@code @EnableScheduling}
 * (见 {@code ImApplication})。
 */
@Component
@ConditionalOnProperty(prefix = "im.presence.sweep", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class PresenceSweepJob {

    private static final Logger log = LoggerFactory.getLogger(PresenceSweepJob.class);

    private final PresenceService presenceService;

    public PresenceSweepJob(PresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @Scheduled(cron = "${im.presence.sweep.cron:0 */30 * * * *}")
    public void sweep() {
        try {
            presenceService.sweepStale();
        } catch (Exception ex) {
            log.error("presence sweep failed", ex);
        }
    }
}
