package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import com.commercefoundry.domainlayer.member.adapter.in.internal.presenter.MarketingConsentInternalPresenter;
import com.commercefoundry.domainlayer.member.application.service.MarketingConsentInternalFacade;
import com.commercefoundry.domainlayer.member.application.service.processor.MarketingConsentQueryProcessor;
import com.commercefoundry.global.config.AuthServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=auth",
    "spring.jpa.properties.hibernate.generate_statistics=true", "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"
})
@Import({AuthServiceFeaturesConfig.class, QuerydslConfigurer.class, MarketingConsentQueryAdapter.class,
    MarketingConsentQueryProcessor.class, MarketingConsentInternalPresenter.class, MarketingConsentInternalFacade.class})
class MarketingConsentQueryPostgresTest extends MarketingConsentQueryContract {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/auth-schema.sql");
}
