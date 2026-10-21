package com.commercefoundry.storage.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * S3 호환 오브젝트 스토리지 설정 ({@code storage.*}). 서비스 {@code *PropertiesConfig} 가 {@code StoragePropertiesConfig} 를 {@code @Import} 한다.
 *
 * <p>기본값은 닫힌 쪽이다 — {@code enabled} 가 없으면 꺼진 채 뜨고, 나머지 값이 비어 있어도 기동한다(dev Vault 가 비어 있어도 뜬다).
 * 켜면 {@code StorageStartupGuard} 가 빈 값 · 풀리지 않은 플레이스홀더 · 범위 밖 값을 모아 기동을 멈춘다. 이 record 는 검사하지 않고 정규화만 한다.
 *
 * <p>환경변수 이름이 프로퍼티와 1:1 이다(Boot relaxed binding) — {@code STORAGE_ENDPOINT} → {@code storage.endpoint},
 * {@code STORAGE_ACCESS_KEY} → {@code storage.access-key}. 값은 Vault 에 둔다 ({@code secret-management.md}).
 *
 * @param enabled         켜면 실제 스토리지를 부른다. 꺼져 있으면 I/O 메서드가 전부 {@code StorageException(DISABLED)} 다.
 *                        {@code Boolean} 으로 받아 빈 값({@code STORAGE_ENABLED=})을 꺼짐으로 정규화한다 — primitive 면 빈 문자열에서 바인딩이 실패한다
 * @param endpoint        S3 API 주소 ({@code STORAGE_ENDPOINT}). https 만 — 루프백(localhost · 127.0.0.1 · [::1])만 http 를 허용한다
 * @param region          서명 리전 ({@code STORAGE_REGION}). R2 는 {@code auto}
 * @param bucket          버킷 이름 ({@code STORAGE_BUCKET})
 * @param accessKey       접근 키 ID ({@code STORAGE_ACCESS_KEY})
 * @param secretKey       비밀 키 ({@code STORAGE_SECRET_KEY})
 * @param publicBaseUrl   공개 읽기 주소(CDN · 공개 버킷 도메인, {@code STORAGE_PUBLIC_BASE_URL}). 끝의 {@code /} 는 지운다.
 *                        비었거나 플레이스홀더가 풀리지 않았으면 {@code null} 이다
 * @param pathStyleAccess {@code {endpoint}/{bucket}/{key}} 주소를 쓴다. 기본 true — MinIO 계열은 path-style 만 된다
 * @param presignTtl      업로드 서명 URL 의 유효 시간. 기본 5분, 1분 이상 15분 이하
 * @param connectTimeout  연결 타임아웃. 켜면 필수 ({@code architecture-guide.md} §8)
 * @param readTimeout     응답(소켓 읽기) 타임아웃. 켜면 필수
 */
@ConfigurationProperties(prefix = "storage")
public record StorageProperties(
    Boolean enabled,
    String endpoint, String region, String bucket,
    String accessKey, String secretKey,
    String publicBaseUrl,
    @DefaultValue("true") Boolean pathStyleAccess,
    @DefaultValue("5m") Duration presignTtl,
    Duration connectTimeout, Duration readTimeout
) {

    /** Binder 는 풀리지 않은 {@code ${...}} 를 문자 그대로 남긴다. 환경변수가 없으면 값이 이 문자열을 품는다. */
    public static final String PLACEHOLDER_PREFIX = "${";

    public StorageProperties {
        enabled = Boolean.TRUE.equals(enabled);
        endpoint = strip(endpoint);
        region = strip(region);
        bucket = strip(bucket);
        accessKey = strip(accessKey);
        secretKey = strip(secretKey);
        publicBaseUrl = isAbsent(publicBaseUrl) ? null : stripTrailingSlashes(publicBaseUrl.strip());
        pathStyleAccess = pathStyleAccess == null || pathStyleAccess;
    }

    /** 값이 없거나 공백뿐이거나 플레이스홀더가 풀리지 않았으면 {@code true} 다. */
    public static boolean isAbsent(String value) {
        return value == null || value.isBlank() || value.contains(PLACEHOLDER_PREFIX);
    }

    /** 공개 주소 끝의 {@code /} 를 지운다 — 키를 {@code "/"} 하나로 이어 붙인다. */
    public static String stripTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    /** 키가 로그 · 오류 메시지로 새지 않게 가린다. */
    @Override
    public String toString() {
        return "StorageProperties[enabled=" + enabled + ", endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket
            + ", accessKey=" + mask(accessKey) + ", secretKey=" + mask(secretKey) + ", publicBaseUrl=" + publicBaseUrl
            + ", pathStyleAccess=" + pathStyleAccess + ", presignTtl=" + presignTtl
            + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static String mask(String value) {
        return value == null ? "null" : "****";
    }
}
