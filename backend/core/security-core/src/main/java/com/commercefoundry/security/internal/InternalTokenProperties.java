package com.commercefoundry.security.internal;

import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 서비스 토큰 설정 ({@code security.internal.*}). 서비스 5종이 쓴다 — 게이트웨이는 internal API 를 부르지도 받지도 않는다.
 *
 * <p>비밀은 서비스 전부가 같은 값을 쓰는 HS256 키다 — base64 로 인코딩한 32바이트 이상 난수({@code openssl rand -base64 32}).
 * 아래 중 하나라도 어긋나면 <b>기동하지 않는다.</b> 메시지에는 프로퍼티 · 환경변수 이름만 넣고 비밀 내용은 넣지 않는다.
 *
 * <ul>
 *   <li>{@code service-id} 가 비었거나 플레이스홀더가 풀리지 않았거나 소문자 kebab({@link InternalTokenFormat#SERVICE_ID})이 아니다</li>
 *   <li>{@code token-ttl} 이 0 이하이거나 {@link InternalTokenFormat#MAX_TOKEN_TTL} 을 넘는다</li>
 *   <li>{@code secret} 이 비었다 · {@code ${INTERNAL_JWT_SECRET}} 가 풀리지 않았다 · base64 가 아니다 · 디코드하면 32바이트 미만이다</li>
 *   <li>{@code previous-secret} 은 비어도 된다. 채웠다면 {@code secret} 과 같은 규칙이다</li>
 * </ul>
 *
 * @param serviceId      이 서비스의 ID — Eureka 서비스 ID({@code spring.application.name}). 보내는 토큰의 {@code iss}, 받는 토큰에 들어 있어야 하는 {@code aud}
 * @param tokenTtl       보내는 토큰의 수명. 기본 60초, 최대 5분
 * @param secret         현재 서명 · 검증 비밀 ({@code INTERNAL_JWT_SECRET})
 * @param previousSecret 키 교체 중에만 채우는 이전 비밀 ({@code INTERNAL_JWT_SECRET_PREVIOUS}). 검증에만 쓴다. 평소에는 비어 있다
 */
@ConfigurationProperties(prefix = "security.internal")
public record InternalTokenProperties(
    String serviceId,
    @DefaultValue("60s") Duration tokenTtl,
    String secret,
    String previousSecret
) {

    /** HS256 키의 최소 길이 — 해시 출력(256비트) 이상 (RFC 7518 §3.2). */
    public static final int MINIMUM_SECRET_BYTES = 32;

    /** Binder 는 풀리지 않은 {@code ${...}} 를 문자 그대로 남긴다. 환경변수가 없으면 값이 이 문자열로 시작한다. */
    private static final String PLACEHOLDER_PREFIX = "${";
    private static final String HMAC_SHA256 = "HmacSHA256";

    /** 비밀이 들어오는 자리. 메시지에 프로퍼티와 환경변수 이름을 함께 적는다. */
    public enum Slot {

        SECRET("security.internal.secret", "INTERNAL_JWT_SECRET"),
        PREVIOUS_SECRET("security.internal.previous-secret", "INTERNAL_JWT_SECRET_PREVIOUS");

        private final String property;
        private final String environmentVariable;

        Slot(String property, String environmentVariable) {
            this.property = property;
            this.environmentVariable = environmentVariable;
        }

        public String environmentVariable() {
            return environmentVariable;
        }

        String describe() {
            return "%s(환경변수 %s)".formatted(property, environmentVariable);
        }
    }

    public InternalTokenProperties {
        if (serviceId == null || serviceId.isBlank() || serviceId.contains(PLACEHOLDER_PREFIX)) {
            throw new IllegalStateException("security.internal.service-id 가 비었거나 플레이스홀더가 풀리지 않았습니다."
                + " 서비스의 spring.application.name(Eureka 서비스 ID)을 넣습니다 (security-guide.md §9)");
        }
        if (!InternalTokenFormat.isServiceId(serviceId)) {
            throw new IllegalStateException(("security.internal.service-id '%s' 는 소문자 kebab(예: order-service)이어야 합니다"
                + " — Eureka 서비스 ID 이고, 다른 서비스가 보내는 토큰의 aud 와 허용 목록 권한 이름(SERVICE_<service-id>)이 됩니다").formatted(serviceId));
        }
        if (tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero() || tokenTtl.compareTo(InternalTokenFormat.MAX_TOKEN_TTL) > 0) {
            throw new IllegalStateException("security.internal.token-ttl 은 0 보다 크고 5분 이하여야 합니다"
                + " — 서비스 토큰은 요청마다 새로 서명하므로 길 이유가 없고, 길수록 가로챈 토큰을 다시 쓸 수 있는 시간이 늘어납니다");
        }
        decode(secret, Slot.SECRET);
        if (!isAbsent(previousSecret)) {
            decode(previousSecret, Slot.PREVIOUS_SECRET);
        }
    }

    /** 서명 · 검증에 쓰는 현재 비밀. */
    public SecretKey currentKey() {
        return decode(secret, Slot.SECRET);
    }

    /** 키 교체 중의 이전 비밀. 검증에만 쓴다. 비어 있거나 플레이스홀더가 풀리지 않았으면 없다. */
    public Optional<SecretKey> previousKey() {
        return isAbsent(previousSecret) ? Optional.empty() : Optional.of(decode(previousSecret, Slot.PREVIOUS_SECRET));
    }

    /** 비밀이 로그 · 오류 메시지로 새지 않게 가린다. */
    @Override
    public String toString() {
        return "InternalTokenProperties[serviceId=" + serviceId + ", tokenTtl=" + tokenTtl
            + ", secret=" + (secret == null ? "null" : "****") + ", previousSecret=" + (isAbsent(previousSecret) ? "(none)" : "****") + "]";
    }

    /** 값이 없거나 공백뿐이거나 플레이스홀더가 풀리지 않았으면 {@code true} 다. */
    static boolean isAbsent(String value) {
        return value == null || value.isBlank() || value.contains(PLACEHOLDER_PREFIX);
    }

    /** base64(표준 알파벳, 패딩은 있어도 없어도 된다)를 HS256 키로 읽는다. 실패 메시지 · 원인 예외에 비밀 내용을 싣지 않는다. */
    private static SecretKey decode(String value, Slot slot) {
        if (isAbsent(value)) {
            throw new IllegalStateException(slot.describe() + " 가 비었거나 플레이스홀더가 풀리지 않았습니다."
                + " openssl rand -base64 32 로 만든 값을 넣으세요 (security-guide.md §9)");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(slot.describe() + " 를 base64 로 읽을 수 없습니다."
                + " openssl rand -base64 32 로 만든 값을 그대로 넣으세요 (security-guide.md §9)");
        }
        if (bytes.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException("%s 를 디코드하면 %d바이트입니다. %d바이트 이상이어야 합니다 — openssl rand -base64 32 (security-guide.md §9)"
                .formatted(slot.describe(), bytes.length, MINIMUM_SECRET_BYTES));
        }
        return new SecretKeySpec(bytes, HMAC_SHA256);
    }
}
