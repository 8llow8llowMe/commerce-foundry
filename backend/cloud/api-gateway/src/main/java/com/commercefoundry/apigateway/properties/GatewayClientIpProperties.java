package com.commercefoundry.apigateway.properties;

import java.util.Base64;
import java.util.List;
import com.commercefoundry.apigateway.ratelimit.CidrBlock;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 비로그인 요청의 클라이언트 IP 를 어떻게 정하고 키로 바꾸는지 ({@code security-guide.md} §7-3).
 *
 * <ul>
 *   <li>{@code trusted-proxies} 가 비면(기본) 어떤 프록시도 믿지 않는다 — {@code X-Forwarded-For} 를 무시하고 소켓의 원격 주소를 쓴다.
 *       LB · CDN 구성이 정해지면 그 주소 대역만 넣는다 ({@code GATEWAY_TRUSTED_PROXIES}, 콤마로 CIDR · 단일 주소). 잘못된 값이면 기동하지 않는다.</li>
 *   <li>{@code hash-key} 는 IP 를 Redis 키로 바꾸는 HMAC 키다({@code GATEWAY_CLIENT_IP_HASH_KEY}, Vault). base64 로 32바이트 이상이어야 하고,
 *       비었거나 {@code ${...}} 가 풀리지 않았으면 기동하지 않는다 — 키 없이 해시를 만들면 IPv4 는 전수 대입으로 되돌릴 수 있다.
 *       게이트웨이 인스턴스가 모두 같은 값을 써야 같은 IP 가 같은 버킷에 모인다.</li>
 * </ul>
 *
 * @param trustedProxies 믿는 프록시의 주소 · CIDR 목록
 * @param hashKey        base64 HMAC 키
 */
@ConfigurationProperties(prefix = "gateway.client-ip")
public record GatewayClientIpProperties(
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
        decodedKey(hashKey);
    }

    /** record 기본 {@code toString} 은 키를 평문으로 싣는다 — 바인딩 오류 · 디버그 로그로 새지 않게 가린다. */
    @Override
    public String toString() {
        return "GatewayClientIpProperties[trustedProxies=" + trustedProxies + ", hashKey=****]";
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
