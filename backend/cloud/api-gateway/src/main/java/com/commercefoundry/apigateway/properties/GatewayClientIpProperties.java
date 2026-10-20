package com.commercefoundry.apigateway.properties;

import java.util.Base64;
import java.util.List;
import com.commercefoundry.apigateway.ratelimit.CidrBlock;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 비로그인 요청을 클라이언트 IP 단위로 셀지와, 그 IP 를 어떻게 정하고 키로 바꾸는지 ({@code security-guide.md} §7-3).
 *
 * <ul>
 *   <li>{@code enabled} 는 토큰 없는 요청을 클라이언트 IP 단위로 셀지다. <b>기본은 끔</b>이고, 끄면 토큰 없는 요청은 세지 않는다(#89 이전과 같다).
 *       스토어프론트는 Next BFF 를 거쳐 오는데 BFF 가 클라이언트 IP 를 넘기지 않아 게이트웨이가 보는 원격 주소는 늘 BFF 다 —
 *       켜면 비로그인 요청 전체가 버킷 하나가 되고, access 쿠키가 만료된 회원(BFF 가 첫 요청을 토큰 없이 보낸다)이 401 재발급 대신 429 를 받는다 (#134 · #135).</li>
 *   <li>{@code trusted-proxies} 가 비면(기본) 어떤 프록시도 믿지 않는다 — {@code X-Forwarded-For} 를 무시하고 소켓의 원격 주소를 쓴다.
 *       BFF · LB · CDN 구성이 정해지면 그 주소 대역만 넣는다 ({@code GATEWAY_TRUSTED_PROXIES}, 콤마로 CIDR · 단일 주소). 잘못된 값이면 꺼져 있어도 기동하지 않는다.</li>
 *   <li>{@code hash-key} 는 IP 를 Redis 키로 바꾸는 HMAC 키다({@code GATEWAY_CLIENT_IP_HASH_KEY}, Vault). <b>켤 때만 필수</b>다 — base64 로 32바이트 이상이어야 하고,
 *       비었거나 {@code ${...}} 가 풀리지 않았으면 기동하지 않는다. 키 없이 해시를 만들면 IPv4 는 전수 대입으로 되돌릴 수 있다.
 *       게이트웨이 인스턴스가 모두 같은 값을 써야 같은 IP 가 같은 버킷에 모인다.</li>
 * </ul>
 *
 * @param enabled        토큰 없는 요청을 클라이언트 IP 단위로 센다. 기본 끔
 * @param trustedProxies 믿는 프록시의 주소 · CIDR 목록
 * @param hashKey        base64 HMAC 키. 켤 때만 필수
 */
@ConfigurationProperties(prefix = "gateway.client-ip")
public record GatewayClientIpProperties(
    @DefaultValue("false") boolean enabled,
    List<String> trustedProxies,
    String hashKey
) {

    private static final int MIN_KEY_BYTES = 32;

    public GatewayClientIpProperties {
        trustedProxies = trustedProxies == null ? List.of() : trustedProxies.stream()
            .map(String::strip)
            .filter(proxy -> !proxy.isEmpty())
            .toList();
        for (String proxy : trustedProxies) {
            try {
                CidrBlock.parse(proxy);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("gateway.client-ip.trusted-proxies 의 값이 주소 · CIDR 이 아닙니다: " + proxy, invalid);
            }
        }
        // 키는 쓸 때만 본다 — 끈 기본값에서는 키 없이 뜬다
        if (enabled) {
            decodedKey(hashKey);
        }
    }

    /** record 기본 {@code toString} 은 키를 평문으로 싣는다 — 바인딩 오류 · 디버그 로그로 새지 않게 가린다. */
    @Override
    public String toString() {
        return "GatewayClientIpProperties[enabled=" + enabled + ", trustedProxies=" + trustedProxies + ", hashKey=****]";
    }

    /** HMAC 키 바이트. 규칙에 맞지 않으면 {@link IllegalArgumentException} — 값 자체는 메시지에 넣지 않는다. */
    public byte[] hashKeyBytes() {
        return decodedKey(hashKey);
    }

    private static byte[] decodedKey(String hashKey) {
        if (hashKey == null || hashKey.isBlank() || hashKey.contains("${")) {
            throw new IllegalArgumentException("gateway.client-ip.hash-key(GATEWAY_CLIENT_IP_HASH_KEY)가 비었거나 풀리지 않았습니다.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(hashKey.strip());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException("gateway.client-ip.hash-key(GATEWAY_CLIENT_IP_HASH_KEY)는 base64 여야 합니다.");
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("gateway.client-ip.hash-key(GATEWAY_CLIENT_IP_HASH_KEY)는 " + MIN_KEY_BYTES + "바이트 이상이어야 합니다.");
        }
        return bytes;
    }
}
