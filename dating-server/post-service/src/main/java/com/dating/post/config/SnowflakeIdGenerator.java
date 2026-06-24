package com.dating.post.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 雪花 ID 生成器(business primary key generator)。
 * <p>
 * 用作 {@code post_id} / {@code comment_id} 等业务主键(student-dev-guide §6.1)。
 * <p>
 * 64 bit 结构:
 * <pre>
 *  1 bit  符号位(始终 0)
 * 41 bits 毫秒级时间戳(从 EPOCH 开始,可用 ~69 年)
 * 10 bits workerId(0..1023,从配置注入)
 * 12 bits 序列号(同毫秒内 0..4095)
 * </pre>
 * 单进程内线程安全:同毫秒用 AtomicLong 自增,跨毫秒重置。
 * 时钟回拨容忍 5ms,超过抛异常让上游感知。
 */
@Component
public class SnowflakeIdGenerator {

    /** 起始时间:2026-01-01 UTC。 */
    private static final long EPOCH = 1767225600000L;

    private static final long WORKER_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER = ~(-1L << WORKER_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS;

    private final long workerId;
    private final AtomicLong lastTimestamp = new AtomicLong(-1L);
    private final AtomicLong sequence = new AtomicLong(0L);
    private final SecureRandom random = new SecureRandom();

    public SnowflakeIdGenerator(@Value("${app.snowflake.worker-id:1}") long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER) {
            throw new IllegalStateException(
                    "app.snowflake.worker-id 必须在 [0, " + MAX_WORKER + "],当前 " + workerId);
        }
        this.workerId = workerId;
    }

    /** 生成下一个 64 位业务主键。线程安全。 */
    public synchronized long nextId() {
        long now = Instant.now().toEpochMilli();
        long last = lastTimestamp.get();

        if (now < last) {
            if (last - now > 5) {
                throw new IllegalStateException("Clock moved backwards by " + (last - now) + "ms");
            }
            now = last;
        }

        long seq;
        if (now == last) {
            seq = sequence.incrementAndGet();
            if (seq > MAX_SEQUENCE) {
                // 同毫秒序列耗尽,自旋到下一毫秒
                while (now <= last) {
                    now = Instant.now().toEpochMilli();
                }
                sequence.set(0);
                seq = 0;
            }
        } else {
            // 跨毫秒序列号重置,随机偏移避免每毫秒第一个 ID 末尾恒 0
            seq = random.nextInt(16);
            sequence.set(seq);
        }

        lastTimestamp.set(now);
        return ((now - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_SHIFT)
                | seq;
    }
}
