package com.dating.mobilegateway.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

// gateway.jwt 配置 + RSA 密钥加载(只在 gateway 进程内存,绝不落盘)。
//   - issuer / accessTtl / refreshTtl 给 JwtIssuer 用
//   - publicKeyBase64 / privateKeyBase64 是 PEM 去掉 header/footer/换行后 base64,Nacos 注入
//   - 本地空占位时生成一对临时 keypair (启动 warn),仅供 mvn package / 本地起服务用
@Slf4j
@Component
@ConfigurationProperties(prefix = "gateway.jwt")
@Getter
@Setter
public class JwtKeyConfig {

    private String issuer = "dating-mobile-gateway";
    private int accessTtlMinutes = 15;
    private int refreshTtlDays = 7;
    private String publicKeyBase64;
    private String privateKeyBase64;

    private RSAPublicKey publicKey;
    private RSAPrivateKey privateKey;
    private boolean ephemeral;

    @PostConstruct
    void init() {
        if (publicKeyBase64 != null && !publicKeyBase64.isBlank()
                && privateKeyBase64 != null && !privateKeyBase64.isBlank()) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                byte[] pub = Base64.getDecoder().decode(stripPem(publicKeyBase64));
                byte[] priv = Base64.getDecoder().decode(stripPem(privateKeyBase64));
                this.publicKey = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(pub));
                this.privateKey = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(priv));
                this.ephemeral = false;
                log.info("gateway.jwt RSA keypair loaded (issuer={}, accessTtlMin={}, refreshTtlDays={})",
                        issuer, accessTtlMinutes, refreshTtlDays);
                return;
            } catch (Exception e) {
                // 密钥配置错误:直接 fail-fast,而不是回退到 ephemeral —— 否则线上签出来的 token 重启就废。
                throw new IllegalStateException("gateway.jwt key load failed; check JWT_PUBLIC_KEY_BASE64 / JWT_PRIVATE_KEY_BASE64", e);
            }
        }
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            this.publicKey = (RSAPublicKey) kp.getPublic();
            this.privateKey = (RSAPrivateKey) kp.getPrivate();
            this.ephemeral = true;
            log.warn("gateway.jwt keys missing —— generated EPHEMERAL keypair for local boot. "
                    + "All issued tokens become invalid after restart. Set JWT_PUBLIC_KEY_BASE64 / "
                    + "JWT_PRIVATE_KEY_BASE64 (Nacos) for any non-local profile.");
        } catch (Exception e) {
            throw new IllegalStateException("RSA keypair generator unavailable", e);
        }
    }

    // 容忍 PEM 整段输入(去 BEGIN/END/换行/空白) —— 但推荐 Nacos 配置只放 base64 主体。
    private static String stripPem(String s) {
        return s.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s+", "");
    }
}
