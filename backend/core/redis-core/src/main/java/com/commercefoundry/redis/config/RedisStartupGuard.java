package com.commercefoundry.redis.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Redis 접속 설정을 접속 팩토리가 만들어지기 전에 검사해, 어긋나면 기동을 멈춘다 ({@code coding-conventions.md} §9-7).
 *
 * <ul>
 *   <li>{@code password} 가 비었거나 공백이거나 플레이스홀더가 풀리지 않았다 — 로컬 compose 도 requirepass 라 예외가 없다.</li>
 *   <li>{@code host} 에 풀리지 않은 플레이스홀더가 남았다 — 기동은 되고 첫 명령에서야 실패한다.</li>
 *   <li>{@code timeout}(명령)·{@code connect-timeout}(연결)이 없거나 0 이하거나 형식이 틀렸다 — 타임아웃 없는 블로킹 호출 금지
 *       ({@code architecture-guide.md} §8).</li>
 *   <li>{@code url} 이 설정됐다 — url 을 쓰면 Boot 가 host·port·password 를 무시해 비밀번호 검사가 무력해진다.</li>
 *   <li>{@code sentinel}·{@code cluster}·{@code masterreplica} 아래 값이 있다 — standalone 만 지원한다.
 *       다른 모드는 노드·비밀번호를 다른 프로퍼티에서 읽어 위 검사를 비켜 간다.</li>
 * </ul>
 *
 * <p>{@link BeanFactoryPostProcessor} 라 일반 빈보다 먼저 돈다. 프로퍼티는 {@link Binder} 로 읽어 환경변수
 * ({@code SPRING_DATA_REDIS_PASSWORD} 등)도 Boot 와 같은 규칙으로 인식한다. 위반은 모아서 한 번에 알리고, 메시지에 설정값을 넣지 않는다.
 */
public final class RedisStartupGuard implements BeanFactoryPostProcessor {

    static final String PASSWORD = "spring.data.redis.password";
    static final String HOST = "spring.data.redis.host";
    static final String TIMEOUT = "spring.data.redis.timeout";
    static final String CONNECT_TIMEOUT = "spring.data.redis.connect-timeout";
    static final String URL = "spring.data.redis.url";

    private static final String PREFIX = "spring.data.redis.";
    private static final List<String> UNSUPPORTED_MODES = List.of("sentinel", "cluster", "masterreplica");

    /** Binder 는 풀리지 않은 {@code ${...}} 를 문자 그대로 남긴다. 환경변수가 없으면 값이 이 문자열을 품는다. */
    private static final String PLACEHOLDER_PREFIX = "${";

    private final Environment environment;

    public RedisStartupGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        Binder binder = Binder.get(environment);
        List<String> violations = new ArrayList<>();

        String password = binder.bind(PASSWORD, String.class).orElse("");
        if (password.isBlank() || password.contains(PLACEHOLDER_PREFIX)) {
            violations.add(PASSWORD + " 가 비었거나 플레이스홀더가 풀리지 않았습니다. 환경변수 REDIS_PASSWORD 를 채우세요"
                + " (로컬 compose 도 requirepass 입니다)");
        }
        if (binder.bind(HOST, String.class).orElse("").contains(PLACEHOLDER_PREFIX)) {
            violations.add(HOST + " 의 플레이스홀더가 풀리지 않았습니다. 환경변수 REDIS_HOST 를 채우세요");
        }
        requirePositive(binder, TIMEOUT, "명령 타임아웃", "SPRING_DATA_REDIS_TIMEOUT", violations);
        requirePositive(binder, CONNECT_TIMEOUT, "연결 타임아웃", "SPRING_DATA_REDIS_CONNECTTIMEOUT", violations);
        if (StringUtils.hasText(binder.bind(URL, String.class).orElse(null))) {
            violations.add(URL + "(환경변수 SPRING_DATA_REDIS_URL)은 쓰지 않습니다. url 이 있으면 host·port·password 가 무시됩니다"
                + " — REDIS_HOST·REDIS_PORT·REDIS_PASSWORD 로 설정하세요");
        }
        for (String mode : UNSUPPORTED_MODES) {
            if (hasAnyPropertyUnder(PREFIX + mode)) {
                violations.add("%s%s 아래 설정이 있습니다. standalone 만 지원하니 지우세요 (환경변수 SPRING_DATA_REDIS_%s_*)"
                    .formatted(PREFIX, mode, mode.toUpperCase(Locale.ROOT)));
            }
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException("Redis 설정이 기동 조건에 맞지 않습니다 (coding-conventions.md §9-7)\n- " + String.join("\n- ", violations));
        }
    }

    private static void requirePositive(Binder binder, String property, String label, String envName, List<String> violations) {
        Duration value;
        try {
            value = binder.bind(property, Duration.class).orElse(null);
        } catch (BindException e) {
            // 원인 메시지에 설정값이 들어 있다. 예외를 잇지 않고 이름만 알린다
            violations.add("%s(%s)의 형식이 틀렸습니다. 1s · 500ms 처럼 적으세요 (환경변수로는 %s)".formatted(property, label, envName));
            return;
        }
        if (value == null || value.isNegative() || value.isZero()) {
            violations.add("%s(%s)이 없거나 0 이하입니다. 서비스 yml 에 양수로 명시하세요 (환경변수로는 %s)".formatted(property, label, envName));
        }
    }

    private boolean hasAnyPropertyUnder(String prefix) {
        ConfigurationPropertyName name = ConfigurationPropertyName.of(prefix);
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            if (source.containsDescendantOf(name) == ConfigurationPropertyState.PRESENT) {
                return true;
            }
        }
        return false;
    }
}
