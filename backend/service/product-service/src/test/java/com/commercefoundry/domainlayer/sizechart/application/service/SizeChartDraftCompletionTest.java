package com.commercefoundry.domainlayer.sizechart.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.product.application.service.processor.ProductImageLookupProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductLookupProcessor;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.ProductSizeChartRepositoryAdapter;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.SizeChartDraftRepositoryAdapter;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.SizeChartTemplateRepositoryAdapter;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository.ProductSizeCellRepository;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository.ProductSizeChartRepository;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository.ProductSizeRowRepository;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository.SizeChartDraftRepository;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository.SizeChartTemplateColumnRepository;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository.SizeChartTemplateRepository;
import com.commercefoundry.domainlayer.sizechart.application.command.ProductSizeChartUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftImage;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationStatus;
import com.commercefoundry.domainlayer.sizechart.application.port.out.ProductSizeChartRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartDraftRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartGenerationPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartTemplateRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartGenerationRegistrationQueryResult;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartGenerationResultQueryResult;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.ProductSizeChartProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftApplyProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftBatchProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftInputProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftRemoteProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftValidationProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartImageSourceProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartTextSourceProcessor;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeRow;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplate;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplateColumn;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.global.config.SizeChartDraftProperties;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #217 완료 기준 — 치수 텍스트 · 승인한 사이즈표 이미지로 초안이 만들어지고, 운영자가 확정하면 상품 사이즈표가 바뀐다.
 *
 * <p>product 쪽은 실제 Facade · Processor · 저장소(H2)를 지난다. operations 작업 등록 · 결과 조회({@link SizeChartGenerationPort})만 스텁이고,
 * 결과 JSON 은 operations {@code SizeChartGenerationValidationProcessor} 가 내는 모양(단위 · 행 · 서버 경고)과 같다 — AI 는 부르지 않는다.
 * operations 쪽 IMAGE 핸들러 · 해시 재검증은 {@code SizeChartGenerationImageJobProcessorTest} 가 본다.
 */
@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:size-chart-completion;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product",
    "size-chart.draft.image-enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({QuerydslConfigurer.class, ProductServiceFeaturesConfig.class, ProductSizeChartRepositoryAdapter.class,
    com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor.class,
    com.commercefoundry.domainlayer.sizechart.adapter.in.admin.presenter.SizeChartDraftAdminPresenter.class,
    SizeChartTemplateRepositoryAdapter.class, SizeChartDraftRepositoryAdapter.class, ProductSizeChartProcessor.class,
    SizeChartDraftProcessor.class, SizeChartDraftApplyProcessor.class, SizeChartDraftValidationProcessor.class,
    SizeChartDraftRemoteProcessor.class, SizeChartDraftInputProcessor.class, SizeChartDraftFacade.class,
    SizeChartDraftProperties.class, SizeChartTextSourceProcessor.class, SizeChartDraftBatchProcessor.class,
    SizeChartDraftCompletionTest.TimeConfig.class})
class SizeChartDraftCompletionTest {
    private static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");
    private static final String HASH = "c".repeat(64);
    private static final long PRODUCT = 10;
    private static final long TEMPLATE = 20;
    private static final long CHEST = 30;
    private static final long LENGTH = 31;
    private static final long ACTOR = 40;

    @Autowired private SizeChartDraftFacade facade;
    @Autowired private SizeChartDraftInputProcessor inputs;
    @Autowired private SizeChartDraftRepositoryPort drafts;
    @Autowired private ProductSizeChartRepositoryPort charts;
    @Autowired private SizeChartTemplateRepositoryPort templates;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ProductSizeCellRepository cells;
    @Autowired private ProductSizeRowRepository rows;
    @Autowired private ProductSizeChartRepository chartRepository;
    @Autowired private SizeChartDraftRepository draftRepository;
    @Autowired private SizeChartTemplateColumnRepository columns;
    @Autowired private SizeChartTemplateRepository templateRepository;
    @MockitoBean private ProductLookupProcessor products;
    @MockitoBean private ProductImageLookupProcessor productImages;
    @MockitoBean private SizeChartImageSourceProcessor imageSources;
    @MockitoBean private SizeChartGenerationPort generation;
    @MockitoBean private SnowflakeIdGenerator ids;
    @MockitoBean private Clock clock;
    private final AtomicLong sequence = new AtomicLong(1000);

    @BeforeEach
    void setup() {
        tx(() -> {
            cells.deleteAllInBatch(); rows.deleteAllInBatch(); chartRepository.deleteAllInBatch(); draftRepository.deleteAllInBatch();
            columns.deleteAllInBatch(); templateRepository.deleteAllInBatch();
            templates.insert(new SizeChartTemplate(TEMPLATE, "Top", SizeChartUnit.CM), List.of(
                new SizeChartTemplateColumn(CHEST, TEMPLATE, "Chest", null, 0), new SizeChartTemplateColumn(LENGTH, TEMPLATE, "Length", null, 1)));
            return null;
        });
        when(clock.instant()).thenReturn(NOW);
        when(ids.generateId()).thenAnswer(ignored -> sequence.incrementAndGet());
        when(products.findAllIncludingHidden(List.of(PRODUCT))).thenReturn(Map.of(PRODUCT, mock(Product.class)));
        when(productImages.findStillImagesIncludingHidden(PRODUCT)).thenReturn(List.of());
        when(generation.register(any())).thenAnswer(call -> {
            SizeChartDraft draft = call.getArgument(0);
            return new SizeChartGenerationRegistrationQueryResult(draft.id(), draft.id() + 10_000, draft.input().inputHash());
        });
    }

    @Test
    void textMeasurementsBecomeADraftAndApplyingItReplacesTheProductChart() {
        var created = facade.create(new SizeChartDraftCreateCommand(PRODUCT, TEMPLATE, ACTOR, UUID.randomUUID(), "TEXT", "S Chest 50 Length 70 M Chest 53 Length 72"));
        SizeChartDraft requested = drafts.findById(created.draftId()).orElseThrow();
        assertThat(requested.status()).isEqualTo(SizeChartDraftStatus.REQUESTED);
        assertThat(requested.jobId()).isPositive();
        assertThat(charts.findByProductId(PRODUCT)).isEmpty();

        succeed(requested, "{\"unit\":\"CM\",\"rows\":["
            + "{\"sizeLabel\":\"S\",\"cells\":[{\"columnId\":30,\"value\":50},{\"columnId\":31,\"value\":70}]},"
            + "{\"sizeLabel\":\"M\",\"cells\":[{\"columnId\":30,\"value\":53},{\"columnId\":31,\"value\":72}]}],\"warnings\":[]}");
        SizeChartDraft ready = refresh(requested);

        facade.apply(ready.id(), ready.version(), chart(row("S", 0, "50", "70"), row("M", 1, "53", "72")), Set.of(), ACTOR);

        assertThat(drafts.findById(ready.id()).orElseThrow().status()).isEqualTo(SizeChartDraftStatus.APPLIED);
        assertThat(productChart()).containsExactly(Map.entry("S", List.of("50.00", "70.00")), Map.entry("M", List.of("53.00", "72.00")));
    }

    @Test
    void approvedImageBecomesADraftAndUncertainCellsMustBeConfirmedBeforeTheChartChanges() {
        when(imageSources.approve(eq(PRODUCT), any(Product.class), anyList(), anyList()))
            .thenReturn(List.of(new SizeChartDraftImage("products/media/size-chart.png", HASH, "image/png", 1024)));
        var created = facade.create(new SizeChartDraftCreateCommand(PRODUCT, TEMPLATE, ACTOR, UUID.randomUUID(), "IMAGE", null,
            List.of(new SizeChartDraftCreateCommand.Image("IMG-1", HASH)), true));
        SizeChartDraft requested = drafts.findById(created.draftId()).orElseThrow();
        assertThat(requested.status()).isEqualTo(SizeChartDraftStatus.REQUESTED);

        // operations 가 읽는 생성 입력 — IMAGE 메타데이터는 있고 스토리지 키는 없다
        var input = inputs.generationInput(requested.id());
        assertThat(input.mode()).isEqualTo("IMAGE");
        assertThat(input.images()).singleElement().satisfies(image -> {
            assertThat(image.sha256()).isEqualTo(HASH);
            assertThat(image.contentType()).isEqualTo("image/png");
        });
        assertThat(input.sourceJson()).contains(HASH).doesNotContain("products/media");

        // AI 가 S 의 총장을 확실히 읽지 못했고(UNCERTAIN_CELL), M 의 총장은 읽지 못했다(null + MISSING_CELL)
        succeed(requested, "{\"unit\":\"CM\",\"rows\":["
            + "{\"sizeLabel\":\"S\",\"cells\":[{\"columnId\":30,\"value\":50},{\"columnId\":31,\"value\":71}]},"
            + "{\"sizeLabel\":\"M\",\"cells\":[{\"columnId\":30,\"value\":53},{\"columnId\":31,\"value\":null}]}],"
            + "\"warnings\":[{\"code\":\"UNCERTAIN_CELL\",\"rowIndex\":0,\"columnId\":31},{\"code\":\"MISSING_CELL\",\"rowIndex\":1,\"columnId\":31}]}");
        SizeChartDraft ready = refresh(requested);
        assertThat(ready.candidate().json()).contains("UNCERTAIN_CELL", "MISSING_CELL");

        // 운영자가 원본과 대조해 값을 고치고 빈칸을 채웠어도 UNCERTAIN_CELL 확인 없이는 적용되지 않는다
        var corrected = chart(row("S", 0, "50", "70"), row("M", 1, "53", "72"));
        assertThatThrownBy(() -> facade.apply(ready.id(), ready.version(), corrected, Set.of(), ACTOR))
            .isInstanceOfSatisfying(SizeChartDraftTransportException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.WARNING_ACK_REQUIRED));
        assertThat(charts.findByProductId(PRODUCT)).isEmpty();

        facade.apply(ready.id(), ready.version(), corrected, Set.of("UNCERTAIN_CELL:0:" + LENGTH), ACTOR);

        assertThat(drafts.findById(ready.id()).orElseThrow().status()).isEqualTo(SizeChartDraftStatus.APPLIED);
        assertThat(productChart()).containsExactly(Map.entry("S", List.of("50.00", "70.00")), Map.entry("M", List.of("53.00", "72.00")));
    }

    private void succeed(SizeChartDraft draft, String resultJson) {
        when(generation.result(any())).thenReturn(new SizeChartGenerationResultQueryResult(draft.id(), draft.jobId(), draft.input().inputHash(),
            SizeChartGenerationStatus.SUCCEEDED, null, resultJson));
    }

    private SizeChartDraft refresh(SizeChartDraft requested) {
        facade.refresh(requested.id(), ACTOR);
        SizeChartDraft ready = drafts.findById(requested.id()).orElseThrow();
        assertThat(ready.status()).isEqualTo(SizeChartDraftStatus.READY);
        return ready;
    }

    private static ProductSizeChartUpsertCommand chart(ProductSizeChartUpsertCommand.Row... rows) {
        return new ProductSizeChartUpsertCommand(TEMPLATE, null, List.of(rows));
    }

    private static ProductSizeChartUpsertCommand.Row row(String label, int order, String chest, String length) {
        return new ProductSizeChartUpsertCommand.Row(label, order, List.of(
            new ProductSizeChartUpsertCommand.Cell(CHEST, new BigDecimal(chest)), new ProductSizeChartUpsertCommand.Cell(LENGTH, new BigDecimal(length))));
    }

    /** 상품 사이즈표를 행 순서대로 {사이즈 → [가슴, 총장]} 으로 읽는다. */
    private List<Map.Entry<String, List<String>>> productChart() {
        var chart = charts.findByProductId(PRODUCT).orElseThrow();
        List<ProductSizeRow> stored = charts.findRowsByChartId(chart.id());
        var values = charts.findCellsByRowIds(stored.stream().map(ProductSizeRow::id).toList());
        return stored.stream().map(row -> Map.entry(row.sizeLabel(), List.of(CHEST, LENGTH).stream()
            .map(column -> values.stream().filter(cell -> cell.rowId() == row.id() && cell.columnId() == column).findFirst().orElseThrow()
                .cellValue().setScale(2).toPlainString()).toList())).toList();
    }

    private <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(ignored -> work.get());
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
