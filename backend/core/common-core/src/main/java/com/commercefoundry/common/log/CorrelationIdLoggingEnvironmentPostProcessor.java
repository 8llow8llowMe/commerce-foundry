package com.commercefoundry.common.log;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 콘솔 로그에 상관 id 를 싣는다 (#284). 서비스 · 게이트웨이 yml 에 패턴을 반복하지 않는다.
 *
 * <p>{@code logging.pattern.correlation} 이 이미 있으면 그대로 둔다. 없으면 가장 뒤에 기본값을 넣어, 애플리케이션 설정이 있으면 그 값이 이긴다.
 */
public class CorrelationIdLoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY = "logging.pattern.correlation";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getProperty(PROPERTY) != null) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource("correlationIdLogging", Map.of(PROPERTY, CorrelationIds.LOG_PATTERN)));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
