package com.commercefoundry;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.SkuRepository;
import com.commercefoundry.domainlayer.product.application.command.StockAdjustmentCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.StockAdminUseCase;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentReason;
import com.commercefoundry.domainlayer.productdraft.adapter.out.persistence.repository.ProductDraftRepository;
import com.commercefoundry.domainlayer.productdraft.application.command.ProductDraftCreateCommand;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.in.ProductDraftAdminUseCase;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageType;
import com.commercefoundry.domainlayer.skucost.application.command.SkuCostCreateCommand;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostErrorCode;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostException;
import com.commercefoundry.domainlayer.skucost.application.port.in.SkuCostAdminUseCase;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType;
import com.commercefoundry.security.audit.AdminAuditLog;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재고 조정 · SKU 원가 추가 · AI 상품 초안 생성 · 거절 감사(#163 PR-C′)가 실제 Facade 트랜잭션(프록시 · JPA 트랜잭션 관리자 · H2)의 <b>커밋 결과</b>로 남는지 본다
 * — 선례 {@link LookAdminAuditIntegrationTest}. 줄에는 행위자 · 대상 · 동작 · 결과만 남고 수량 · 사유 · 메모 · 금액 · 통화 · 원가 유형 · 적용 시각 · 초안 입력 문구 ·
 * 이미지 주소는 싣지 않는다(정본은 각 이력 표 · 초안 행).
 *
 * <p>애노테이션은 {@link LookAdminAuditIntegrationTest} 와 같아 컨텍스트를 같이 쓴다. 공유 H2 라 이 클래스의 SKU 아이디 · 행위자로 만든 행을 시작 전과 끝난 뒤
 * 모두 지운다 — 다른 테스트의 정리에 기대지 않고, 개수도 이 SKU · 행위자로만 센다.
 */
@SpringBootTest(properties = {"store.media.public-base-url=https://cdn.example.com/media/", "store.features.preorder=true", "store.features.looks=true"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StockCostDraftAdminAuditIntegrationTest {

    /** 토큰의 memberId({@link TestTokens#MEMBER_ID})와 다르게 둔다 — HTTP 경로 테스트가 토큰 값이 찍히는지 가린다. */
    private static final long ACTOR = 77_001L;
    private static final long SKU_ID = 9_611L;
    private static final long MISSING_SKU = 999_999_997L;
    private static final long MISSING_DRAFT = 999_999_996L;
    /** 요청 본문에만 넣는 표식 — 감사 줄에 나오면 본문이 새어 나간 것이다. */
    private static final String SENTINEL = "SENTINEL-PRC2-BODY";
    /** 본문의 수량 · 금액. 대상 아이디와 겹치지 않는 값이다. */
    private static final long QUANTITY = 7_373L;
    private static final long AMOUNT = 12_345L;
    /** 독립된 숫자로만 찾는다 — 초안 아이디(Snowflake)의 자릿수 안에 우연히 든 것은 새어 나간 값이 아니다. */
    private static final Pattern BODY_NUMBER = Pattern.compile("(^|\\D)(" + QUANTITY + "|" + AMOUNT + ")(\\D|$)");

    @Autowired
    private StockAdminUseCase stockAdminUseCase;

    @Autowired
    private SkuCostAdminUseCase skuCostAdminUseCase;

    @Autowired
    private ProductDraftAdminUseCase productDraftAdminUseCase;

    @Autowired
    private SkuRepository skuRepository;

    @Autowired
    private ProductDraftRepository productDraftRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvcTester mvc;

    private final Logger auditLogger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
    private ListAppender<ILoggingEvent> audit;

    @BeforeEach
    void setUp() {
        deleteRows();
        skuRepository.save(SkuEntity.builder()
            .id(SKU_ID).productId(9_610L).optionValueKey("").additionalPriceAmount(0).additionalPriceCurrency(KRW).saleStatus(SaleStatus.ON_SALE)
            .build());
        audit = new ListAppender<>();
        audit.start();
        auditLogger.addAppender(audit);
    }

    @AfterEach
    void cleanUp() {
        auditLogger.detachAppender(audit);
        deleteRows();
    }

    @Test
    @DisplayName("재고 — 커밋된 조정은 sku:<id> SUCCESS 한 줄이고 이력이 한 건이다. 줄에 수량 · 사유 · 메모가 없다")
    void stockCommittedIsSuccess() {
        stockAdminUseCase.adjustStock(stockCommand(SKU_ID, 0L));

        assertThat(lines()).containsExactly(line("STOCK_ADJUST", "sku:" + SKU_ID, "SUCCESS"));
        assertNoBody();
        assertThat(stockRows()).isOne();
        assertThat(historyRows()).isOne();
    }

    @Test
    @DisplayName("재고 — 업무 실패는 FAILURE 한 줄이다: 없는 SKU(404 · PRODUCT_002), 기대 수량 불일치(409 · PRODUCT_020). 이력은 남지 않는다")
    void stockBusinessFailuresAreFailure() {
        assertProductError(() -> stockAdminUseCase.adjustStock(stockCommand(MISSING_SKU, 0L)), ProductErrorCode.NOT_FOUND_SKU);
        assertProductError(() -> stockAdminUseCase.adjustStock(stockCommand(SKU_ID, 5L)), ProductErrorCode.STOCK_ADJUSTMENT_CONFLICT);

        assertThat(lines()).containsExactly(
            line("STOCK_ADJUST", "sku:" + MISSING_SKU, "FAILURE"),
            line("STOCK_ADJUST", "sku:" + SKU_ID, "FAILURE")
        );
        assertNoBody();
        assertThat(stockRows()).isZero();
        assertThat(historyRows()).isZero();
    }

    @Test
    @DisplayName("재고 — 업무는 성공했지만 바깥 트랜잭션이 롤백되면 FAILURE 이고 재고 행 · 조정 이력도 없다. 롤백 전에는 줄을 쓰지 않는다")
    void stockRolledBackIsFailure() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            stockAdminUseCase.adjustStock(stockCommand(SKU_ID, 0L));
            assertThat(audit.list).as("트랜잭션이 끝나기 전에는 줄을 쓰지 않는다").isEmpty();
            status.setRollbackOnly();
        });

        assertThat(lines()).containsExactly(line("STOCK_ADJUST", "sku:" + SKU_ID, "FAILURE"));
        assertThat(stockRows()).isZero();
        assertThat(historyRows()).isZero();
    }

    @Test
    @DisplayName("재고 — HTTP 경로는 access 토큰의 memberId 를 행위자로 넘긴다. 없는 SKU 조정은 404 와 FAILURE 한 줄이다")
    void stockControllerPassesTokenMemberId() {
        String body = "{\"quantity\": " + QUANTITY + ", \"expectedQuantity\": 0, \"reason\": \"RESTOCK\", \"memo\": \"" + SENTINEL + "\"}";

        assertThat(mvc.patch().uri("/api/v1/admin/stocks/" + MISSING_SKU).header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON).content(body).exchange())
            .hasStatus(HttpStatus.NOT_FOUND);

        assertThat(lines()).containsExactly("action=STOCK_ADJUST actorMemberId=" + TestTokens.MEMBER_ID + " target=sku:" + MISSING_SKU + " result=FAILURE");
        assertNoBody();
    }

    @Test
    @DisplayName("원가 — 커밋된 추가는 원가 행 아이디가 아니라 sku:<id> 대상 SUCCESS 한 줄이다. 줄에 금액 · 통화 · 유형 · 사유가 없다")
    void costCommittedIsSuccess() {
        skuCostAdminUseCase.createCost(costCommand(SKU_ID, null));

        assertThat(lines()).containsExactly(line("SKU_COST_CREATE", "sku:" + SKU_ID, "SUCCESS"));
        assertNoBody();
        assertThat(lines()).noneMatch(line -> line.contains("KRW") || line.contains("GOODS"));
        assertThat(costRows()).isOne();
    }

    @Test
    @DisplayName("원가 — 업무 실패는 FAILURE 한 줄이다: 없는 SKU(404 · PRODUCT_002), 5분보다 뒤 적용 시작(UseCase 안의 도메인 400 · SKU_COST_001). 행은 남지 않는다")
    void costBusinessFailuresAreFailure() {
        assertProductError(() -> skuCostAdminUseCase.createCost(costCommand(MISSING_SKU, null)), ProductErrorCode.NOT_FOUND_SKU);
        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        assertThatThrownBy(() -> skuCostAdminUseCase.createCost(costCommand(SKU_ID, future)))
            .isInstanceOfSatisfying(SkuCostException.class, e -> assertThat(e.getErrorCode()).isEqualTo(SkuCostErrorCode.EFFECTIVE_FROM_IN_FUTURE));

        assertThat(lines()).containsExactly(
            line("SKU_COST_CREATE", "sku:" + MISSING_SKU, "FAILURE"),
            line("SKU_COST_CREATE", "sku:" + SKU_ID, "FAILURE")
        );
        assertNoBody();
        assertThat(costRows()).isZero();
    }

    @Test
    @DisplayName("원가 — 업무는 성공했지만 바깥 트랜잭션이 롤백되면 FAILURE 이고 원가 이력 행도 없다")
    void costRolledBackIsFailure() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            skuCostAdminUseCase.createCost(costCommand(SKU_ID, null));
            assertThat(audit.list).as("트랜잭션이 끝나기 전에는 줄을 쓰지 않는다").isEmpty();
            status.setRollbackOnly();
        });

        assertThat(lines()).containsExactly(line("SKU_COST_CREATE", "sku:" + SKU_ID, "FAILURE"));
        assertThat(costRows()).isZero();
    }

    @Test
    @DisplayName("초안 — 커밋된 생성 · 거절은 product-draft:<id> SUCCESS 줄이고, 이미 거절한 초안의 재전송(그대로 200)도 SUCCESS 다. 줄에 입력 문구 · 이미지 주소가 없다")
    void draftCommittedIsSuccess() {
        long id = createDraft();
        productDraftAdminUseCase.reject(id, ACTOR);
        productDraftAdminUseCase.reject(id, ACTOR);

        assertThat(lines()).containsExactly(
            line("PRODUCT_DRAFT_CREATE", "product-draft:" + id, "SUCCESS"),
            line("PRODUCT_DRAFT_REJECT", "product-draft:" + id, "SUCCESS"),
            line("PRODUCT_DRAFT_REJECT", "product-draft:" + id, "SUCCESS")
        );
        assertNoBody();
        assertThat(productDraftRepository.existsById(id)).isTrue();
    }

    @Test
    @DisplayName("초안 — 업무 실패는 FAILURE 한 줄이다: 대표 이미지 두 장으로 생성(UseCase 안의 도메인 400 · PRODUCT_DRAFT_034, target=-), 없는 초안 거절(404 · PRODUCT_DRAFT_001)")
    void draftBusinessFailuresAreFailure() {
        ProductDraftCreateCommand twoThumbnails = draftCommand(List.of(thumbnail("-1"), thumbnail("-2")));
        assertDraftError(() -> productDraftAdminUseCase.create(twoThumbnails), ProductDraftErrorCode.THUMBNAIL_DUPLICATED);
        assertDraftError(() -> productDraftAdminUseCase.reject(MISSING_DRAFT, ACTOR), ProductDraftErrorCode.NOT_FOUND_DRAFT);

        assertThat(lines()).containsExactly(
            line("PRODUCT_DRAFT_CREATE", "-", "FAILURE"),
            line("PRODUCT_DRAFT_REJECT", "product-draft:" + MISSING_DRAFT, "FAILURE")
        );
        assertNoBody();
        assertThat(draftRows()).isZero();
    }

    @Test
    @DisplayName("초안 — 업무는 성공했지만 바깥 트랜잭션이 롤백되면 생성 · 거절 줄은 발급됐던 아이디로 FAILURE 이고 초안 행도 없다")
    void draftRolledBackIsFailure() {
        AtomicReference<Long> id = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            id.set(createDraft());
            productDraftAdminUseCase.reject(id.get(), ACTOR);
            assertThat(audit.list).as("트랜잭션이 끝나기 전에는 줄을 쓰지 않는다").isEmpty();
            status.setRollbackOnly();
        });

        assertThat(lines()).containsExactly(
            line("PRODUCT_DRAFT_CREATE", "product-draft:" + id.get(), "FAILURE"),
            line("PRODUCT_DRAFT_REJECT", "product-draft:" + id.get(), "FAILURE")
        );
        assertThat(productDraftRepository.existsById(id.get())).isFalse();
        assertThat(draftRows()).isZero();
    }

    @Test
    @DisplayName("초안 — HTTP 경로는 access 토큰의 memberId 를 행위자로 넘긴다. 없는 초안 거절은 404 와 FAILURE 한 줄이다")
    void draftControllerPassesTokenMemberId() {
        assertThat(mvc.post().uri("/api/v1/admin/product-drafts/" + MISSING_DRAFT + "/reject").header(HttpHeaders.AUTHORIZATION, bearer()).exchange())
            .hasStatus(HttpStatus.NOT_FOUND);

        assertThat(lines()).containsExactly(
            "action=PRODUCT_DRAFT_REJECT actorMemberId=" + TestTokens.MEMBER_ID + " target=product-draft:" + MISSING_DRAFT + " result=FAILURE"
        );
    }

    private static StockAdjustmentCommand stockCommand(long skuId, long expectedQuantity) {
        return StockAdjustmentCommand.builder()
            .skuId(skuId).quantity(QUANTITY).expectedQuantity(expectedQuantity).reason(StockAdjustmentReason.RESTOCK).memo(SENTINEL).adminMemberId(ACTOR)
            .build();
    }

    private static SkuCostCreateCommand costCommand(long skuId, Instant effectiveFrom) {
        return SkuCostCreateCommand.builder()
            .skuId(skuId).costType(SkuCostType.GOODS).amount(Money.of(AMOUNT, KRW)).effectiveFrom(effectiveFrom).reason(SENTINEL).adminMemberId(ACTOR)
            .build();
    }

    private long createDraft() {
        return Long.parseLong(productDraftAdminUseCase.create(draftCommand(List.of(thumbnail("")))).draftId());
    }

    private static ProductDraftImage thumbnail(String suffix) {
        return new ProductDraftImage("https://cdn.example.com/media/drafts/" + SENTINEL + suffix + ".jpg", ProductDraftImageType.THUMBNAIL, null, null);
    }

    private static ProductDraftCreateCommand draftCommand(List<ProductDraftImage> images) {
        ProductDraftFacts facts = new ProductDraftFacts(
            SENTINEL + "-name", null, List.of(SENTINEL + "-material"), null, SENTINEL + "-measure", null, null, SENTINEL + "-tone", List.of(SENTINEL + "-must"), null
        );
        return ProductDraftCreateCommand.builder().requestedByMemberId(ACTOR).facts(facts).images(images).build();
    }

    private void deleteRows() {
        jdbcTemplate.update("delete from product.stock_adjustment_history where sku_id in (?, ?)", SKU_ID, MISSING_SKU);
        jdbcTemplate.update("delete from product.stock where sku_id in (?, ?)", SKU_ID, MISSING_SKU);
        jdbcTemplate.update("delete from product.sku_cost where sku_id in (?, ?)", SKU_ID, MISSING_SKU);
        jdbcTemplate.update("delete from product.product_draft where requested_by_member_id = ?", ACTOR);
        jdbcTemplate.update("delete from product.sku where id = ?", SKU_ID);
    }

    private long stockRows() {
        return jdbcTemplate.queryForObject("select count(*) from product.stock where sku_id = ?", Long.class, SKU_ID);
    }

    private long historyRows() {
        return jdbcTemplate.queryForObject("select count(*) from product.stock_adjustment_history where sku_id in (?, ?)", Long.class, SKU_ID, MISSING_SKU);
    }

    private long costRows() {
        return jdbcTemplate.queryForObject("select count(*) from product.sku_cost where sku_id in (?, ?)", Long.class, SKU_ID, MISSING_SKU);
    }

    private long draftRows() {
        return jdbcTemplate.queryForObject("select count(*) from product.product_draft where requested_by_member_id = ?", Long.class, ACTOR);
    }

    private static String bearer() {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.ADMIN).tokenValue();
    }

    private static void assertProductError(ThrowingCallable call, ProductErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProductException.class, e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
    }

    private static void assertDraftError(ThrowingCallable call, ProductDraftErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProductDraftException.class, e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
    }

    /** 본문 문구 · 이미지 주소 · 사유 이름 · 수량 · 금액이 줄에 없다. 줄 전체는 각 테스트가 containsExactly 로 맞춰 본다. */
    private void assertNoBody() {
        assertThat(lines()).noneMatch(line -> line.contains(SENTINEL) || line.contains("cdn.example.com") || line.contains("RESTOCK")
            || BODY_NUMBER.matcher(line).find());
    }

    private List<String> lines() {
        return audit.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String line(String action, String target, String result) {
        return "action=" + action + " actorMemberId=" + ACTOR + " target=" + target + " result=" + result;
    }
}
