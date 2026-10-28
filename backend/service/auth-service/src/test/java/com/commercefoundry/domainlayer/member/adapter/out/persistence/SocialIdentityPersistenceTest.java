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
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test
    void concurrentPostgresTransactionsArbitrateBothUniqueKeys() throws Exception {
        for (boolean sameMember : new boolean[]{false, true}) {
            long id = Math.abs(java.util.UUID.randomUUID().getMostSignificantBits() >>> 2);
            String subject = java.util.UUID.randomUUID().toString();
            var start = new java.util.concurrent.CountDownLatch(1);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var one = pool.submit(() -> appendAfter(start, id, id, subject));
                var two = pool.submit(() -> appendAfter(start, id + 1, sameMember ? id : id + 1, sameMember ? subject + "-other" : subject));
                start.countDown();
                assertThat(java.util.List.of(one.get(10, java.util.concurrent.TimeUnit.SECONDS), two.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            }
            if (sameMember) {
                assertThat(identities.findProviders(id)).containsExactly(SocialProvider.KAKAO);
                assertThat(java.util.stream.Stream.of(subject, subject + "-other")
                    .filter(value -> identities.findMemberId(SocialProvider.KAKAO, value).isPresent()).count()).isEqualTo(1);
            } else {
                assertThat(identities.findMemberId(SocialProvider.KAKAO, subject).orElseThrow()).isIn(id, id + 1);
                assertThat(identities.findProviders(id).size() + identities.findProviders(id + 1).size()).isEqualTo(1);
            }
        }
    }

    private boolean appendAfter(java.util.concurrent.CountDownLatch start, long id, long memberId, String subject) throws Exception {
        start.await();
        try {
            new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(status -> identities.append(id, memberId, SocialProvider.KAKAO, subject));
            return true;
        } catch (MemberException conflict) {
            assertThat(conflict.getErrorCode()).isEqualTo(MemberErrorCode.SOCIAL_IDENTITY_CONFLICT);
            return false;
        }
    }

    @Test
    void memberProviderUniqueConstraintAndProviderProjection() {
        identities.append(11, 111, SocialProvider.KAKAO, "first");
        identities.append(12, 111, SocialProvider.NAVER, "second");
        assertThat(identities.findProviders(111)).containsExactly(SocialProvider.KAKAO, SocialProvider.NAVER);
        assertThat(identities.findSubject(111, SocialProvider.KAKAO)).contains("first");
        assertThat(identities.findSubject(222, SocialProvider.KAKAO)).isEmpty();
        assertThatThrownBy(() -> identities.append(13, 111, SocialProvider.KAKAO, "third")).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_IDENTITY_CONFLICT);
    }

    @Test
    void providerAndSubjectUniqueConstraintMapsToDomainConflict() {
        identities.append(1, 101, SocialProvider.KAKAO, "subject");
        assertThat(identities.findMemberId(SocialProvider.KAKAO, "subject")).contains(101L);
        identities.append(2, 102, SocialProvider.NAVER, "subject");
        assertThatThrownBy(() -> identities.append(3, 103, SocialProvider.KAKAO, "subject")).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_IDENTITY_CONFLICT);
    }
}
