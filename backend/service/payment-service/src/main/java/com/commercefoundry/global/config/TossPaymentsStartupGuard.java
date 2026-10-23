package com.commercefoundry.global.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * 토스페이먼츠 설정을 클라이언트 · 프로퍼티 빈이 만들어지기 전에 검사해, 어긋나면 기동을 멈춘다 ({@code payment-guide.md} §2, redis-core {@code RedisStartupGuard} 와 같은 방식).
 *
 * <ul>
 *   <li>{@value #SECRET_KEY} 가 비었거나 플레이스홀더가 풀리지 않았다 — 첫 승인 요청에서야 401 로 드러난다.</li>
 *   <li>시크릿 키 형식이 아니다({@code test_sk_} · {@code live_sk_} · 결제위젯 {@code test_gsk_} · {@code live_gsk_}) — 클라이언트 키({@code ck} · {@code gck})를 넣은 실수도 여기서 막는다.</li>
 *   <li>키와 프로파일이 뒤바뀌었다 — {@code local} · {@code dev} · {@code test} 만 활성이면 {@code test_} 키만, 그 밖(prod · 다른 프로파일 · 프로파일 없음)은
 *       {@code live_} 키만 받는다. 닫힌 쪽이 기본값이다 — 프로파일을 빠뜨린 배포가 테스트 키로 떠서 "결제는 되는데 돈이 안 들어오는" 사고를 내지 않게.
 *       테스트 프로파일과 다른 프로파일이 같이 켜져 있어도 {@code live_} 다.</li>
 *   <li>{@value #BASE_URL} 이 비었거나 플레이스홀더가 풀리지 않았다.</li>
 *   <li>Feign 타임아웃({@value #CONNECT_TIMEOUT} · {@value #READ_TIMEOUT}, ms)이 없거나 0 이하 · 형식 오류이거나, read timeout 이
 *       {@value #GATEWAY_RESPONSE_TIMEOUT_MILLIS}ms(게이트웨이 응답 타임아웃) 이상이다 — 게이트웨이가 먼저 끊으면 고객은 실패를 보는데 승인은 계속 진행된다.</li>
 * </ul>
 *
 * <p>{@link BeanFactoryPostProcessor} 라 일반 빈보다 먼저 돈다. 프로퍼티는 {@link Binder} 로 읽어 환경변수도 Boot 와 같은 규칙으로 인식한다.
 * 위반은 모아서 한 번에 알리고, 메시지에 설정값을 넣지 않는다 — 키 이름 · 조건만 싣는다.
 */
public final class TossPaymentsStartupGuard implements BeanFactoryPostProcessor {

    static final String SECRET_KEY = "toss.secret-key";
    static final String BASE_URL = "toss.base-url";
    static final String CONNECT_TIMEOUT = "spring.cloud.openfeign.client.config.toss-payments.connect-timeout";
    static final String READ_TIMEOUT = "spring.cloud.openfeign.client.config.toss-payments.read-timeout";
    static final int GATEWAY_RESPONSE_TIMEOUT_MILLIS = 10_000;

    /** {@code test_} 키를 받는 프로파일. 이 밖의 프로파일이 하나라도 켜져 있거나 아무것도 없으면 {@code live_} 키만 받는다. */
    static final Set<String> TEST_KEY_PROFILES = Set.of("local", "dev", "test");

    /** 시크릿 키 — API 개별 연동 {@code sk}, 결제위젯 {@code gsk}. 토스 키는 영문 · 숫자다. */
    private static final Pattern SECRET_KEY_SHAPE = Pattern.compile("(test|live)_g?sk_[A-Za-z0-9]+");
    private static final String TEST_KEY_PREFIX = "test_";
    private static final String LIVE_KEY_PREFIX = "live_";

    /** Binder 는 풀리지 않은 {@code ${...}} 를 문자 그대로 남긴다. 환경변수가 없으면 값이 이 문자열을 품는다. */
    private static final String PLACEHOLDER_PREFIX = "${";

    private final Environment environment;

    public TossPaymentsStartupGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        Binder binder = Binder.get(environment);
        List<String> violations = new ArrayList<>();

        checkSecretKey(binder.bind(SECRET_KEY, String.class).orElse(""), violations);
        String baseUrl = binder.bind(BASE_URL, String.class).orElse("");
        if (baseUrl.isBlank() || baseUrl.contains(PLACEHOLDER_PREFIX)) {
            violations.add(BASE_URL + " 가 비었거나 플레이스홀더가 풀리지 않았습니다");
        }
        requirePositiveMillis(binder, CONNECT_TIMEOUT, "연결 타임아웃", violations);
        Integer readTimeout = requirePositiveMillis(binder, READ_TIMEOUT, "응답 타임아웃", violations);
        if (readTimeout != null && readTimeout >= GATEWAY_RESPONSE_TIMEOUT_MILLIS) {
            violations.add("%s(응답 타임아웃)는 게이트웨이 응답 타임아웃 %dms 보다 짧아야 합니다".formatted(READ_TIMEOUT, GATEWAY_RESPONSE_TIMEOUT_MILLIS));
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException("토스페이먼츠 설정이 기동 조건에 맞지 않습니다 (payment-guide.md §2)\n- " + String.join("\n- ", violations));
        }
    }

    private void checkSecretKey(String secretKey, List<String> violations) {
        if (secretKey.isBlank() || secretKey.contains(PLACEHOLDER_PREFIX)) {
            violations.add(SECRET_KEY + " 가 비었거나 플레이스홀더가 풀리지 않았습니다. 환경변수 TOSS_SECRET_KEY 를 채우세요");
            return;
        }
        if (!SECRET_KEY_SHAPE.matcher(secretKey).matches()) {
            violations.add(SECRET_KEY + " 가 시크릿 키 형식(test_sk_ · live_sk_ · test_gsk_ · live_gsk_)이 아닙니다 — 클라이언트 키(ck · gck)는 프론트 몫입니다");
            return;
        }
        boolean testKeyProfile = testKeyProfile();
        if (testKeyProfile && !secretKey.startsWith(TEST_KEY_PREFIX)) {
            violations.add(SECRET_KEY + " 는 local · dev · test 프로파일에서 test_ 키여야 합니다");
        }
        if (!testKeyProfile && !secretKey.startsWith(LIVE_KEY_PREFIX)) {
            violations.add(SECRET_KEY + " 는 운영(prod · 그 밖의 프로파일 · 프로파일 없음)에서 live_ 키여야 합니다. 로컬이면 local 프로파일로 띄우세요");
        }
    }

    /** 활성 프로파일이 있고 모두 {@link #TEST_KEY_PROFILES} 안에 있을 때만 테스트 키 환경이다. */
    private boolean testKeyProfile() {
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length > 0 && Arrays.stream(activeProfiles).allMatch(TEST_KEY_PROFILES::contains);
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
