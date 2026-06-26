package com.dating.common.alert.throttle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketTest {

    @Test
    void initiallyHoldsBurstCapacity() {
        TokenBucket b = new TokenBucket(5, 60);
        for (int i = 0; i < 5; i++) {
            assertThat(b.tryTake()).as("token %d", i).isTrue();
        }
        assertThat(b.tryTake()).isFalse();
    }

    @Test
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> new TokenBucket(0, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(10, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(10, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        // 6000/min = 100/s,每 10ms 补 1 token;sleep 50ms 应该至少补 4 个
        TokenBucket b = new TokenBucket(10, 6000);
        for (int i = 0; i < 10; i++) b.tryTake();
        assertThat(b.tryTake()).isFalse();
        Thread.sleep(60);
        int got = 0;
        for (int i = 0; i < 4; i++) {
            if (b.tryTake()) got++;
        }
        assertThat(got).as("refilled within 60ms at 100/s").isGreaterThanOrEqualTo(4);
    }

    @Test
    void cannotExceedCapacityAfterIdle() throws InterruptedException {
        TokenBucket b = new TokenBucket(3, 6000);
        Thread.sleep(50);
        for (int i = 0; i < 3; i++) {
            assertThat(b.tryTake()).isTrue();
        }
        // 突发取走 3 个后立刻第 4 次,无足够 elapsed 不应再有
        assertThat(b.tryTake()).isFalse();
    }
}
