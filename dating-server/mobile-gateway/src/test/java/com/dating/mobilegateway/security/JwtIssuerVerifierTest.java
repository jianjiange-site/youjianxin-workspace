package com.dating.mobilegateway.security;

import com.dating.mobilegateway.config.JwtKeyConfig;
import com.dating.mobilegateway.exception.BizException;
import com.dating.mobilegateway.exception.ErrorCodes;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 纯单测:用临时 RSA keypair 走 JwtIssuer → JwtVerifier 闭环,验 claim 正确;
// 再用过期 / 错签名 / 类型不符 / 损坏 token 验失败分支。无 PG / Redis / Spring 上下文依赖。
class JwtIssuerVerifierTest {

    private static JwtKeyConfig keyConfig;
    private static JwtIssuer issuer;
    private static JwtVerifier verifier;

    @BeforeAll
    static void setup() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        keyConfig = new JwtKeyConfig();
        keyConfig.setIssuer("test-issuer");
        keyConfig.setAccessTtlMinutes(15);
        keyConfig.setRefreshTtlDays(7);
        keyConfig.setPublicKey((RSAPublicKey) kp.getPublic());
        keyConfig.setPrivateKey((RSAPrivateKey) kp.getPrivate());
        issuer = new JwtIssuer(keyConfig);
        verifier = new JwtVerifier(keyConfig);
    }

    @Test
    void issuedAccessTokenVerifiesAndCarriesClaims() {
        IssuedAccessToken access = issuer.issueAccess(42L, "device-abc");
        assertThat(access.token()).isNotBlank();
        assertThat(access.jti()).isNotBlank();
        assertThat(access.expiresAt()).isAfter(java.time.OffsetDateTime.now().minusMinutes(1));

        ParsedAccessToken parsed = verifier.parse(access.token());
        assertThat(parsed.userId()).isEqualTo(42L);
        assertThat(parsed.deviceId()).isEqualTo("device-abc");
        assertThat(parsed.jti()).isEqualTo(access.jti());
    }

    @Test
    void issuedRefreshTokenHashMatchesPlainSha256() {
        IssuedRefreshToken refresh = issuer.issueRefresh();
        assertThat(refresh.plainToken()).isNotBlank();
        assertThat(refresh.tokenHash()).isNotBlank().hasSize(64);
        // 明文经 TokenHasher 算出的 hash 必须和 issuer 出的 hash 完全一致 ——
        // 反向证明 G5 验 refresh 时 sha256(plain) 能命中入库的 hash。
        assertThat(TokenHasher.sha256Hex(refresh.plainToken())).isEqualTo(refresh.tokenHash());
        assertThat(refresh.expiresAt()).isAfter(java.time.OffsetDateTime.now().plusDays(6));
    }

    @Test
    void parseFailsOnCorruptToken() {
        assertThatThrownBy(() -> verifier.parse("not.a.jwt"))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCodes.TOKEN_INVALID);
    }

    @Test
    void parseFailsOnBlankToken() {
        assertThatThrownBy(() -> verifier.parse(""))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCodes.TOKEN_INVALID);
    }

    @Test
    void parseFailsOnExpiredToken() {
        Instant past = Instant.now().minusSeconds(120);
        String expired = Jwts.builder()
                .issuer(keyConfig.getIssuer())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(past.minus(Duration.ofMinutes(15))))
                .expiration(Date.from(past))
                .claim("uid", 7L)
                .claim("did", "d")
                .claim("typ", "access")
                .signWith(keyConfig.getPrivateKey(), Jwts.SIG.RS256)
                .compact();
        assertThatThrownBy(() -> verifier.parse(expired))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCodes.TOKEN_EXPIRED);
    }

    @Test
    void parseFailsOnTokenTypeMismatch() {
        Instant now = Instant.now();
        String mistypedAsRefresh = Jwts.builder()
                .issuer(keyConfig.getIssuer())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)))
                .claim("uid", 7L)
                .claim("did", "d")
                .claim("typ", "refresh")
                .signWith(keyConfig.getPrivateKey(), Jwts.SIG.RS256)
                .compact();
        assertThatThrownBy(() -> verifier.parse(mistypedAsRefresh))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCodes.TOKEN_INVALID);
    }

    @Test
    void parseFailsOnWrongSignerKey() throws Exception {
        // 用另一把私钥签,公钥不匹配 → invalid。
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair other = g.generateKeyPair();
        Instant now = Instant.now();
        String forged = Jwts.builder()
                .issuer(keyConfig.getIssuer())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)))
                .claim("uid", 7L)
                .claim("did", "d")
                .claim("typ", "access")
                .signWith(other.getPrivate(), Jwts.SIG.RS256)
                .compact();
        assertThatThrownBy(() -> verifier.parse(forged))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCodes.TOKEN_INVALID);
    }
}
