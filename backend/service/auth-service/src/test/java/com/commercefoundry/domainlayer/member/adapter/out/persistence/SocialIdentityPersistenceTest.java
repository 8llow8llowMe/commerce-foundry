package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.domainlayer.member.application.exception.*;
import com.commercefoundry.domainlayer.member.application.port.out.SocialIdentityRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.SocialProvider;
import com.commercefoundry.global.config.AuthServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=auth",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"})
@Import({AuthServiceFeaturesConfig.class, QuerydslConfigurer.class, SocialIdentityRepositoryAdapter.class})
class SocialIdentityPersistenceTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/auth-schema.sql");
    @Autowired SocialIdentityRepositoryPort identities;

    @Test
    void providerAndSubjectUniqueConstraintMapsToDomainConflict() {
        identities.append(1, 101, SocialProvider.KAKAO, "subject");
        assertThat(identities.findMemberId(SocialProvider.KAKAO, "subject")).contains(101L);
        identities.append(2, 102, SocialProvider.NAVER, "subject");
        assertThatThrownBy(() -> identities.append(3, 103, SocialProvider.KAKAO, "subject")).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_IDENTITY_CONFLICT);
    }
}