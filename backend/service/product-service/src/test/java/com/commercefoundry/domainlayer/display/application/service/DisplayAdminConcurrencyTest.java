package com.commercefoundry.domainlayer.display.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response.DisplaySectionAdminResponse;
import com.commercefoundry.domainlayer.display.application.command.DisplaySectionUpsertCommand;
import com.commercefoundry.domainlayer.display.application.exception.DisplayErrorCode;
import com.commercefoundry.domainlayer.display.application.exception.DisplayException;
import com.commercefoundry.domainlayer.display.application.port.in.DisplayAdminUseCase;
import com.commercefoundry.domainlayer.display.application.service.processor.DisplaySectionProcessor;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySection;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
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
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 어드민 전시 쓰기의 전체 잠금 — 실제 Facade 트랜잭션(프록시 · 시간 상한 · 행 잠금)으로 PostgreSQL 에서 본다. 잠금 대기 취소 · READ COMMITTED 재조회는 엔진에 기대므로
 * H2 로는 볼 수 없다. 테스트 메서드는 트랜잭션 밖이다({@code @SpringBootTest} 기본) — 잠금을 쥐는 쪽은 별도 스레드의 {@link TransactionTemplate} 트랜잭션이다.
 * Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다 ({@code CategoryAdminConcurrencyTest} 와 같은 틀).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class DisplayAdminConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    /** 미디어 · 상품이 없는 섹션 — 저장소 공개 주소 · 상품 행 없이 만들 수 있다. */
    private static final DisplaySectionUpsertCommand STORY = DisplaySectionUpsertCommand.builder()
        .sectionType(DisplaySectionType.STORY).title("이야기").visible(true).items(List.of()).build();

    @Autowired
    private DisplayAdminUseCase displayAdminUseCase;

    @Autowired
    private DisplaySectionProcessor displaySectionProcessor;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    @DisplayName("다른 트랜잭션이 전시 잠금을 쥐고 있으면 생성은 시간 상한(5초) 안에 409 · PRODUCT_058 로 끊긴다 — 무한정 기다리지 않고 아무것도 쓰지 않는다")
    void lockWaitIsBounded() throws Exception {
        // 잠글 행이 하나는 있어야 한다 — 빈 표는 잠글 것이 없다(그때만 직렬화되지 않는다)
        displayAdminUseCase.createSection(STORY);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding = holder.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                displaySectionProcessor.lockAll();
                locked.countDown();
                await(release, 20);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            Throwable failure = catchThrowable(() -> displayAdminUseCase.createSection(STORY));
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            release.countDown();
            holding.get(30, TimeUnit.SECONDS);

            assertThat(failure).isInstanceOf(DisplayException.class)
                .extracting(exception -> ((DisplayException) exception).getErrorCode())
                .isEqualTo(DisplayErrorCode.DISPLAY_BUSY);
            assertThat(failure.getCause()).as("PostgreSQL 이 잠금 대기 문장을 취소한다 — 드라이버 57014 → Spring QueryTimeoutException").isInstanceOf(QueryTimeoutException.class);
            assertThat(waitedMillis).isBetween(4_000L, 10_000L);
            assertThat(sectionCount()).isOne();
        } finally {
            release.countDown();
            holder.shutdownNow();
        }
    }

    @Test
    @DisplayName("잠금을 기다린 생성은 앞선 생성이 커밋한 새 행을 순서 판정에 넣는다 — 맨 뒤(최대 + 1) 순서를 받는다")
    void waitedCreateSeesPriorRowForOrder() throws Exception {
        displayAdminUseCase.createSection(STORY);

        DisplaySectionAdminResponse created = createWhileAnotherCreateHoldsLock();

        // 노출 순서는 어드민 응답에 내지 않는 내부 값이다(#305 — 순서는 목록의 배열 순서로 전달한다). 저장된 칸을 직접 본다
        Integer displayOrder = jdbcTemplate.queryForObject(
            "select display_order from product.display_section where id = ?", Integer.class, Long.parseLong(created.sectionId())
        );
        assertThat(displayOrder).as("기존 0 · 앞선 생성 1 다음").isEqualTo(2);
        assertThat(sectionCount()).isEqualTo(3);
        assertThat(displayAdminUseCase.getSections().sections()).extracting(DisplaySectionAdminResponse::sectionId).last().as("목록에서도 맨 뒤다").isEqualTo(created.sectionId());
    }

    @Test
    @DisplayName("잠금을 기다린 생성은 앞선 생성이 커밋한 새 행을 상한 판정에 넣는다 — 49개 + 앞선 생성 1개면 409 · PRODUCT_056 이다")
    void waitedCreateSeesPriorRowForLimit() throws Exception {
        insertSections(DisplaySectionProcessor.MAX_SECTION_COUNT - 1);

        Throwable failure = catchThrowable(this::createWhileAnotherCreateHoldsLock);

        assertThat(failure).isInstanceOf(DisplayException.class)
            .extracting(exception -> ((DisplayException) exception).getErrorCode())
            .isEqualTo(DisplayErrorCode.DISPLAY_SECTION_LIMIT_EXCEEDED);
        assertThat(sectionCount()).isEqualTo(DisplaySectionProcessor.MAX_SECTION_COUNT);
    }

    @Test
    @DisplayName("잠금 · 쓰기 Processor 메서드를 트랜잭션 밖에서 부르면 바로 실패한다 (MANDATORY)")
    void writeOutsideTransactionFails() {
        assertThat(catchThrowable(() -> displaySectionProcessor.lockAll())).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(catchThrowable(() -> displaySectionProcessor.delete(1L))).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(catchThrowable(() -> displaySectionProcessor.reorder(List.of(), List.of()))).isInstanceOf(IllegalTransactionStateException.class);
    }

    /**
     * 다른 스레드가 잠금을 쥔 채 섹션 하나를 만들고(아직 커밋 전) 잠시 쥐고 있는 동안 이 스레드가 생성한다. 이 생성은 잠금에서 기다렸다가
     * 앞선 트랜잭션이 커밋한 뒤 진행한다 — 시간 상한(5초)보다 훨씬 짧게 쥔다.
     */
    private DisplaySectionAdminResponse createWhileAnotherCreateHoldsLock() throws Exception {
        CountDownLatch createdInHolder = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding = holder.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                List<DisplaySection> locked = displaySectionProcessor.lockAll();
                displaySectionProcessor.create(displaySectionProcessor.draftOf(STORY), locked);
                createdInHolder.countDown();
                sleep(1_000);
            }));
            assertThat(createdInHolder.await(10, TimeUnit.SECONDS)).isTrue();

            try {
                return displayAdminUseCase.createSection(STORY);
            } finally {
                holding.get(30, TimeUnit.SECONDS);
            }
        } finally {
            holder.shutdownNow();
        }
    }

    private void insertSections(int count) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.batchUpdate(
            "insert into product.display_section (id, created_at, updated_at, display_order, section_type, visible) values (?, ?, ?, ?, 'STORY', true)",
            IntStream.range(0, count).mapToObj(index -> new Object[] {index + 1L, now, now, index}).toList()
        );
    }

    private long sectionCount() {
        return jdbcTemplate.queryForObject("select count(*) from product.display_section", Long.class);
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
        jdbcTemplate.update("delete from product.display_item");
        jdbcTemplate.update("delete from product.display_section");
    }
}
