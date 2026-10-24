package com.commercefoundry.discovery.config;

import java.util.regex.Pattern;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * 레지스트리 비밀번호({@code EUREKA_PASSWORD})를 보안 체인이 만들어지기 전에 검사해, 어긋나면 기동을 멈춘다 (#248, {@code security-guide.md} §12).
 *
 * <ul>
 *   <li>비었거나 플레이스홀더가 풀리지 않았다 — 인증을 끄는 기본값은 없다.</li>
 *   <li>32자 미만이다.</li>
 *   <li>URI unreserved 문자({@code A-Z a-z 0-9 . _ ~ -}) 밖의 문자가 있다. 클라이언트는 이 값을 {@code defaultZone} URL 의 userinfo 로 싣는데,
 *       Eureka 클라이언트는 {@code :} 가 들어 있거나 URL 이 파싱되지 않으면 <b>조용히 인증 없이</b> 요청한다 — 서버가 먼저 막는다.</li>
 * </ul>
 *
 * <p>{@link BeanFactoryPostProcessor} 라 일반 빈보다 먼저 돈다. 메시지에 설정값을 넣지 않는다.
 */
public final class DiscoveryPasswordStartupGuard implements BeanFactoryPostProcessor {

    static final String PASSWORD = "discovery.auth.password";
    static final int MIN_LENGTH = 32;

    private static final Pattern UNRESERVED = Pattern.compile("[A-Za-z0-9._~-]+");
    /** Binder 는 풀리지 않은 {@code ${...}} 를 문자 그대로 남긴다. 환경변수가 없으면 값이 이 문자열을 품는다. */
    private static final String PLACEHOLDER_PREFIX = "${";

    private final Environment environment;

    public DiscoveryPasswordStartupGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        String password = Binder.get(environment).bind(PASSWORD, String.class).orElse("");
        String violation = violationOf(password);
        if (violation != null) {
            throw new IllegalStateException("Eureka 레지스트리 비밀번호가 기동 조건에 맞지 않습니다 (security-guide.md §12)\n- " + violation);
        }
    }

    private static String violationOf(String password) {
        if (password.isBlank() || password.contains(PLACEHOLDER_PREFIX)) {
            return PASSWORD + " 가 비었거나 플레이스홀더가 풀리지 않았습니다. 환경변수 EUREKA_PASSWORD 를 채우세요 (openssl rand -hex 32)";
        }
        if (password.length() < MIN_LENGTH) {
            return "EUREKA_PASSWORD 는 %d자 이상이어야 합니다 (openssl rand -hex 32)".formatted(MIN_LENGTH);
        }
        if (!UNRESERVED.matcher(password).matches()) {
            return "EUREKA_PASSWORD 는 영문 · 숫자 · . _ ~ - 만 쓸 수 있습니다. 클라이언트 URL 에 실리는 값이라 : @ / ? # % 공백 등이 있으면"
                + " 인증 없이 요청됩니다 (openssl rand -hex 32)";
        }
        return null;
    }
}
