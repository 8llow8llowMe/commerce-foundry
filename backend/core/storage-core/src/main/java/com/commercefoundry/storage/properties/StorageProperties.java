package com.commercefoundry.storage.properties;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * S3 호환 오브젝트 스토리지 설정 ({@code storage.*}). 서비스 {@code *PropertiesConfig} 가 {@code StoragePropertiesConfig} 를 {@code @Import} 한다.
 *
 * <p>기본값은 닫힌 쪽이다 — {@code enabled} 가 없으면 꺼진 채 뜨고, 나머지 값이 비어 있어도 기동한다(dev Vault 가 비어 있어도 뜬다).
 * 생성자는 검사하지 않고 정규화만 한다. 검사 규칙은 {@link #violations()} 한 곳에 있고, 기동 때는 {@code StorageStartupGuard} 가,
 * 직접 만들 때는 {@code ObjectStorageClients.create} 가 같은 규칙을 부른다.
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
 * @param pathStyleAccess {@code {endpoint}/{bucket}/{key}} 주소를 쓴다. 기본 true — MinIO 는 추가 설정 없이 path-style 이 된다
 *                        ({@code MINIO_DOMAIN} 을 두면 virtual-host 도 된다)
 * @param presignTtl      업로드 서명 URL 의 유효 시간. 기본 5분, 1분 이상 15분 이하
 * @param connectTimeout  연결 타임아웃. 켜면 필수 ({@code architecture-guide.md} §8)
 * @param readTimeout     응답(소켓 읽기) 타임아웃 — 바이트 사이 대기의 상한이다. 켜면 필수
 * @param apiCallTimeout  SDK 호출 한 번(재시도 포함)의 전체 시간 상한. 켜면 필수이고 {@code readTimeout} 이상이어야 한다.
 *                        게이트웨이 response-timeout(10s)보다 짧게 둔다 — 스토리지가 멈춰도 요청 스레드를 그보다 오래 잡지 않게
 */
@ConfigurationProperties(prefix = "storage")
public record StorageProperties(
    Boolean enabled,
    String endpoint, String region, String bucket,
    String accessKey, String secretKey,
    String publicBaseUrl,
    @DefaultValue("true") Boolean pathStyleAccess,
    @DefaultValue("5m") Duration presignTtl,
    Duration connectTimeout, Duration readTimeout, Duration apiCallTimeout
) {

    /** Binder 는 풀리지 않은 {@code ${...}} 를 문자 그대로 남긴다. 환경변수가 없으면 값이 이 문자열을 품는다. */
    public static final String PLACEHOLDER_PREFIX = "${";

    public static final String ENABLED = "storage.enabled";
    public static final String ENDPOINT = "storage.endpoint";
    public static final String REGION = "storage.region";
    public static final String BUCKET = "storage.bucket";
    public static final String ACCESS_KEY = "storage.access-key";
    public static final String SECRET_KEY = "storage.secret-key";
    public static final String PUBLIC_BASE_URL = "storage.public-base-url";
    public static final String PRESIGN_TTL = "storage.presign-ttl";
    public static final String CONNECT_TIMEOUT = "storage.connect-timeout";
    public static final String READ_TIMEOUT = "storage.read-timeout";
    public static final String API_CALL_TIMEOUT = "storage.api-call-timeout";

    public static final Duration MIN_PRESIGN_TTL = Duration.ofMinutes(1);
    public static final Duration MAX_PRESIGN_TTL = Duration.ofMinutes(15);

    /** S3 버킷 이름 규칙 — R2 · NCP · MinIO 도 같다. */
    private static final Pattern BUCKET_NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

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

    /**
     * 이 설정으로 클라이언트를 만들어도 되는지 본다. 비어 있으면 통과다.
     *
     * <p>켜져 있을 때:
     * <ul>
     *   <li>{@code endpoint} · {@code region} · {@code bucket} · {@code access-key} · {@code secret-key} · {@code public-base-url} 이 비었거나
     *       플레이스홀더가 풀리지 않았다 — 그대로 뜨면 첫 업로드에서야 실패한다.</li>
     *   <li>{@code endpoint} · {@code public-base-url} 이 절대 URL 이 아니거나, https 가 아니다(루프백 호스트만 http 허용 — 로컬 · 테스트 컨테이너),
     *       사용자 정보 · 쿼리 · 프래그먼트가 있다. https 관리자 · 스토어프론트 화면이 http 주소로 업로드하거나 이미지를 읽으면 브라우저가 막는다(mixed content).</li>
     *   <li>{@code bucket} 이 S3 버킷 이름 규칙(소문자 · 숫자 · {@code .} · {@code -}, 3~63자)에 맞지 않는다.</li>
     *   <li>{@code presign-ttl} 이 1분 이상 15분 이하가 아니다 — 짧으면 업로드가 끝나기 전에 만료되고, 길면 새어 나간 서명 URL 을 오래 쓸 수 있다.</li>
     *   <li>{@code connect-timeout} · {@code read-timeout} · {@code api-call-timeout} 이 없거나 0 이하다 — 타임아웃 없는 블로킹 호출 금지
     *       ({@code architecture-guide.md} §8). {@code api-call-timeout} 이 {@code read-timeout} 보다 짧다 — 응답 타임아웃이 뜻을 잃는다.</li>
     * </ul>
     *
     * <p>꺼져 있으면 채운 {@code public-base-url} 의 형식만 본다 — 꺼져도 공개 URL 은 만들므로 조용히 틀린 URL 을 내지 않게 한다.
     * 타임아웃 상한은 두지 않는다 — {@code RedisStartupGuard} 와 같이 양수만 본다. 권장값은 {@code bootstrap-conventions.md} Storage 다.
     *
     * @return 위반마다 프로퍼티 이름과 설명. 설명에 설정값(키 · 주소)을 넣지 않는다
     */
    public List<Violation> violations() {
        List<Violation> violations = new ArrayList<>();
        if (!enabled) {
            if (publicBaseUrl != null) {
                checkUrl(PUBLIC_BASE_URL, publicBaseUrl, violations);
            }
            return violations;
        }
        requireText(ENDPOINT, endpoint, violations);
        requireText(REGION, region, violations);
        requireText(BUCKET, bucket, violations);
        requireText(ACCESS_KEY, accessKey, violations);
        requireText(SECRET_KEY, secretKey, violations);
        requireText(PUBLIC_BASE_URL, publicBaseUrl, violations);

        if (!isAbsent(endpoint)) {
            checkUrl(ENDPOINT, endpoint, violations);
        }
        if (publicBaseUrl != null) {
            checkUrl(PUBLIC_BASE_URL, publicBaseUrl, violations);
        }
        if (!isAbsent(bucket) && !BUCKET_NAME.matcher(bucket).matches()) {
            violations.add(new Violation(BUCKET, describe(BUCKET) + " 가 버킷 이름 규칙에 맞지 않습니다 — 소문자 · 숫자 · '.' · '-' 로 3~63자이고 영숫자로 시작 · 끝납니다"));
        }
        if (presignTtl == null || presignTtl.compareTo(MIN_PRESIGN_TTL) < 0 || presignTtl.compareTo(MAX_PRESIGN_TTL) > 0) {
            violations.add(new Violation(PRESIGN_TTL,
                PRESIGN_TTL + "(업로드 서명 URL 유효 시간)는 1분 이상 15분 이하여야 합니다 — 짧으면 업로드 중에 만료되고, 길면 새어 나간 URL 을 오래 쓸 수 있습니다"));
        }
        requirePositive(CONNECT_TIMEOUT, "연결 타임아웃", connectTimeout, violations);
        requirePositive(READ_TIMEOUT, "응답 타임아웃", readTimeout, violations);
        requirePositive(API_CALL_TIMEOUT, "호출 전체 시간 상한", apiCallTimeout, violations);
        if (isPositive(apiCallTimeout) && isPositive(readTimeout) && apiCallTimeout.compareTo(readTimeout) < 0) {
            violations.add(new Violation(API_CALL_TIMEOUT, API_CALL_TIMEOUT + "(호출 전체 시간 상한)는 " + READ_TIMEOUT
                + " 이상이어야 합니다 — 짧으면 응답 타임아웃이 뜻을 잃고 느린 응답이 재시도 없이 끊깁니다"));
        }
        return violations;
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

    /** {@code storage.access-key} → {@code storage.access-key(환경변수 STORAGE_ACCESS_KEY)}. 이름이 Vault 키 이름과 같다. */
    public static String describe(String property) {
        return "%s(환경변수 %s)".formatted(property, property.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
    }

    /** 키가 로그 · 오류 메시지로 새지 않게 가린다. */
    @Override
    public String toString() {
        return "StorageProperties[enabled=" + enabled + ", endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket
            + ", accessKey=" + mask(accessKey) + ", secretKey=" + mask(secretKey) + ", publicBaseUrl=" + publicBaseUrl
            + ", pathStyleAccess=" + pathStyleAccess + ", presignTtl=" + presignTtl
            + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + ", apiCallTimeout=" + apiCallTimeout + "]";
    }

    /**
     * 어긋난 설정 하나.
     *
     * @param property 프로퍼티 이름({@code storage.endpoint} 등) — 같은 항목의 다른 위반(형식 오류)과 겹치지 않게 거를 때 쓴다
     * @param message  설명. 설정값을 싣지 않는다
     */
    public record Violation(String property, String message) {

    }

    private static void requireText(String property, String value, List<Violation> violations) {
        if (isAbsent(value)) {
            violations.add(new Violation(property,
                describe(property) + " 가 비었거나 플레이스홀더가 풀리지 않았습니다. Vault 에 등록한 값을 넣으세요 (secret-management.md)"));
        }
    }

    private static void requirePositive(String property, String label, Duration value, List<Violation> violations) {
        if (!isPositive(value)) {
            violations.add(new Violation(property, "%s(%s)이 없거나 0 이하입니다. 서비스 yml 에 양수로 명시하세요".formatted(property, label)));
        }
    }

    private static boolean isPositive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }

    private static void checkUrl(String property, String value, List<Violation> violations) {
        urlProblem(value).ifPresent(problem -> violations.add(new Violation(property, describe(property) + " " + problem)));
    }

    /** 절대 http(s) URL 이고, https 이거나 루프백 http 이며, 사용자 정보 · 쿼리 · 프래그먼트가 없어야 한다. 문제의 설명만 돌려주고 값은 싣지 않는다. */
    private static Optional<String> urlProblem(String value) {
        URI uri;
        try {
            uri = new URI(value.strip());
        } catch (URISyntaxException e) {
            return Optional.of("가 URL 형식이 아닙니다");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null) {
            return Optional.of("는 https://호스트 형식의 절대 URL 이어야 합니다");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return Optional.of("에 사용자 정보(user@) · 쿼리(?) · 프래그먼트(#)를 넣지 않습니다");
        }
        if (scheme.equals("http") && !LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            return Optional.of("는 https 여야 합니다 — http 는 루프백 호스트(localhost · 127.0.0.1 · [::1])만 허용합니다");
        }
        return Optional.empty();
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static String mask(String value) {
        return value == null ? "null" : "****";
    }
}
