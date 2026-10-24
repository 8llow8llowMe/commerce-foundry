package com.commercefoundry.ai.config;

import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.ai.properties.AiProperties.Violation;
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
 * <p>규칙은 {@link AiProperties#violations()} 한 곳에 있다. 가드는 그 앞에서 형식이 틀린 값(불리언 · 시간 · 숫자)을 걸러,
 * Boot 의 바인딩 오류(메시지에 설정값이 들어 있다) 대신 이름만 알린다. 꺼져 있으면(기본) 값이 비어도 기동한다 — 닫힌 기본값이다.
 */
public final class AiStartupGuard implements BeanFactoryPostProcessor {

    private static final String HEADER = "AI 설정이 기동 조건에 맞지 않습니다 (bootstrap-conventions.md AI)\n- ";

    private final Environment environment;

    public AiStartupGuard(Environment environment) {
        this.environment = environment;
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
                null, null, null, null, null, null, null, null, null, null, null, null, null));
        } catch (BindException e) {
            // 원인을 잇지 않는다 — 메시지에 설정값이 들어 있다. 어긋난 항목 이름만 알린다
            String property = e.getProperty() == null ? "ai.*" : e.getProperty().getName().toString();
            throw new IllegalStateException(HEADER + property + " 의 형식이 틀렸습니다. 시간은 2s · 500ms · 5m, 수는 정수로 적으세요");
        }
        List<String> violations = properties.violations().stream().map(Violation::message).toList();
        if (!violations.isEmpty()) {
            throw new IllegalStateException(HEADER + String.join("\n- ", violations));
        }
    }
}
