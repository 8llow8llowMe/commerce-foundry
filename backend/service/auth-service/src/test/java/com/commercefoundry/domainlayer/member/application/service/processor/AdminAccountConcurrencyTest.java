package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.MemberRepositoryAdapter;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.mapper.MemberMapperImpl;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.global.config.AuthServiceFeaturesConfig;
import com.commercefoundry.global.properties.OperatorRoleProperties;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 대표 관리자 두 명이 서로를 동시에 강등 · 정지할 때 (#236 PR-B1) — 실제 PostgreSQL 에서 활성 운영자 전체 잠금({@code select ... for update})이 두 요청을 줄 세워
 * 대표 관리자가 0명이 되지 않는지 본다. 잠금 대기 · 커밋 뒤 재평가는 엔진에 기대므로 H2 로는 볼 수 없다. 테스트 트랜잭션을 끄고(NOT_SUPPORTED) 스레드마다 실제
 * Processor 트랜잭션으로 돈다. 커밋된 행이 남으므로 회원 아이디는 테스트마다 새로 만든다. Docker 가 없으면 건너뛴다(CI 는 돈다). 값은 전부 테스트 전용이다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=auth",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"})
@Import({AuthServiceFeaturesConfig.class, QuerydslConfigurer.class, MemberMapperImpl.class, MemberRepositoryAdapter.class, AdminAccountProcessor.class,
    AdminAccountConcurrencyTest.Beans.class})
class AdminAccountConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/auth-schema.sql");

    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz012345";
    private static final Instant AT = Instant.parse("2026-11-06T00:00:00Z");
    private static final AtomicLong MEMBER_IDS = new AtomicLong(80_000L);

    @TestConfiguration
    static class Beans {

        @Bean
        SnowflakeIdGenerator snowflakeIdGenerator() {
            return new SnowflakeIdGenerator(1, 1);
        }

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }

        @Bean
        OperatorRoleProperties operatorRoleProperties() {
            return new OperatorRoleProperties(true);
        }
    }

    @Autowired AdminAccountProcessor processor;
    @Autowired MemberRepositoryPort memberRepositoryPort;

    @Test
    @DisplayName("대표 두 명이 서로를 동시에 매니저로 내리면 한 건만 성공하고 다른 한 건은 403 AUTH_046 이다 — 활성 대표가 하나 남는다")
    void mutualDemotion() throws Exception {
        Member first = owner();
        Member second = owner();

        List<Throwable> failures = race(
            () -> processor.changeRole(first.id(), second.id(), MemberRole.MANAGER),
            () -> processor.changeRole(second.id(), first.id(), MemberRole.MANAGER));

        assertOneRejected(failures);
        assertThat(activeOwnersAmong(first, second)).isEqualTo(1);
    }

    @Test
    @DisplayName("대표 두 명이 서로를 동시에 정지하면 한 건만 성공하고 다른 한 건은 403 AUTH_046 이다 — 활성 대표가 하나 남는다")
    void mutualDeactivation() throws Exception {
        Member first = owner();
        Member second = owner();

        List<Throwable> failures = race(
            () -> processor.changeStatus(first.id(), second.id(), false, AT),
            () -> processor.changeStatus(second.id(), first.id(), false, AT));

        assertOneRejected(failures);
        assertThat(activeOwnersAmong(first, second)).isEqualTo(1);
    }

    private Member owner() {
        long id = MEMBER_IDS.incrementAndGet();
        return memberRepositoryPort.save(Member.operator(id, "owner-" + id + "@example.com", HASH, "대표", MemberRole.OWNER));
    }

    private long activeOwnersAmong(Member... owners) {
        long count = 0;
        for (Member owner : owners) {
            Member stored = memberRepositoryPort.findById(owner.id()).orElseThrow();
            if (stored.isOwner() && stored.isActiveOperator()) {
                count++;
            }
        }
        return count;
    }

    private static void assertOneRejected(List<Throwable> failures) {
        assertThat(failures).singleElement().satisfies(failure -> {
            assertThat(failure).isInstanceOf(MemberException.class);
            assertThat(((MemberException) failure).getErrorCode())
                .isIn(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN, MemberErrorCode.LAST_ACTIVE_OWNER_ROLE, MemberErrorCode.LAST_ACTIVE_ADMIN);
        });
    }

    /** 두 작업을 같은 순간에 출발시키고, 실패한 작업의 예외만 모은다. */
    private static List<Throwable> race(Runnable left, Runnable right) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Runnable task : List.of(left, right)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        task.run();
                        return null;
                    } catch (RuntimeException exception) {
                        return exception;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> future : futures) {
                Throwable failure = future.get(30, TimeUnit.SECONDS);
                if (failure != null) {
                    failures.add(failure);
                }
            }
            return failures;
        } finally {
            executor.shutdownNow();
        }
    }
}
