package com.dating.common.bizid;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TestApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BizIdGeneratorIT {

    private final String tablePrefix = "__test_" + UUID.randomUUID().toString().substring(0, 8) + "_";

    @Autowired
    private BizIdSeqMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanRows() {
        jdbc.update("DELETE FROM biz_id_seq WHERE table_name LIKE ?", tablePrefix + "%");
    }

    @AfterAll
    void cleanupAll() {
        jdbc.update("DELETE FROM biz_id_seq WHERE table_name LIKE ?", tablePrefix + "%");
    }

    private BizIdGeneratorImpl generator(int envPrefix, LocalDate date) {
        BizIdProperties props = new BizIdProperties();
        props.setEnvPrefix(envPrefix);
        Clock fixed = Clock.fixed(
                date.atStartOfDay(ZoneOffset.UTC).toInstant(),
                ZoneOffset.UTC);
        return new BizIdGeneratorImpl(mapper, props, fixed);
    }

    private String table(String suffix) {
        return tablePrefix + suffix;
    }

    @Test
    void next_sameDay_increments() {
        var gen = generator(1, LocalDate.of(2026, 5, 27));
        String t = table("user");
        assertThat(gen.next(t)).isEqualTo(12_605_270_001L);
        assertThat(gen.next(t)).isEqualTo(12_605_270_002L);
        assertThat(gen.next(t)).isEqualTo(12_605_270_003L);
    }

    @Test
    void next_crossDay_resetsSeq() {
        String t = table("crossday");
        generator(1, LocalDate.of(2026, 5, 27)).next(t);
        generator(1, LocalDate.of(2026, 5, 27)).next(t);
        long firstOfNextDay = generator(1, LocalDate.of(2026, 5, 28)).next(t);
        assertThat(firstOfNextDay).isEqualTo(12_605_280_001L);
    }

    @Test
    void next_differentTables_independent() {
        var gen = generator(1, LocalDate.of(2026, 5, 27));
        String user = table("user");
        String post = table("post");
        long u1 = gen.next(user);
        long p1 = gen.next(post);
        long u2 = gen.next(user);
        assertThat(u1).isEqualTo(12_605_270_001L);
        assertThat(p1).isEqualTo(12_605_270_001L);
        assertThat(u2).isEqualTo(12_605_270_002L);
    }

    @Test
    void next_envPrefix_affectsHighBits() {
        long dev = generator(1, LocalDate.of(2026, 5, 27)).next(table("dev"));
        long prod = generator(2, LocalDate.of(2026, 5, 27)).next(table("prod"));
        assertThat(dev / 10_000_000_000L).isEqualTo(1L);
        assertThat(prod / 10_000_000_000L).isEqualTo(2L);
    }

    @Test
    void next_concurrent_uniqueIds() throws Exception {
        var gen = generator(1, LocalDate.of(2026, 5, 27));
        String t = table("concurrent");
        int threads = 20;
        int perThread = 50;
        var ids = new ConcurrentLinkedQueue<Long>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        var latch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < perThread; j++) {
                        ids.add(gen.next(t));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(ids).hasSize(threads * perThread);
        assertThat(new HashSet<>(ids)).hasSize(threads * perThread);
    }

    @Test
    void next_expandsBeyond9999() {
        var gen = generator(1, LocalDate.of(2026, 5, 27));
        String t = table("expand");
        jdbc.update("INSERT INTO biz_id_seq(table_name, date_part, seq) VALUES (?, ?, ?)",
                t, 260527, 9999L);
        long id = gen.next(t); // seq 涨到 10000,自动扩成 5 位
        assertThat(id).isEqualTo(126_052_710_000L);
        // 仍大于 4 位段最大 ID,同表同天顺序不乱
        assertThat(id).isGreaterThan(12_605_279_999L);
    }

    @Test
    void next_overflowsAtBigintGuard() {
        var gen = generator(1, LocalDate.of(2026, 5, 27));
        String t = table("bigintguard");
        jdbc.update("INSERT INTO biz_id_seq(table_name, date_part, seq) VALUES (?, ?, ?)",
                t, 260527, 999_999_999_999L);
        assertThatThrownBy(() -> gen.next(t)) // seq 涨到 1e12,越过 BIGINT 安全上限
                .isInstanceOf(BizIdOverflowException.class)
                .hasMessageContaining("overflow")
                .hasMessageContaining(t);
    }
}
