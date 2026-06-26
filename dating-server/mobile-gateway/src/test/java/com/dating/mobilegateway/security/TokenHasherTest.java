package com.dating.mobilegateway.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// SHA-256 hex (lowercase) 已知向量验证;同时校验 null/blank 入参 fail-fast。
class TokenHasherTest {

    @Test
    void sha256OfEmptyStringIsRejected() {
        assertThatThrownBy(() -> TokenHasher.sha256Hex(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TokenHasher.sha256Hex(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sha256KnownVector_abc() {
        // 已知:sha256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertThat(TokenHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sha256IsStable() {
        String h1 = TokenHasher.sha256Hex("refresh-token-sample");
        String h2 = TokenHasher.sha256Hex("refresh-token-sample");
        assertThat(h1).isEqualTo(h2).hasSize(64);
    }
}
