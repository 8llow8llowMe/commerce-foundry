package com.commercefoundry.domainlayer.look.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.response.LookAdminResponse;
import com.commercefoundry.domainlayer.look.adapter.out.persistence.repository.LookRepository;
import com.commercefoundry.domainlayer.look.application.command.LookUpsertCommand;
import com.commercefoundry.domainlayer.look.application.exception.LookErrorCode;
import com.commercefoundry.domainlayer.look.application.exception.LookException;
import com.commercefoundry.domainlayer.look.application.port.in.LookAdminUseCase;
import com.commercefoundry.domainlayer.look.application.service.processor.LookProcessor;
import com.commercefoundry.domainlayer.look.domain.model.Look;
import com.commercefoundry.domainlayer.look.domain.model.LookContent;
import com.commercefoundry.domainlayer.look.domain.model.LookLimits;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 어드민 룩 쓰기의 전체 잠금 — 실제 Facade 트랜잭션(프록시 · 시간 상한 · 행 잠금 · 잠금 대기 힌트)으로 PostgreSQL 에서 본다. 잠금 대기 상한 · READ COMMITTED 재조회는
 * 엔진에 기대므로 H2 로는 볼 수 없다. 잠금을 쥐는 쪽은 별도 스레드의 {@link TransactionTemplate} 트랜잭션이다.
 * Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다 ({@code DisplayAdminConcurrencyTest} 와 같은 틀).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "store.media.public-base-url=" + LookAdminConcurrencyTest.MEDIA_BASE
})
class LookAdminConcurrencyTest {

    static final String MEDIA_BASE = "https://cdn.example.com/media";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    private static final long PRODUCT_ID = 700L;

    private static final LookUpsertCommand COMMAND = LookUpsertCommand.builder()
        .title("가을 룩")
        .image(LookUpsertCommand.Image.builder().url(MEDIA_BASE + "/looks/a.jpg").build())
        .visible(true)
        .productIds(List.of(PRODUCT_ID))
        .build();

    @Autowired
    private LookAdminUseCase lookAdminUseCase;

    @Autowired
    private LookProcessor lookProcessor;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "insert into product.product (id, created_at, updated_at, category_id, custom_order, name, preorder, sale_price_amount, sale_price_currency,"
                + " sale_status, visible, version) values (?, ?, ?, 1, false, '상품', false, 1000, 'KRW', 'ON_SALE', true, 0)",
            PRODUCT_ID, now, now
        );
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    @DisplayName("다른 트랜잭션이 룩 잠금을 쥐고 있으면 삭제는 대기 상한 안에 409 · LOOK_008 로 끊긴다 — 무한정 기다리지 않고 아무것도 지우지 않는다")
    void lockWaitIsBounded() throws Exception {
        insertLooks(1);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding = holder.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                lookProcessor.lockAll();
                locked.countDown();
                await(release, 20);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            Throwable failure = catchThrowable(() -> lookAdminUseCase.delete(1L));
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            release.countDown();
            holding.get(30, TimeUnit.SECONDS);

            assertThat(failure).isInstanceOf(LookException.class)
                .extracting(exception -> ((LookException) exception).getErrorCode())
                .isEqualTo(LookErrorCode.LOOK_BUSY);
            // 잠금 대기 힌트(jakarta.persistence.lock.timeout)가 Hibernate 7 · PostgreSQL 에서 먹는다 — 트랜잭션 상한(5초)의 문장 취소(QueryTimeoutException)가 아니라
            // 대기 상한(2초)의 잠금 실패로 끊긴다 (선례: StockRestorationConcurrencyTest ④). Facade 가 잡은 예외를 그대로 원인으로 싣는다
            assertThat(failure.getCause()).as("잠금 대기 상한 → Spring PessimisticLockingFailureException").isInstanceOf(PessimisticLockingFailureException.class);
            assertThat(waitedMillis).as("대기 상한(2초)까지는 기다리고 트랜잭션 상한(5초) 전에 끊긴다")
                .isGreaterThanOrEqualTo(Long.parseLong(LookRepository.LOCK_TIMEOUT_MILLIS) - 500L)
                .isLessThan(4_000L);
            assertThat(lookCount()).isOne();
        } finally {
            release.countDown();
            holder.shutdownNow();
        }
    }

    @Test
    @DisplayName("잠금을 기다린 생성은 앞선 생성이 커밋한 새 행을 순서 판정에 넣는다 — 맨 뒤(최대 + 1) 순서를 받는다")
    void waitedCreateSeesPriorRowForOrder() throws Exception {
        insertLooks(1);

        LookAdminResponse created = createWhileAnotherCreateHoldsLock();

        Integer displayOrder = jdbcTemplate.queryForObject("select display_order from product.look where id = ?", Integer.class, Long.parseLong(created.lookId()));
        assertThat(displayOrder).as("기존 0 · 앞선 생성 1 다음").isEqualTo(2);
        assertThat(lookCount()).isEqualTo(3);
        assertThat(lookAdminUseCase.getLooks().looks()).extracting(LookAdminResponse::lookId).last().as("목록에서도 맨 뒤다").isEqualTo(created.lookId());
    }

    @Test
    @DisplayName("잠금을 기다린 생성은 앞선 생성이 커밋한 새 행을 상한 판정에 넣는다 — 99개 + 앞선 생성 1개면 409 · LOOK_002 다")
    void waitedCreateSeesPriorRowForLimit() {
        insertLooks(LookLimits.MAX_LOOK_COUNT - 1);

        Throwable failure = catchThrowable(this::createWhileAnotherCreateHoldsLock);

        assertThat(failure).isInstanceOf(LookException.class)
            .extracting(exception -> ((LookException) exception).getErrorCode())
            .isEqualTo(LookErrorCode.LOOK_LIMIT_EXCEEDED);
        assertThat(lookCount()).isEqualTo(LookLimits.MAX_LOOK_COUNT);
    }

    @Test
    @DisplayName("잠금 · 쓰기 Processor 메서드를 트랜잭션 밖에서 부르면 바로 실패한다 (MANDATORY)")
    void writeOutsideTransactionFails() {
        assertThat(catchThrowable(() -> lookProcessor.lockAll())).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(catchThrowable(() -> lookProcessor.delete(1L))).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(catchThrowable(() -> lookProcessor.reorder(List.of(), List.of()))).isInstanceOf(IllegalTransactionStateException.class);
    }

    /**
     * 다른 스레드가 잠금을 쥔 채 룩 하나를 만들고(아직 커밋 전) 잠시 쥐고 있는 동안 이 스레드가 생성한다. 이 생성은 잠금에서 기다렸다가
     * 앞선 트랜잭션이 커밋한 뒤 진행한다 — 잠금 대기 상한(2초)보다 짧게 쥔다.
     */
    private LookAdminResponse createWhileAnotherCreateHoldsLock() throws Exception {
        CountDownLatch createdInHolder = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding = holder.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                List<Look> locked = lookProcessor.lockAll();
                LookContent draft = lookProcessor.draftOf(COMMAND);
                lookProcessor.create(draft, locked);
                createdInHolder.countDown();
                sleep(800);
            }));
            assertThat(createdInHolder.await(10, TimeUnit.SECONDS)).isTrue();

            try {
                return lookAdminUseCase.create(COMMAND);
            } finally {
                holding.get(30, TimeUnit.SECONDS);
            }
        } finally {
            holder.shutdownNow();
        }
    }

    /** 아이디 1..count, 노출 순서 0..count-1 의 룩과 아이템 하나씩. */
    private void insertLooks(int count) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.batchUpdate(
            "insert into product.look (id, created_at, updated_at, title, image_url, visible, display_order) values (?, ?, ?, '룩', ?, true, ?)",
            IntStream.range(0, count).mapToObj(index -> new Object[] {index + 1L, now, now, MEDIA_BASE + "/looks/a.jpg", index}).toList()
        );
        jdbcTemplate.batchUpdate(
            "insert into product.look_item (id, created_at, updated_at, look_id, product_id, display_order) values (?, ?, ?, ?, ?, 0)",
            IntStream.range(0, count).mapToObj(index -> new Object[] {10_000L + index, now, now, index + 1L, PRODUCT_ID}).toList()
        );
    }

    private long lookCount() {
        return jdbcTemplate.queryForObject("select count(*) from product.look", Long.class);
    }

    private static void await(CountDownLatch latch, int seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void cleanUp() {
        jdbcTemplate.update("delete from product.look_item");
        jdbcTemplate.update("delete from product.look");
        jdbcTemplate.update("delete from product.product where id = ?", PRODUCT_ID);
    }
}
