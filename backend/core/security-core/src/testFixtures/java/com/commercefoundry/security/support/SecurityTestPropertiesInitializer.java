package com.commercefoundry.security.support;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * 테스트 컨텍스트에 실행 중 만든 키({@link TestKeys#CURRENT})로 {@code security.jwt.*} 를 넣는다.
 * 테스트 픽스처의 {@code META-INF/spring.factories} 로 등록돼, 픽스처를 받은 모듈의 {@code SpringApplication}(={@code @SpringBootTest})마다 돈다.
 * 공개 저장소라 키를 {@code application-test.yml} 에 적지 않는다 ({@code security-guide.md} §5-3 테스트 키).
 *
 * <ul>
 *   <li>{@code addFirst} 다 — 개발 PC 의 OS 환경변수 {@code JWT_PUBLIC_KEY} 가 결과를 바꾸지 않게 가장 앞에 둔다.
 *       그래서 {@code @TestPropertySource} · {@code @DynamicPropertySource} 로도 이 키들을 바꿀 수 없다. 다른 키로 기동 실패를 볼 테스트는
 *       {@code ApplicationContextRunner} 를 쓴다 ({@code SpringApplication} 이 아니라 이 초기화기가 돌지 않는다).</li>
 *   <li>{@code security.jwt.issuance.private-key} 도 미리 넣는다 — auth-service 가 발급기를 붙이는 #4 대비다. 발급기를 import 하지 않는 서비스에는 무해하다.</li>
 *   <li>서비스 간 인증(#51) 같은 테스트 전용 비밀이 생기면 {@link #properties()} 에 한 줄 더한다.</li>
 * </ul>
 */
public class SecurityTestPropertiesInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String PROPERTY_SOURCE_NAME = "security-test-properties";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, properties()));
    }

    /** 컨텍스트에 넣는 값. 키는 실행 중 만든 것이고 PEM 본문 한 줄(환경변수의 정본 모양)이다. */
    public static Map<String, Object> properties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("security.jwt.public-key", TestKeys.publicKeyLine(TestKeys.CURRENT));
        properties.put("security.jwt.previous-public-key", "");
        properties.put("security.jwt.issuance.private-key", TestKeys.privateKeyLine(TestKeys.CURRENT));
        return properties;
    }
}
