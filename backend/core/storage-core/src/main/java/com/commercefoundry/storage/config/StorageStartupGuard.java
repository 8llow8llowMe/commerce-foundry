package com.commercefoundry.storage.config;

import com.commercefoundry.storage.properties.StorageProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * 스토리지 설정을 클라이언트 · 프로퍼티 빈이 만들어지기 전에 검사해, 어긋나면 기동을 멈춘다 ({@code RedisStartupGuard} 와 같은 방식).
 *
 * <p>{@code storage.enabled=true} 일 때:
 * <ul>
 *   <li>{@code endpoint} · {@code region} · {@code bucket} · {@code access-key} · {@code secret-key} · {@code public-base-url} 이 비었거나
 *       플레이스홀더가 풀리지 않았다 — 그대로 뜨면 첫 업로드에서야 실패한다.</li>
 *   <li>{@code endpoint} · {@code public-base-url} 이 절대 URL 이 아니거나, https 가 아니다(루프백 호스트만 http 허용 — 로컬 · 테스트 컨테이너),
 *       사용자 정보 · 쿼리 · 프래그먼트가 있다. https 관리자 · 스토어프론트 화면이 http 주소로 업로드하거나 이미지를 읽으면 브라우저가 막는다(mixed content).</li>
 *   <li>{@code bucket} 이 S3 버킷 이름 규칙(소문자 · 숫자 · {@code .} · {@code -}, 3~63자)에 맞지 않는다.</li>
 *   <li>{@code presign-ttl} 이 1분 이상 15분 이하가 아니다 — 짧으면 업로드가 끝나기 전에 만료되고, 길면 새어 나간 서명 URL 을 오래 쓸 수 있다.</li>
 *   <li>{@code connect-timeout} · {@code read-timeout} 이 없거나 0 이하거나 형식이 틀렸다 — 타임아웃 없는 블로킹 호출 금지 ({@code architecture-guide.md} §8).</li>
 * </ul>
 *
 * <p>꺼져 있으면(기본) 값이 비어도 기동한다 — 닫힌 기본값이다. 다만 {@code public-base-url} 이 채워져 있으면 공개 URL 만 만들 수 있으므로
 * 그 형식은 켜졌을 때와 같이 본다.
 *
 * <p>{@link BeanFactoryPostProcessor} 라 일반 빈보다 먼저 돈다. 프로퍼티는 {@link Binder} 로 읽어 환경변수({@code STORAGE_ENDPOINT} 등)도
 * Boot 와 같은 규칙으로 인식한다. 위반은 모아서 한 번에 알리고, 메시지에 설정값을 넣지 않는다.
 */
public final class StorageStartupGuard implements BeanFactoryPostProcessor {

    static final String ENABLED = "storage.enabled";
    static final String ENDPOINT = "storage.endpoint";
    static final String REGION = "storage.region";
    static final String BUCKET = "storage.bucket";
    static final String ACCESS_KEY = "storage.access-key";
    static final String SECRET_KEY = "storage.secret-key";
    static final String PUBLIC_BASE_URL = "storage.public-base-url";
    static final String PRESIGN_TTL = "storage.presign-ttl";
    static final String CONNECT_TIMEOUT = "storage.connect-timeout";
    static final String READ_TIMEOUT = "storage.read-timeout";

    static final Duration MIN_PRESIGN_TTL = Duration.ofMinutes(1);
    static final Duration MAX_PRESIGN_TTL = Duration.ofMinutes(15);
    private static final Duration DEFAULT_PRESIGN_TTL = Duration.ofMinutes(5);

    /** S3 버킷 이름 규칙 — R2 · NCP · MinIO 도 같다. */
    private static final Pattern BUCKET_NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final Environment environment;

    public StorageStartupGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        Binder binder = Binder.get(environment);
        List<String> violations = new ArrayList<>();

        Boolean enabled;
        try {
            enabled = binder.bind(ENABLED, Boolean.class).orElse(false);
        } catch (BindException e) {
            // 원인 메시지에 설정값이 들어 있다. 예외를 잇지 않고 이름만 알린다
            violations.add(ENABLED + "(환경변수 STORAGE_ENABLED)의 형식이 틀렸습니다. true 또는 false 를 적으세요");
            enabled = false;
        }

        if (enabled) {
            checkEnabled(binder, violations);
        } else {
            // 꺼져 있어도 공개 URL 은 만든다(StorageConfigurer). 채운 값이 틀렸으면 조용히 틀린 URL 을 내지 않게 막는다
            String publicBaseUrl = binder.bind(PUBLIC_BASE_URL, String.class).orElse(null);
            if (!StorageProperties.isAbsent(publicBaseUrl)) {
                urlProblem(publicBaseUrl).ifPresent(problem -> violations.add(describe(PUBLIC_BASE_URL) + " " + problem));
            }
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException("Storage 설정이 기동 조건에 맞지 않습니다 (bootstrap-conventions.md Storage)\n- " + String.join("\n- ", violations));
        }
    }

    private static void checkEnabled(Binder binder, List<String> violations) {
        String endpoint = requireText(binder, ENDPOINT, violations);
        requireText(binder, REGION, violations);
        String bucket = requireText(binder, BUCKET, violations);
        requireText(binder, ACCESS_KEY, violations);
        requireText(binder, SECRET_KEY, violations);
        String publicBaseUrl = requireText(binder, PUBLIC_BASE_URL, violations);

        if (endpoint != null) {
            urlProblem(endpoint).ifPresent(problem -> violations.add(describe(ENDPOINT) + " " + problem));
        }
        if (publicBaseUrl != null) {
            urlProblem(publicBaseUrl).ifPresent(problem -> violations.add(describe(PUBLIC_BASE_URL) + " " + problem));
        }
        if (bucket != null && !BUCKET_NAME.matcher(bucket).matches()) {
            violations.add(describe(BUCKET) + " 가 버킷 이름 규칙에 맞지 않습니다 — 소문자 · 숫자 · '.' · '-' 로 3~63자이고 영숫자로 시작 · 끝납니다");
        }

        bindPresignTtl(binder, violations).ifPresent(ttl -> {
            if (ttl.compareTo(MIN_PRESIGN_TTL) < 0 || ttl.compareTo(MAX_PRESIGN_TTL) > 0) {
                violations.add(PRESIGN_TTL + "(업로드 서명 URL 유효 시간)는 1분 이상 15분 이하여야 합니다 — 짧으면 업로드 중에 만료되고, 길면 새어 나간 URL 을 오래 쓸 수 있습니다");
            }
        });
        requirePositive(binder, CONNECT_TIMEOUT, "연결 타임아웃", violations);
        requirePositive(binder, READ_TIMEOUT, "응답 타임아웃", violations);
    }

    /** 값이 있으면 앞뒤 공백을 지워 돌려준다. 비었거나 플레이스홀더가 풀리지 않았으면 위반을 남기고 null 이다. */
    private static String requireText(Binder binder, String property, List<String> violations) {
        String value = binder.bind(property, String.class).orElse(null);
        if (StorageProperties.isAbsent(value)) {
            violations.add(describe(property) + " 가 비었거나 플레이스홀더가 풀리지 않았습니다. Vault 에 등록한 값을 넣으세요 (secret-management.md)");
            return null;
        }
        return value.strip();
    }

    /** 없으면 {@link StorageProperties} 의 기본값(5분)이다. */
    private static Optional<Duration> bindPresignTtl(Binder binder, List<String> violations) {
        try {
            return Optional.of(binder.bind(PRESIGN_TTL, Duration.class).orElse(DEFAULT_PRESIGN_TTL));
        } catch (BindException e) {
            violations.add(PRESIGN_TTL + "(업로드 서명 URL 유효 시간)의 형식이 틀렸습니다. 5m · 300s 처럼 적으세요");
            return Optional.empty();
        }
    }

    private static void requirePositive(Binder binder, String property, String label, List<String> violations) {
        Duration value;
        try {
            value = binder.bind(property, Duration.class).orElse(null);
        } catch (BindException e) {
            violations.add("%s(%s)의 형식이 틀렸습니다. 2s · 500ms 처럼 적으세요".formatted(property, label));
            return;
        }
        if (value == null || value.isNegative() || value.isZero()) {
            violations.add("%s(%s)이 없거나 0 이하입니다. 서비스 yml 에 양수로 명시하세요".formatted(property, label));
        }
    }

    /** 절대 http(s) URL 이고, https 이거나 루프백 http 이며, 사용자 정보 · 쿼리 · 프래그먼트가 없어야 한다. 문제의 설명만 돌려주고 값은 싣지 않는다. */
    static Optional<String> urlProblem(String value) {
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

    /** {@code storage.access-key} → {@code storage.access-key(환경변수 STORAGE_ACCESS_KEY)}. 이름이 Vault 키 이름과 같다. */
    private static String describe(String property) {
        return "%s(환경변수 %s)".formatted(property, property.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
    }
}
