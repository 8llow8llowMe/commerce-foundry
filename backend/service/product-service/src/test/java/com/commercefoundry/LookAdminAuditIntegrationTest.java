package com.commercefoundry;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.look.adapter.out.persistence.repository.LookItemRepository;
import com.commercefoundry.domainlayer.look.adapter.out.persistence.repository.LookRepository;
import com.commercefoundry.domainlayer.look.application.command.LookUpsertCommand;
import com.commercefoundry.domainlayer.look.application.exception.LookErrorCode;
import com.commercefoundry.domainlayer.look.application.exception.LookException;
import com.commercefoundry.domainlayer.look.application.port.in.LookAdminUseCase;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductRepository;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.security.audit.AdminAuditLog;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 룩 어드민 쓰기 감사(#163 PR-C)가 실제 Facade 트랜잭션(프록시 · JPA 트랜잭션 관리자 · H2)의 <b>커밋 결과</b>로 남는지 본다 — 선례 {@link CategoryAdminAuditIntegrationTest}.
 * 줄에는 행위자 · 대상 · 동작 · 결과만 남고 룩 내용(제목 · 설명 · 이미지 주소 · 대체 텍스트 · 구성 상품)은 싣지 않는다.
 *
 * <p>애노테이션은 {@link DisplayFlowIntegrationTest} 와 같아 컨텍스트를 같이 쓴다(룩 생성에 미디어 저장소 공개 주소가 필요하다).
 * 공유 H2 라 테스트가 끝나면 룩 행을 모두 지우고 넣은 상품을 아이디로 지운다.
 */
@SpringBootTest(properties = {"store.media.public-base-url=https://cdn.example.com/media/", "store.features.preorder=true", "store.features.looks=true"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LookAdminAuditIntegrationTest {

    private static final long ACTOR = 4_611_686_018_427_387_904L;
    private static final long PRODUCT_ID = 9_301L;
    private static final long MISSING_LOOK = 999_999_999L;
    private static final long MISSING_PRODUCT = 999_999_998L;
    /** 요청 본문에만 넣는 표식 — 감사 줄에 나오면 본문이 새어 나간 것이다. */
    private static final String SENTINEL = "SENTINEL-LOOK-BODY";
    private static final LookUpsertCommand COMMAND = command(PRODUCT_ID);

    @Autowired
    private LookAdminUseCase lookAdminUseCase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private LookRepository lookRepository;

    @Autowired
    private LookItemRepository lookItemRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private MockMvcTester mvc;

    private final Logger auditLogger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
    private ListAppender<ILoggingEvent> audit;

    @BeforeEach
    void setUp() {
        productRepository.save(ProductEntity.builder()
            .id(PRODUCT_ID).categoryId(10L).name("룩 감사 상품").saleStatus(SaleStatus.ON_SALE).visible(true)
            .salePriceAmount(39_000L).salePriceCurrency(KRW).customOrder(false)
            .build());
        audit = new ListAppender<>();
        audit.start();
        auditLogger.addAppender(audit);
    }

    @AfterEach
    void cleanUp() {
        auditLogger.detachAppender(audit);
        lookItemRepository.deleteAllInBatch();
        lookRepository.deleteAllInBatch();
        productRepository.deleteAllById(List.of(PRODUCT_ID));
    }

    @Test
    @DisplayName("커밋된 생성은 만든 아이디로 SUCCESS 한 줄이고, 줄에 본문(제목 · 설명 · 이미지 주소 · 상품)이 없다")
    void committedCreateIsSuccess() {
        long id = create();

        assertThat(lines()).containsExactly(line("LOOK_CREATE", "look:" + id, "SUCCESS"));
        assertNoBody();
        assertThat(lookRepository.existsById(id)).isTrue();
    }

    @Test
    @DisplayName("커밋된 수정 · 순서 변경 · 삭제는 각각 SUCCESS 한 줄이다 — 순서 변경은 아이디 목록 없이 고정 대상 look:order 다")
    void committedWritesAreSuccess() {
        long id = create();
        audit.list.clear();

        lookAdminUseCase.update(id, COMMAND, ACTOR);
        lookAdminUseCase.reorder(List.of(id), ACTOR);
        lookAdminUseCase.delete(id, ACTOR);

        assertThat(lines()).containsExactly(
            line("LOOK_UPDATE", "look:" + id, "SUCCESS"),
            line("LOOK_REORDER", "look:order", "SUCCESS"),
            line("LOOK_DELETE", "look:" + id, "SUCCESS")
        );
        assertNoBody();
        assertThat(lookRepository.existsById(id)).isFalse();
    }

    @Test
    @DisplayName("업무 실패는 FAILURE 한 줄이다 — 없는 룩 수정 · 삭제(404 · LOOK_001), 순서 집합 불일치(400 · LOOK_007), 없는 상품으로 생성(400 · LOOK_004, target=-)")
    void businessFailuresAreFailure() {
        assertErrorCode(() -> lookAdminUseCase.update(MISSING_LOOK, COMMAND, ACTOR), LookErrorCode.NOT_FOUND_LOOK);
        assertErrorCode(() -> lookAdminUseCase.delete(MISSING_LOOK, ACTOR), LookErrorCode.NOT_FOUND_LOOK);
        assertErrorCode(() -> lookAdminUseCase.reorder(List.of(MISSING_LOOK), ACTOR), LookErrorCode.LOOK_ORDER_MISMATCH);
        assertErrorCode(() -> lookAdminUseCase.create(command(MISSING_PRODUCT), ACTOR), LookErrorCode.LOOK_PRODUCT_NOT_FOUND);

        assertThat(lines()).containsExactly(
            line("LOOK_UPDATE", "look:" + MISSING_LOOK, "FAILURE"),
            line("LOOK_DELETE", "look:" + MISSING_LOOK, "FAILURE"),
            line("LOOK_REORDER", "look:order", "FAILURE"),
            line("LOOK_CREATE", "-", "FAILURE")
        );
        assertThat(lines()).noneMatch(line -> line.contains(String.valueOf(MISSING_PRODUCT)));
        assertNoBody();
        assertThat(lookRepository.count()).isZero();
    }

    @Test
    @DisplayName("업무는 성공했지만 바깥 트랜잭션이 롤백되면 생성 · 수정 · 순서 변경 줄은 롤백 뒤에 FAILURE 로 남고 룩도 없다 — 롤백 전에는 아무 줄도 쓰지 않는다")
    void rolledBackWritesAreFailure() {
        AtomicReference<Long> id = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            id.set(Long.parseLong(lookAdminUseCase.create(COMMAND, ACTOR).lookId()));
            lookAdminUseCase.update(id.get(), COMMAND, ACTOR);
            lookAdminUseCase.reorder(List.of(id.get()), ACTOR);
            assertThat(audit.list).as("트랜잭션이 끝나기 전에는 줄을 쓰지 않는다").isEmpty();
            status.setRollbackOnly();
        });

        assertThat(lines()).containsExactly(
            line("LOOK_CREATE", "look:" + id.get(), "FAILURE"),
            line("LOOK_UPDATE", "look:" + id.get(), "FAILURE"),
            line("LOOK_REORDER", "look:order", "FAILURE")
        );
        assertThat(lookRepository.existsById(id.get())).isFalse();
    }

    @Test
    @DisplayName("롤백된 삭제도 FAILURE 이고 룩은 그대로 남는다")
    void rolledBackDeleteIsFailure() {
        long id = create();
        audit.list.clear();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            lookAdminUseCase.delete(id, ACTOR);
            status.setRollbackOnly();
        });

        assertThat(lines()).containsExactly(line("LOOK_DELETE", "look:" + id, "FAILURE"));
        assertThat(lookRepository.existsById(id)).isTrue();
    }

    @Test
    @DisplayName("HTTP 경로는 access 토큰의 memberId 를 행위자로 넘긴다 — 없는 룩 삭제는 404 와 FAILURE 한 줄이다")
    void controllerPassesTokenMemberId() {
        String token = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.ADMIN).tokenValue();

        assertThat(mvc.delete().uri("/api/v1/admin/looks/" + MISSING_LOOK).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange())
            .hasStatus(HttpStatus.NOT_FOUND);

        assertThat(lines()).containsExactly("action=LOOK_DELETE actorMemberId=" + TestTokens.MEMBER_ID + " target=look:" + MISSING_LOOK + " result=FAILURE");
    }

    private long create() {
        return Long.parseLong(lookAdminUseCase.create(COMMAND, ACTOR).lookId());
    }

    private static LookUpsertCommand command(long productId) {
        return LookUpsertCommand.builder()
            .title(SENTINEL + "-title").description(SENTINEL + "-description")
            .image(LookUpsertCommand.Image.builder().url("https://cdn.example.com/media/looks/" + SENTINEL + ".jpg").altText(SENTINEL + "-alt").build())
            .visible(true).productIds(List.of(productId))
            .build();
    }

    private static void assertErrorCode(ThrowingCallable call, LookErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(LookException.class, e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
    }

    private void assertNoBody() {
        // 구성 상품 아이디는 문자열로 찾지 않는다 — 대상의 룩 아이디(Snowflake)에 우연히 들어갈 수 있다. 줄 전체를 containsExactly 로 맞춰 본다
        assertThat(lines()).noneMatch(line -> line.contains(SENTINEL) || line.contains("cdn.example.com"));
    }

    private List<String> lines() {
        return audit.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String line(String action, String target, String result) {
        return "action=" + action + " actorMemberId=" + ACTOR + " target=" + target + " result=" + result;
    }
}
