package com.commercefoundry.security.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.internal.InternalTokenIssuer;
import com.commercefoundry.security.support.TestServiceTokens;
import feign.Request;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.Target;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * internal Feign 요청의 {@code Authorization} 이 서비스 토큰으로 바뀌는지 본다. Feign 은 대상(Target)을 요청 템플릿에 실은 뒤 인터셉터를 부른다.
 */
class InternalServiceTokenInterceptorTest {

    private static final String CALLER = TestServiceTokens.CALLER;
    private static final String TARGET = TestServiceTokens.SERVICE_ID;

    private final InternalTokenIssuer issuer = TestServiceTokens.issuer(CALLER, Instant.now());
    private final InternalServiceTokenInterceptor interceptor = new InternalServiceTokenInterceptor(issuer);

    @Test
    @DisplayName("사용자 Authorization 을 지우고 서비스 토큰 하나만 싣는다 — aud 는 Feign 대상 이름, iss 는 이 서비스")
    void replacesUserAuthorizationWithServiceToken() {
        RequestTemplate template = templateFor(TARGET).header("Authorization", "Bearer user-access-token");

        interceptor.apply(template);

        Collection<String> authorization = template.headers().get("Authorization");
        assertThat(authorization).singleElement().asString().startsWith("Bearer ").doesNotContain("user-access-token");
        Jwt jwt = decodeAs(TARGET, authorization.iterator().next().substring("Bearer ".length()));
        assertThat(jwt.getAudience()).containsExactly(TARGET);
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(CALLER);
    }

    @Test
    @DisplayName("헤더 이름의 대소문자가 달라도 기존 값을 지운다 — 사용자 토큰이 같이 실리지 않는다")
    void removesAuthorizationCaseInsensitively() {
        RequestTemplate template = templateFor(TARGET).header("authorization", "Bearer user-access-token");

        interceptor.apply(template);

        assertThat(template.headers().entrySet())
            .filteredOn(header -> header.getKey().equalsIgnoreCase("Authorization"))
            .singleElement()
            .satisfies(header -> assertThat(header.getValue()).singleElement().asString().doesNotContain("user-access-token"));
    }

    @Test
    @DisplayName("요청마다 새 토큰을 싣는다 — 캐시하지 않는다")
    void issuesNewTokenPerRequest() {
        RequestTemplate first = templateFor(TARGET);
        RequestTemplate second = templateFor(TARGET);

        interceptor.apply(first);
        interceptor.apply(second);

        assertThat(first.headers().get("Authorization")).doesNotContainAnyElementsOf(second.headers().get("Authorization"));
    }

    @Test
    @DisplayName("Feign 대상이 없으면 토큰 없이 보내지 않고 실패한다")
    void failsWithoutTarget() {
        RequestTemplate template = new RequestTemplate().header("Authorization", "Bearer user-access-token");

        assertThatThrownBy(() -> interceptor.apply(template)).isInstanceOf(IllegalStateException.class).hasMessageContaining("@FeignClient(name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    @DisplayName("Feign 대상 이름이 비었으면 실패한다")
    void failsWithBlankTargetName(String name) {
        assertThatThrownBy(() -> interceptor.apply(templateFor(name))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("대상 이름이 서비스 ID 모양이 아니면(URL · 대문자) 실패한다 — 외부 API 클라이언트에 잘못 붙인 경우를 드러낸다")
    void failsWithNonServiceIdTargetName() {
        assertThatThrownBy(() -> interceptor.apply(templateFor("PRODUCT-SERVICE"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> interceptor.apply(templateFor("https://api.tosspayments.com"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("InternalFeignClientConfiguration 은 @Configuration 이 아니고, 서비스 컨텍스트의 발급기로 인터셉터 하나를 만든다")
    void configurationRegistersInterceptorFromIssuer() {
        assertThat(InternalFeignClientConfiguration.class.isAnnotationPresent(Configuration.class)).as("전역 설정으로 스캔되지 않는다").isFalse();

        new ApplicationContextRunner()
            .withBean(InternalTokenIssuer.class, () -> issuer)
            .withUserConfiguration(InternalFeignClientConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(RequestInterceptor.class).values()).singleElement().isInstanceOf(InternalServiceTokenInterceptor.class);
            });
    }

    @Test
    @DisplayName("발급기 빈이 없으면 클라이언트 설정이 실패한다 — 서비스가 InternalTokenIssuerConfigurer 를 빠뜨린 경우")
    void configurationFailsWithoutIssuer() {
        new ApplicationContextRunner()
            .withUserConfiguration(InternalFeignClientConfiguration.class)
            .run(context -> assertThat(context).hasFailed());
    }

    private static RequestTemplate templateFor(String targetName) {
        RequestTemplate template = new RequestTemplate();
        template.feignTarget(new StubTarget(targetName));
        return template;
    }

    /** {@code HardCodedTarget} 은 빈 이름을 생성자에서 막으므로, 이름이 빈 대상은 직접 만든다. 이름 말고는 쓰지 않는다. */
    private record StubTarget(String name) implements Target<Object> {

        @Override
        public Class<Object> type() {
            return Object.class;
        }

        @Override
        public String url() {
            return "http://" + name;
        }

        @Override
        public Request apply(RequestTemplate input) {
            throw new UnsupportedOperationException();
        }
    }

    private static Jwt decodeAs(String serviceId, String token) {
        return InternalTokenDecoderFactory.create(TestServiceTokens.propertiesWithoutPrevious(serviceId), Clock.fixed(Instant.now(), ZoneOffset.UTC))
            .decode(token);
    }
}
