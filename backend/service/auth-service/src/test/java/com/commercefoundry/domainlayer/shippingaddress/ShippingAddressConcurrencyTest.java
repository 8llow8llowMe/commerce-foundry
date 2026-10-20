package com.commercefoundry.domainlayer.shippingaddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.MemberRepositoryAdapter;
import com.commercefoundry.domainlayer.member.application.mapper.MemberMapperImpl;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberLockProcessor;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.presenter.ShippingAddressWebPresenter;
import com.commercefoundry.domainlayer.shippingaddress.adapter.out.persistence.ShippingAddressRepositoryAdapter;
import com.commercefoundry.domainlayer.shippingaddress.application.command.ShippingAddressCommand;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressErrorCode;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressException;
import com.commercefoundry.domainlayer.shippingaddress.application.mapper.ShippingAddressMapperImpl;
import com.commercefoundry.domainlayer.shippingaddress.application.port.in.ShippingAddressWebUseCase;
import com.commercefoundry.domainlayer.shippingaddress.application.port.out.ShippingAddressRepositoryPort;
import com.commercefoundry.domainlayer.shippingaddress.application.service.ShippingAddressWebFacade;
import com.commercefoundry.domainlayer.shippingaddress.application.service.processor.ShippingAddressProcessor;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddress;
import com.commercefoundry.global.config.AuthServiceFeaturesConfig;
import com.commercefoundry.global.properties.ShippingAddressProperties;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 기본 배송지 단일성 · 개수 상한이 <b>동시 요청</b>에서도 지켜지는지 실제 PostgreSQL 에서 본다 — 잠금 · 즉시(행 단위) 유니크 검사는 엔진에 기대므로 H2 로는 볼 수 없다.
 *
 * <p>테스트 트랜잭션을 끄고(NOT_SUPPORTED) 스레드마다 실제 Facade 를 불러 각자 트랜잭션 · 커넥션으로 돈다. 회원 아이디는 테스트마다 새로 만든다 —
 * 커밋된 데이터가 컨테이너에 남는다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=auth",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({
    AuthServiceFeaturesConfig.class,
    MemberLockProcessor.class,
    MemberMapperImpl.class,
    MemberRepositoryAdapter.class,
    QuerydslConfigurer.class,
    ShippingAddressConcurrencyTest.Beans.class,
    ShippingAddressMapperImpl.class,
    ShippingAddressProcessor.class,
    ShippingAddressRepositoryAdapter.class,
    ShippingAddressWebFacade.class,
    ShippingAddressWebPresenter.class
})
class ShippingAddressConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/auth-schema.sql");

    private static final int MAX_PER_MEMBER = 3;
    private static final int ROUNDS = 10;
    private static final AtomicLong MEMBER_IDS = new AtomicLong(1_000L);
    private static final ShippingAddressCommand COMMAND =
        new ShippingAddressCommand(null, "홍길동", "01012345678", "KR", "06236", "서울특별시 강남구 테헤란로 123", null, null, null);

    @Autowired
    private ShippingAddressWebUseCase shippingAddressWebUseCase;

    @Autowired
    private ShippingAddressRepositoryPort shippingAddressRepositoryPort;

    @Autowired
    private MemberRepositoryPort memberRepositoryPort;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("두 스레드가 같은 회원의 다른 주소를 동시에 기본으로 지정해도 둘 다 성공하고, 끝난 뒤 기본은 정확히 하나다")
    void concurrentSetDefaultKeepsSingleDefault() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long memberId = newMember();
            shippingAddressWebUseCase.create(memberId, COMMAND, false);
            long first = Long.parseLong(shippingAddressWebUseCase.create(memberId, COMMAND, false).addressId());
            long second = Long.parseLong(shippingAddressWebUseCase.create(memberId, COMMAND, false).addressId());

            List<Throwable> failures = runConcurrently(List.of(
                () -> {
                    shippingAddressWebUseCase.setDefault(memberId, first);
                    return null;
                },
                () -> {
                    shippingAddressWebUseCase.setDefault(memberId, second);
                    return null;
                }
            ));

            assertThat(failures).as("round %d", round).isEmpty();
            assertThat(shippingAddressRepositoryPort.findAllByMemberId(memberId)).filteredOn(ShippingAddress::defaultAddress)
                .as("round %d", round).singleElement().extracting(ShippingAddress::id).isIn(first, second);
        }
    }

    @Test
    @DisplayName("동시에 기본으로 추가해도 기본은 하나이고, 상한을 넘겨 만들어지지 않는다 — 넘친 요청은 AUTH_021 이다")
    void concurrentCreateRespectsLimitAndSingleDefault() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long memberId = newMember();
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < MAX_PER_MEMBER * 2; i++) {
                boolean requestedDefault = i % 2 == 0;
                tasks.add(() -> shippingAddressWebUseCase.create(memberId, COMMAND, requestedDefault));
            }

            List<Throwable> failures = runConcurrently(tasks);

            assertThat(failures).as("round %d", round).hasSize(MAX_PER_MEMBER).allSatisfy(failure -> assertThat(failure)
                .isInstanceOf(ShippingAddressException.class)
                .extracting(exception -> ((ShippingAddressException) exception).getErrorCode())
                .isEqualTo(ShippingAddressErrorCode.SHIPPING_ADDRESS_LIMIT_EXCEEDED));
            List<ShippingAddress> addresses = shippingAddressRepositoryPort.findAllByMemberId(memberId);
            assertThat(addresses).as("round %d", round).hasSize(MAX_PER_MEMBER);
            assertThat(addresses).filteredOn(ShippingAddress::defaultAddress).as("round %d", round).hasSize(1);
        }
    }

    @Test
    @DisplayName("기본 배송지 삭제와 다른 주소 기본 지정이 겹쳐도 기본은 정확히 하나다")
    void concurrentDeleteAndSetDefault() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long memberId = newMember();
            long defaultAddress = Long.parseLong(shippingAddressWebUseCase.create(memberId, COMMAND, false).addressId());
            long other = Long.parseLong(shippingAddressWebUseCase.create(memberId, COMMAND, false).addressId());
            shippingAddressWebUseCase.create(memberId, COMMAND, false);

            List<Throwable> failures = runConcurrently(List.of(
                () -> {
                    shippingAddressWebUseCase.delete(memberId, defaultAddress);
                    return null;
                },
                () -> {
                    shippingAddressWebUseCase.setDefault(memberId, other);
                    return null;
                }
            ));

            assertThat(failures).as("round %d", round).isEmpty();
            List<ShippingAddress> addresses = shippingAddressRepositoryPort.findAllByMemberId(memberId);
            assertThat(addresses).as("round %d", round).hasSize(2);
            assertThat(addresses).filteredOn(ShippingAddress::defaultAddress).as("round %d", round).hasSize(1);
        }
    }

    @Test
    @DisplayName("전체 교체와 기본 지정이 같은 주소에 겹쳐도 기본은 하나이고, 실패는 AUTH_022(옛 버전) 뿐이다")
    void concurrentUpdateAndSetDefault() throws Exception {
        ShippingAddressCommand changed =
            new ShippingAddressCommand("회사", "김철수", "0212345678", "KR", "04524", "서울특별시 중구 세종대로 110", null, null, null);
        for (int round = 0; round < ROUNDS; round++) {
            long memberId = newMember();
            long defaultAddress = Long.parseLong(shippingAddressWebUseCase.create(memberId, COMMAND, false).addressId());
            long target = Long.parseLong(shippingAddressWebUseCase.create(memberId, COMMAND, false).addressId());

            List<Throwable> failures = runConcurrently(List.of(
                () -> shippingAddressWebUseCase.update(memberId, target, changed),
                () -> {
                    shippingAddressWebUseCase.setDefault(memberId, target);
                    return null;
                }
            ));

            assertThat(failures).as("round %d", round).allSatisfy(failure -> assertThat(failure)
                .isInstanceOf(ShippingAddressException.class)
                .extracting(exception -> ((ShippingAddressException) exception).getErrorCode())
                .isEqualTo(ShippingAddressErrorCode.SHIPPING_ADDRESS_UPDATE_CONFLICT));
            List<ShippingAddress> addresses = shippingAddressRepositoryPort.findAllByMemberId(memberId);
            assertThat(addresses).filteredOn(ShippingAddress::defaultAddress).as("round %d", round).singleElement()
                .extracting(ShippingAddress::id).isIn(defaultAddress, target);
        }
    }

    @Test
    @DisplayName("다른 트랜잭션이 회원 잠금을 쥐고 있으면 시간 상한(5초) 안에 409 · AUTH_023 으로 끊긴다 — 무한정 기다리지 않는다")
    void lockWaitIsBounded() throws Exception {
        long memberId = newMember();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding = holder.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                memberRepositoryPort.findByIdForUpdate(memberId);
                locked.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            Throwable failure = catchThrowable(() -> shippingAddressWebUseCase.create(memberId, COMMAND, false));
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            release.countDown();
            holding.get(30, TimeUnit.SECONDS);

            assertThat(failure).isInstanceOf(ShippingAddressException.class)
                .extracting(exception -> ((ShippingAddressException) exception).getErrorCode())
                .isEqualTo(ShippingAddressErrorCode.SHIPPING_ADDRESS_BUSY);
            assertThat(failure.getCause()).as("PostgreSQL 이 잠금 대기 문장을 취소한다 — 드라이버 57014 → Spring QueryTimeoutException").isInstanceOf(QueryTimeoutException.class);
            assertThat(waitedMillis).isBetween(4_000L, 10_000L);
            assertThat(shippingAddressRepositoryPort.countByMemberId(memberId)).isZero();
        } finally {
            release.countDown();
            holder.shutdownNow();
        }
    }

    private long newMember() {
        long memberId = MEMBER_IDS.incrementAndGet();
        memberRepositoryPort.save(Member.signUp(memberId, "member-" + memberId + "-" + System.nanoTime() + "@example.com", "$2a$10$hash", "홍길동", null));
        return memberId;
    }

    /** 모든 작업을 출발선에 세운 뒤 한꺼번에 놓는다. 실패한 작업의 예외만 모아 돌려준다. */
    private static List<Throwable> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Throwable> failures = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException exception) {
                    failures.add(exception.getCause());
                }
            }
            return failures;
        } finally {
            executor.shutdownNow();
        }
    }

    @TestConfiguration
    static class Beans {

        @Bean
        SnowflakeIdGenerator snowflakeIdGenerator() {
            return new SnowflakeIdGenerator(1, 1);
        }

        @Bean
        ShippingAddressProperties shippingAddressProperties() {
            return new ShippingAddressProperties(MAX_PER_MEMBER);
        }
    }
}
