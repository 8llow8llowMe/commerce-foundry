package com.commercefoundry.ai.config;

import com.commercefoundry.ai.metering.AiCallInterceptor;
import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.ai.properties.AiProperties.Violation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * AI 설정을 클라이언트 · 프로퍼티 빈이 만들어지기 전에 검사해, 어긋나면 기동을 멈춘다 ({@code StorageStartupGuard} 와 같은 방식).
 *
 * <p>켜져 있으면 계량 인터셉터({@code AiCallInterceptor}) 빈 정의가 정확히 하나여야 한다 — 없으면 {@code ai.allow-unmetered=true} 일 때만 기동한다
 * ({@link AiProperties#meteringViolation(int)}). 사용량 원장 · 월 한도 없이 API 를 부르는 서비스가 생기지 않게 한다.
 * 운영(활성 프로파일에 local · dev · test 가 없으면 prod)에서는 {@code ai.allow-unmetered} · {@code ai.allow-insecure-loopback} 을 켜지 못한다.
 *
 * <p>규칙은 {@link AiProperties#violations()} 한 곳에 있다. 가드는 그 앞에서 형식이 틀린 값(불리언 · 시간 · 숫자)을 걸러,
 * Boot 의 바인딩 오류(메시지에 설정값이 들어 있다) 대신 이름만 알린다. 꺼져 있으면(기본) 값이 비어도 기동한다 — 닫힌 기본값이다.
 */
public final class AiStartupGuard implements BeanFactoryPostProcessor {

    private static final String HEADER = "AI 설정이 기동 조건에 맞지 않습니다 (bootstrap-conventions.md AI)\n- ";
    private static final List<String> NON_PROD_ENVIRONMENTS = List.of("local", "dev", "test");
    private static final String PROD = "prod";

    private final Environment environment;

    public AiStartupGuard(Environment environment) {
        this.environment = environment;
    }

    /** 활성 프로파일 중 local · dev · test 가 있으면 그것, 없으면 prod ({@code MessagingStartupGuard} 와 같은 판정). */
    static String activeEnvironment(Environment environment) {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        return NON_PROD_ENVIRONMENTS.stream().filter(active::contains).findFirst().orElse(PROD);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        Binder binder = Binder.get(environment);
        Boolean enabled;
        try {
            enabled = binder.bind(AiProperties.ENABLED, Boolean.class).orElse(false);
        } catch (BindException e) {
            throw new IllegalStateException(HEADER + AiProperties.describe(AiProperties.ENABLED) + " 의 형식이 틀렸습니다. true 또는 false 를 적으세요");
        }
        if (!enabled) {
            return;
        }

        AiProperties properties;
        try {
            properties = binder.bind("ai", Bindable.of(AiProperties.class)).orElseGet(() -> new AiProperties(true,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        } catch (BindException e) {
            // 원인을 잇지 않는다 — 메시지에 설정값이 들어 있다. 어긋난 항목 이름만 알린다
            String property = e.getProperty() == null ? "ai.*" : e.getProperty().getName().toString();
            throw new IllegalStateException(HEADER + property + " 의 형식이 틀렸습니다. 시간은 2s · 500ms · 5m, 수는 정수로 적으세요");
        }
        List<String> violations = new ArrayList<>(properties.violations().stream().map(Violation::message).toList());
        // 빈 정의만 센다 — 인스턴스를 만들지 않는다(BeanFactoryPostProcessor 단계)
        properties.meteringViolation(beanFactory.getBeanNamesForType(AiCallInterceptor.class, true, false).length)
            .ifPresent(violation -> violations.add(violation.message()));
        if (PROD.equals(activeEnvironment(environment))) {
            // 테스트 / 로컬 전용 스위치는 운영에서 켜지 못한다 — 원장 없이 부르거나 평문 http 로 키를 보내지 않게
            if (properties.allowUnmetered()) {
                violations.add(AiProperties.ALLOW_UNMETERED + " 는 운영(prod)에서 켜지 못합니다 — 로컬 · 개발 · 테스트 전용입니다. 계량 인터셉터를 두세요");
            }
            if (properties.allowInsecureLoopback()) {
                violations.add(AiProperties.ALLOW_INSECURE_LOOPBACK + " 는 운영(prod)에서 켜지 못합니다 — 테스트 스텁 전용입니다");
            }
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException(HEADER + String.join("\n- ", violations));
        }
    }
}
