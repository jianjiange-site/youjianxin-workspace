package com.dating.common.bizid;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

public class BizIdGeneratorImpl implements BizIdGenerator {

    private static final long DATE_PREFIX_FACTOR = 1_000_000L; // env 位移到 6 位 date 之上
    private static final long MIN_SEQ_FACTOR = 10_000L;        // seq 默认占 4 位 (9999/天)
    private static final long SEQ_MAX = 999_999_999_999L;      // ~1e12/天 硬上限,仅防 BIGINT 溢出成负数
    // 全系统时区统一 UTC(见 CLAUDE.md):业务主键日期段按 UTC 滚动,跨环境/跨机房不串天
    private static final ZoneId DEFAULT_ZONE = ZoneOffset.UTC;

    private final BizIdSeqMapper mapper;
    private final BizIdProperties properties;
    private final Clock clock;

    public BizIdGeneratorImpl(BizIdSeqMapper mapper, BizIdProperties properties) {
        this(mapper, properties, Clock.system(DEFAULT_ZONE));
    }

    BizIdGeneratorImpl(BizIdSeqMapper mapper, BizIdProperties properties, Clock clock) {
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public long next(String tableName) {
        int datePart = todayYyMmDd();
        Long seq = mapper.upsertAndIncrement(tableName, datePart);
        if (seq == null || seq > SEQ_MAX) {
            throw new BizIdOverflowException(tableName, datePart, seq == null ? -1 : seq);
        }
        long prefix = (long) properties.getEnvPrefix() * DATE_PREFIX_FACTOR + datePart;
        return prefix * seqFactor(seq) + seq;
    }

    // seq <= 9999 占 4 位;超出按实际位数自动扩位,保证同表同天"数值越大=创建越晚"不被破坏
    private long seqFactor(long seq) {
        long factor = MIN_SEQ_FACTOR;
        while (seq >= factor) {
            factor *= 10;
        }
        return factor;
    }

    private int todayYyMmDd() {
        LocalDate today = LocalDate.now(clock);
        return (today.getYear() % 100) * 10_000
                + today.getMonthValue() * 100
                + today.getDayOfMonth();
    }
}
