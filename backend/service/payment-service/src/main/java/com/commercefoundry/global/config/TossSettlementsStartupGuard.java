package com.commercefoundry.global.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * 토스 정산 조회 클라이언트({@code toss-settlements}, #219) 설정을 클라이언트가 만들어지기 전에 검사해, 어긋나면 기동을 멈춘다.
 * 결제 승인 클라이언트의 {@code TossPaymentsStartupGuard}(read 10초 미만 — 사용자 요청 경로)와 따로 둔다 — 정산 조회는 배치라 상한이 아니라 하한이 있다.
 *
 * <ul>
 *   <li>Feign 타임아웃({@value #CONNECT_TIMEOUT} · {@value #READ_TIMEOUT}, ms)이 없거나 0 이하 · 형식 오류다 — 타임아웃 없는 외부 호출을 막는다.</li>
 *   <li>read 가 {@value #MIN_READ_TIMEOUT_MILLIS}ms 보다 짧다 — 토스는 정산 조회에 60초 이상을 권고한다. 짧으면 큰 날짜가 늘 타임아웃으로 실패한다.</li>
 *   <li>Feign 로그 수준({@value #LOGGER_LEVEL})이 {@code HEADERS} · {@code FULL} 이다 — 시크릿 키가 든 Authorization · 결제키가 든 본문이 로그에 남는다.
 *       {@code default} 의 로그 수준은 {@code TossPaymentsStartupGuard} 가 본다.</li>
 * </ul>
 *
 * <p>위반은 모아서 한 번에 알리고, 메시지에 설정값을 넣지 않는다 — 키 이름 · 조건만 싣는다.
 */
public final class TossSettlementsStartupGuard implements BeanFactoryPostProcessor {

    static final String CONNECT_TIMEOUT = "spring.cloud.openfeign.client.config.toss-settlements.connect-timeout";
    static final String READ_TIMEOUT = "spring.cloud.openfeign.client.config.toss-settlements.read-timeout";
    static final String LOGGER_LEVEL = "spring.cloud.openfeign.client.config.toss-settlements.logger-level";
    static final int MIN_READ_TIMEOUT_MILLIS = 60_000;

    private static final Set<String> HEADER_LOGGING_LEVELS = Set.of("HEADERS", "FULL");

    private final Environment environment;

    public TossSettlementsStartupGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        Binder binder = Binder.get(environment);
        List<String> violations = new ArrayList<>();
        requirePositiveMillis(binder, CONNECT_TIMEOUT, "연결 타임아웃", violations);
        Integer readTimeout = requirePositiveMillis(binder, READ_TIMEOUT, "응답 타임아웃", violations);
        if (readTimeout != null && readTimeout < MIN_READ_TIMEOUT_MILLIS) {
            violations.add("%s(응답 타임아웃)는 %dms 이상이어야 합니다 — 토스 정산 조회 권고".formatted(READ_TIMEOUT, MIN_READ_TIMEOUT_MILLIS));
        }
        String level = binder.bind(LOGGER_LEVEL, String.class).orElse("");
        if (HEADER_LOGGING_LEVELS.contains(level.strip().toUpperCase(Locale.ROOT))) {
            violations.add(LOGGER_LEVEL + " 가 HEADERS · FULL 입니다 — 시크릿 키가 든 Authorization 헤더가 로그에 남습니다. 지우거나 NONE · BASIC 으로 두세요");
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("토스 정산 조회 설정이 기동 조건에 맞지 않습니다 (payment-guide.md §10)\n- " + String.join("\n- ", violations));
        }
    }

    /** 양수 ms 면 그 값, 아니면 위반을 남기고 null. */
    private static Integer requirePositiveMillis(Binder binder, String property, String label, List<String> violations) {
        Integer value;
        try {
            value = binder.bind(property, Integer.class).orElse(null);
        } catch (BindException e) {
            // 원인 메시지에 설정값이 들어 있다. 예외를 잇지 않고 이름만 알린다
            violations.add("%s(%s)의 형식이 틀렸습니다. ms 단위 정수로 적으세요".formatted(property, label));
            return null;
        }
        if (value == null || value <= 0) {
            violations.add("%s(%s)가 없거나 0 이하입니다. 서비스 yml 에 ms 단위 양수로 명시하세요".formatted(property, label));
            return null;
        }
        return value;
    }
}
