package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.global.config.ProductServicePropertiesConfig;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

class PriceSourcePropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ProductServicePropertiesConfig.class)
        // 실제 서비스의 나머지 필수 설정은 application.yml 기본값을 사용한다. price-source는 미설정 시험을 위해 제외한다.
        .withPropertyValues("security.internal.service-id=product-service", "security.internal.secret=" + temporarySecret(),
            "product-draft.ttl=7d", "stock.reservation.ttl=30m", "stock.reservation.expiry-sweep.initial-delay=60s",
            "stock.reservation.expiry-sweep.fixed-delay=60s", "stock.reservation.expiry-sweep.batch-size=100",
            "stock.reservation.expiry-sweep.max-batches-per-run=10", "stock.reservation.expiry-sweep.event-grace=2m");

    @Test
    void actualServiceRegistrationDefaultsToLegacyWhenAbsentOrNull() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PriceSourceProperties.class);
            assertThat(context.getBean(PriceSourceProperties.class).mode()).isEqualTo(PriceSourceProperties.Mode.LEGACY);
        });
        var properties = new HashMap<String, Object>();
        properties.put("price-source.mode", null);
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
            .addFirst(new MapPropertySource("nullable-price-source", properties))).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(PriceSourceProperties.class).mode()).isEqualTo(PriceSourceProperties.Mode.LEGACY);
            });
        assertThat(new PriceSourceProperties(null).mode()).isEqualTo(PriceSourceProperties.Mode.LEGACY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LEGACY", ""})
    void explicitLegacyAndEmptyEnvironmentValueStart(String value) {
        runner.withPropertyValues("price-source.mode=" + value).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PriceSourceProperties.class).mode()).isEqualTo(PriceSourceProperties.Mode.LEGACY);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRICE_BOOK", "INVALID_MODE"})
    void reservedAndUnknownModesFailDuringContextStartupBeforeApplicationRunners(String value) {
        runner.withPropertyValues("price-source.mode=" + value, "price-backfill.mode=APPLY").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("price-source");
            if (value.equals("PRICE_BOOK")) {
                // 바인딩은 받고(D6) 기동 검증 빈이 거절한다. B7 에서 PriceSourceModeGuard 를 지우면 해제된다.
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("price-source.mode=PRICE_BOOK 는 아직 지원하지 않습니다");
            }
        });
    }

    @Test
    void priceBookBindsAsValueSoWriterModeBranchCanBeTested() {
        assertThat(new PriceSourceProperties(PriceSourceProperties.Mode.PRICE_BOOK).mode())
            .isEqualTo(PriceSourceProperties.Mode.PRICE_BOOK);
    }

    private static String temporarySecret() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
