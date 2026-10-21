package com.commercefoundry.storage.config;

import static com.commercefoundry.storage.properties.StorageProperties.ACCESS_KEY;
import static com.commercefoundry.storage.properties.StorageProperties.API_CALL_TIMEOUT;
import static com.commercefoundry.storage.properties.StorageProperties.BUCKET;
import static com.commercefoundry.storage.properties.StorageProperties.CONNECT_TIMEOUT;
import static com.commercefoundry.storage.properties.StorageProperties.ENABLED;
import static com.commercefoundry.storage.properties.StorageProperties.ENDPOINT;
import static com.commercefoundry.storage.properties.StorageProperties.PRESIGN_TTL;
import static com.commercefoundry.storage.properties.StorageProperties.PUBLIC_BASE_URL;
import static com.commercefoundry.storage.properties.StorageProperties.READ_TIMEOUT;
import static com.commercefoundry.storage.properties.StorageProperties.REGION;
import static com.commercefoundry.storage.properties.StorageProperties.SECRET_KEY;

import com.commercefoundry.storage.properties.StorageProperties;
import com.commercefoundry.storage.properties.StorageProperties.Violation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * 스토리지 설정을 클라이언트 · 프로퍼티 빈이 만들어지기 전에 검사해, 어긋나면 기동을 멈춘다 ({@code RedisStartupGuard} 와 같은 방식).
 *
 * <p>규칙은 {@link StorageProperties#violations()} 한 곳에 있다 — 공개 팩토리({@code ObjectStorageClients.create})도 같은 규칙을 부른다.
 * 가드는 그 앞에서 형식(불리언 · 시간)이 틀린 값을 먼저 걸러, Boot 의 바인딩 오류(메시지에 설정값이 들어 있다) 대신 이름만 알린다.
 * 형식이 틀린 항목은 "없음" 위반을 겹쳐 알리지 않는다.
 *
 * <p>꺼져 있으면(기본) 값이 비어도 기동한다 — 닫힌 기본값이다. 다만 {@code public-base-url} 이 채워져 있으면 공개 URL 만 만들 수 있으므로
 * 그 형식은 켜졌을 때와 같이 본다.
 *
 * <p>{@link BeanFactoryPostProcessor} 라 일반 빈보다 먼저 돈다. 프로퍼티는 {@link Binder} 로 읽어 환경변수({@code STORAGE_ENDPOINT} 등)도
 * Boot 와 같은 규칙으로 인식한다. 위반은 모아서 한 번에 알리고, 메시지에 설정값을 넣지 않는다.
 */
public final class StorageStartupGuard implements BeanFactoryPostProcessor {

    /** {@link StorageProperties} 의 {@code @DefaultValue("5m")} 와 같다 — 가드는 레코드를 Binder 로 만들지 않아 기본값을 직접 넣는다. */
    private static final Duration DEFAULT_PRESIGN_TTL = Duration.ofMinutes(5);
    private static final String DURATION_EXAMPLE = "2s · 500ms · 5m 처럼 적으세요";

    private final Environment environment;

    public StorageStartupGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        Binder binder = Binder.get(environment);
        List<String> violations = new ArrayList<>();
        Set<String> malformed = new HashSet<>();

        Boolean enabled = bind(binder, ENABLED, Boolean.class, "true 또는 false 를 적으세요", violations, malformed);
        StorageProperties properties = Boolean.TRUE.equals(enabled) ? enabledProperties(binder, violations, malformed) : disabledProperties(binder);
        properties.violations().stream()
            .filter(violation -> !malformed.contains(violation.property()))
            .map(Violation::message)
            .forEach(violations::add);

        if (!violations.isEmpty()) {
            throw new IllegalStateException("Storage 설정이 기동 조건에 맞지 않습니다 (bootstrap-conventions.md Storage)\n- " + String.join("\n- ", violations));
        }
    }

    private static StorageProperties enabledProperties(Binder binder, List<String> violations, Set<String> malformed) {
        Duration presignTtl = bind(binder, PRESIGN_TTL, Duration.class, DURATION_EXAMPLE, violations, malformed);
        if (presignTtl == null && !malformed.contains(PRESIGN_TTL)) {
            presignTtl = DEFAULT_PRESIGN_TTL;
        }
        return new StorageProperties(true,
            text(binder, ENDPOINT), text(binder, REGION), text(binder, BUCKET), text(binder, ACCESS_KEY), text(binder, SECRET_KEY), text(binder, PUBLIC_BASE_URL),
            null, presignTtl,
            bind(binder, CONNECT_TIMEOUT, Duration.class, DURATION_EXAMPLE, violations, malformed),
            bind(binder, READ_TIMEOUT, Duration.class, DURATION_EXAMPLE, violations, malformed),
            bind(binder, API_CALL_TIMEOUT, Duration.class, DURATION_EXAMPLE, violations, malformed));
    }

    /** 꺼져 있으면 공개 주소만 본다(StorageConfigurer 가 꺼져도 공개 URL 은 만든다). */
    private static StorageProperties disabledProperties(Binder binder) {
        return new StorageProperties(false, null, null, null, null, null, text(binder, PUBLIC_BASE_URL), null, null, null, null, null);
    }

    private static String text(Binder binder, String property) {
        return binder.bind(property, String.class).orElse(null);
    }

    /** 없으면 null. 형식이 틀리면 위반을 남기고 {@code malformed} 에 넣은 뒤 null 이다 — 원인 메시지에 설정값이 들어 있어 예외를 잇지 않는다. */
    private static <T> T bind(Binder binder, String property, Class<T> type, String example, List<String> violations, Set<String> malformed) {
        try {
            return binder.bind(property, type).orElse(null);
        } catch (BindException e) {
            violations.add("%s의 형식이 틀렸습니다. %s".formatted(StorageProperties.describe(property), example));
            malformed.add(property);
            return null;
        }
    }
}
