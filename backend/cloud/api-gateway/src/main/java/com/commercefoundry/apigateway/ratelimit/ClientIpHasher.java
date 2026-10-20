package com.commercefoundry.apigateway.ratelimit;

import com.commercefoundry.apigateway.properties.GatewayClientIpProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 클라이언트 IP 를 Redis 키에 넣을 값으로 바꾼다 — HMAC-SHA256 의 앞 16바이트를 base64url(패딩 없음, 22자)로 쓴다 ({@code security-guide.md} §7-3).
 * 키 없는 해시는 IPv4 공간(약 43억)을 전수 대입하면 되돌릴 수 있어 쓰지 않는다. 같은 키를 쓰는 게이트웨이 인스턴스끼리 같은 IP 는 같은 값이 된다.
 *
 * <p>{@code gateway.client-ip.enabled} 가 꺼져 있으면(기본) 키 없이 뜨고, {@link #hash} 를 부르면 {@link IllegalStateException} 이다 —
 * 꺼진 상태에서는 필터가 부르지 않는다.
 */
@Component
public class ClientIpHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int KEPT_BYTES = 16;

    /** 꺼져 있으면 {@code null}. */
    private final SecretKeySpec key;

    public ClientIpHasher(GatewayClientIpProperties properties) {
        if (!properties.enabled()) {
            this.key = null;
            return;
        }
        this.key = new SecretKeySpec(properties.hashKeyBytes(), ALGORITHM);
        // 기동 때 한 번 만들어 본다 — 알고리즘 · 키 문제를 첫 요청이 아니라 기동에서 드러낸다
        newMac();
    }

    public String hash(String clientIp) {
        if (key == null) {
            // 키 · IP 는 메시지에 넣지 않는다
            throw new IllegalStateException("client ip hashing is disabled (gateway.client-ip.enabled=false)");
        }
        byte[] digest = newMac().doFinal(clientIp.getBytes(StandardCharsets.US_ASCII));
        byte[] kept = new byte[KEPT_BYTES];
        System.arraycopy(digest, 0, kept, 0, KEPT_BYTES);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(kept);
    }

    /** {@link Mac} 은 스레드 안전하지 않아 요청마다 만든다. */
    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("client ip hmac is not available", failure);
        }
    }
}
