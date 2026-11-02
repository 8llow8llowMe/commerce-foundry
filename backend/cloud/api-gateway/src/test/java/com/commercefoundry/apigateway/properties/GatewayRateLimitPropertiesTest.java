package com.commercefoundry.apigateway.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.apigateway.properties.GatewayRateLimitProperties.Limit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GatewayRateLimitPropertiesTest {

    @ParameterizedTest
    @CsvSource({
        // 분당 횟수, 순간 허용, 초당 보충, 요청당 토큰, 버킷 크기, Retry-After
        "6,   3,  1, 10,  30, 10",
        "12,  5,  1,  5,  25,  5",
        "60,  20, 1,  1,  20,  1",
        "120, 40, 2,  1,  40,  1",
        "1,   1,  1, 60,  60, 60"
    })
    @DisplayName("분당 횟수 · 순간 허용 횟수를 토큰 버킷 값으로 바꾼다 — 60 미만은 요청 한 번에 토큰 여러 개를 쓴다")
    void translatesToTokenBucket(int perMinute, int burst, int replenish, int requested, long capacity, long retryAfter) {
        Limit limit = new Limit(perMinute, burst);

        assertThat(limit.replenishRate()).isEqualTo(replenish);
        assertThat(limit.requestedTokens()).isEqualTo(requested);
        assertThat(limit.burstCapacity()).isEqualTo(capacity);
        assertThat(limit.retryAfterSeconds()).isEqualTo(retryAfter);
    }

    @ParameterizedTest
    @CsvSource({"7, 3", "90, 3", "0, 3", "6, 0", "-1, 3"})
    @DisplayName("60 의 약수 · 배수가 아니거나 0 이하인 값은 기동에서 막는다 — 정수 토큰 버킷으로 정확히 나타낼 수 없다")
    void rejectsInvalidLimit(int perMinute, int burst) {
        assertThatThrownBy(() -> new Limit(perMinute, burst)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("yml 에 적지 않은 정책은 기본 한도를 쓴다")
    void fallsBackToDefaults() {
        GatewayRateLimitProperties properties = new GatewayRateLimitProperties(true, null, null, null, null, null);

        assertThat(properties.cartMerge()).isEqualTo(GatewayRateLimitProperties.DEFAULT_CART_MERGE);
        assertThat(properties.cartWrite()).isEqualTo(GatewayRateLimitProperties.DEFAULT_CART_WRITE);
        assertThat(properties.orderWrite()).isEqualTo(GatewayRateLimitProperties.DEFAULT_ORDER_WRITE);
        assertThat(properties.paymentWrite()).isEqualTo(new Limit(6, 3));
        assertThat(properties.orderQuote()).isEqualTo(GatewayRateLimitProperties.DEFAULT_ORDER_QUOTE);
    }
    @ParameterizedTest
    @CsvSource({"12, 3", "6, 4"})
    void paymentCannotExceedEitherOrderLimit(int perMinute, int burst) {
        assertThatThrownBy(() -> new GatewayRateLimitProperties(true, null, null, new Limit(6, 3), null, new Limit(perMinute, burst)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("payment-write");
    }

    @Test
    void lowerOrderLimitRequiresExplicitLowerPaymentLimit() {
        assertThatThrownBy(() -> new GatewayRateLimitProperties(true, null, null, new Limit(2, 1), null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new GatewayRateLimitProperties(true, null, null, new Limit(2, 1), null, new Limit(2, 1)).paymentWrite())
            .isEqualTo(new Limit(2, 1));
    }
    @Test
    void bindsPaymentLimitsAndRejectsUnsafeEffectiveConfigurationAtStartup() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(RateLimitConfig.class);
        runner.withPropertyValues("gateway.rate-limit.payment-write.requests-per-minute=3", "gateway.rate-limit.payment-write.burst=2")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(GatewayRateLimitProperties.class).paymentWrite()).isEqualTo(new Limit(3, 2));
            });
        runner.withPropertyValues("gateway.rate-limit.order-write.requests-per-minute=3", "gateway.rate-limit.order-write.burst=2")
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GatewayRateLimitProperties.class)
    static class RateLimitConfig {
    }

}
