package com.dating.mobilegateway.filter;

import com.dating.mobilegateway.exception.BizException;
import com.dating.mobilegateway.exception.ErrorCodes;
import com.dating.mobilegateway.security.JwtVerifier;
import com.dating.mobilegateway.security.ParsedAccessToken;
import com.dating.mobilegateway.security.TokenBlacklistManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

// 纯单测:无 token / 坏 token / 黑名单命中 都应当短路返业务 code,
// 白名单路径放行,合法 token 注入请求属性放行下游。
class JwtAuthFilterTest {

    private JwtVerifier verifier;
    private TokenBlacklistManager blacklist;
    private ObjectMapper mapper;
    private JwtAuthFilter filter;

    @BeforeEach
    void init() {
        verifier = Mockito.mock(JwtVerifier.class);
        blacklist = Mockito.mock(TokenBlacklistManager.class);
        mapper = new ObjectMapper();
        filter = new JwtAuthFilter(verifier, blacklist, mapper);
    }

    @Test
    void rejectsRequestWithoutBearerHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/home/card");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        JsonNode body = mapper.readTree(resp.getContentAsByteArray());
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(body.get("code").asInt()).isEqualTo(ErrorCodes.UNAUTHENTICATED);
    }

    @Test
    void rejectsRequestWithMalformedBearer() throws Exception {
        when(verifier.parse(anyString()))
                .thenThrow(new BizException(ErrorCodes.TOKEN_INVALID, "bad"));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/home/card");
        req.addHeader("Authorization", "Bearer aaa.bbb.ccc");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        JsonNode body = mapper.readTree(resp.getContentAsByteArray());
        assertThat(body.get("code").asInt()).isEqualTo(ErrorCodes.TOKEN_INVALID);
    }

    @Test
    void rejectsBlacklistedJti() throws Exception {
        when(verifier.parse(anyString()))
                .thenReturn(new ParsedAccessToken(1L, "d", "jti-1", OffsetDateTime.now().plusMinutes(10)));
        when(blacklist.isBlacklisted("jti-1")).thenReturn(true);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/home/card");
        req.addHeader("Authorization", "Bearer x");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        JsonNode body = mapper.readTree(resp.getContentAsByteArray());
        assertThat(body.get("code").asInt()).isEqualTo(ErrorCodes.TOKEN_REVOKED);
    }

    @Test
    void passesValidTokenAndInjectsAttributes() throws Exception {
        when(verifier.parse(anyString()))
                .thenReturn(new ParsedAccessToken(99L, "dev-x", "jti-ok", OffsetDateTime.now().plusMinutes(10)));
        when(blacklist.isBlacklisted("jti-ok")).thenReturn(false);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/home/card");
        req.addHeader("Authorization", "Bearer good-token");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);

        assertThat(req.getAttribute("gateway.userId")).isEqualTo(99L);
        assertThat(req.getAttribute("gateway.deviceId")).isEqualTo("dev-x");
        assertThat(req.getAttribute("gateway.jti")).isEqualTo("jti-ok");
        // 走到下游 = response body 还是 0 长(没被 filter 写错误)
        assertThat(resp.getContentLength()).isEqualTo(0);
    }

    @Test
    void whitelistsAuthEndpoints() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login-device");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);
        // 白名单路径直接放行,响应未被 filter 改写,默认 200,无错误 body。
        assertThat(resp.getContentLength()).isEqualTo(0);
    }

    @Test
    void whitelistsActuatorAndSwagger() throws Exception {
        for (String path : new String[]{
                "/actuator/health",
                "/v3/api-docs",
                "/swagger-ui/index.html",
                "/api/v1/health/ping"
        }) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(req, resp, new MockFilterChain());
            assertThat(resp.getContentLength()).as("path=%s", path).isEqualTo(0);
        }
    }
}
