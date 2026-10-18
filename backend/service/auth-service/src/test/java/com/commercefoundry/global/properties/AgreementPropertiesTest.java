package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 약관 버전이 비면 기동하지 않는다 — 어느 약관에 동의했는지 모르는 이력이 쌓이지 않게.
 */
class AgreementPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("세 버전이 모두 있으면 바인딩된다")
    void binds() {
        runner.withPropertyValues(
            "auth.agreement.versions.terms-of-service=2026-10-01",
            "auth.agreement.versions.privacy-policy=2026-10-02",
            "auth.agreement.versions.marketing=2026-10-03"
        ).run(context -> {
            AgreementProperties.Versions versions = context.getBean(AgreementProperties.class).versions();

            assertThat(versions.termsOfService()).isEqualTo("2026-10-01");
            assertThat(versions.privacyPolicy()).isEqualTo("2026-10-02");
            assertThat(versions.marketing()).isEqualTo("2026-10-03");
        });
    }

    @Test
    @DisplayName("auth.agreement.* 가 하나도 없으면 기동하지 않는다")
    void absentFails() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "123456789012345678901"})
    @DisplayName("버전이 비었거나 20자(terms_version 길이)를 넘으면 기동하지 않는다")
    void invalidVersionFails(String marketing) {
        runner.withPropertyValues(
            "auth.agreement.versions.terms-of-service=2026-10-01",
            "auth.agreement.versions.privacy-policy=2026-10-01",
            "auth.agreement.versions.marketing=" + marketing
        ).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgreementProperties.class)
    static class PropertiesConfig {
    }
}
