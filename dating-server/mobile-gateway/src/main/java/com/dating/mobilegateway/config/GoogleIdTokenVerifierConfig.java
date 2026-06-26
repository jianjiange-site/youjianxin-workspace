package com.dating.mobilegateway.config;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

// Google idToken 校验器 bean。
//   - 仅当 gateway.third-party.enabled=true 时装配,本地 mock 路径不需要
//   - audience 白名单从 gateway.third-party.google.client-ids 取,逗号分隔多个值
//     (典型场景:Web Client ID + iOS Client ID + Android Client ID)
//   - GoogleIdTokenVerifier 内部缓存 Google JWKS 公钥,默认 1h TTL,这里复用单例
@Configuration
@ConfigurationProperties(prefix = "gateway.third-party.google")
@Data
public class GoogleIdTokenVerifierConfig {

    /** Google OAuth Client IDs 白名单,任何一个匹配 idToken.aud 即视为可信 audience */
    private List<String> clientIds = List.of();

    @Bean
    @ConditionalOnProperty(name = "gateway.third-party.enabled", havingValue = "true")
    public GoogleIdTokenVerifier googleIdTokenVerifier() {
        // iss 不显式设置:GoogleIdTokenVerifier 默认认 accounts.google.com 与 https://accounts.google.com
        return new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(clientIds)
                .build();
    }
}
