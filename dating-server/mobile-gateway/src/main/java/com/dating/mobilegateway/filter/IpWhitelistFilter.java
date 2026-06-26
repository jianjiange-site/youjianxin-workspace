package com.dating.mobilegateway.filter;

import com.dating.mobilegateway.exception.ErrorCodes;
import com.dating.mobilegateway.exception.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * IP whitelist filter for callback endpoints.
 *
 * <p>OpenIM callbacks have no built-in auth (no signature, no token).
 * This filter restricts access to known OpenIM server IPs only.
 *
 * <p>Entries accept plain IPs ({@code 127.0.0.1}) and CIDR ranges
 * ({@code 172.16.0.0/12}); a plain IP is treated as a /32 (IPv4) or /128 (IPv6).
 * OpenIM shares one Docker network with the gateway and connects directly
 * (no proxy, no {@code X-Forwarded-For}), so the source is its container IP —
 * which Docker assigns dynamically, hence range matching rather than exact IPs.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 26)
public class IpWhitelistFilter extends OncePerRequestFilter {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final List<CidrEntry> whitelist;
    private final ObjectMapper objectMapper;

    public IpWhitelistFilter(@Value("${gateway.callback.ip-whitelist}") String whitelistCsv,
                             ObjectMapper objectMapper) {
        this.whitelist = Arrays.stream(whitelistCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(CidrEntry::parse)
                .toList();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !MATCHER.match("/callback/**", req.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String ip = clientIp(req);

        if (isWhitelisted(ip)) {
            chain.doFilter(req, resp);
            return;
        }

        log.warn("IP not in callback whitelist: ip={} uri={}", ip, req.getRequestURI());
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        byte[] body = objectMapper.writeValueAsBytes(
                Result.fail(ErrorCodes.FORBIDDEN, "IP not in whitelist: " + ip));
        resp.getOutputStream().write(body);
    }

    private boolean isWhitelisted(String ip) {
        byte[] addr;
        try {
            addr = InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return false;
        }
        for (CidrEntry entry : whitelist) {
            if (entry.matches(addr)) {
                return true;
            }
        }
        return false;
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return req.getRemoteAddr();
    }

    /** A whitelist entry: base address + prefix length (CIDR). Plain IP = full-length prefix. */
    private record CidrEntry(byte[] base, int prefixBits) {

        static CidrEntry parse(String spec) {
            int slash = spec.indexOf('/');
            String addrPart = slash >= 0 ? spec.substring(0, slash) : spec;
            byte[] base;
            try {
                base = InetAddress.getByName(addrPart).getAddress();
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid callback IP whitelist entry: " + spec, e);
            }
            int maxBits = base.length * 8;
            int prefix = slash >= 0 ? Integer.parseInt(spec.substring(slash + 1).trim()) : maxBits;
            if (prefix < 0 || prefix > maxBits) {
                throw new IllegalArgumentException("Invalid CIDR prefix in whitelist entry: " + spec);
            }
            return new CidrEntry(base, prefix);
        }

        boolean matches(byte[] addr) {
            if (addr.length != base.length) {
                return false; // different address family (IPv4 vs IPv6)
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addr[i] != base[i]) {
                    return false;
                }
            }
            int remBits = prefixBits % 8;
            if (remBits > 0) {
                int mask = (0xFF << (8 - remBits)) & 0xFF;
                return (addr[fullBytes] & mask) == (base[fullBytes] & mask);
            }
            return true;
        }
    }
}
