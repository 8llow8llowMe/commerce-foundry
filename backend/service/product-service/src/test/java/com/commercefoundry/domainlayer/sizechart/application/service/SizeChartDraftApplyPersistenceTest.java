package com.commercefoundry.domainlayer.sizechart.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftApplyCommand;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.port.out.ProductSizeChartRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartDraftRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartGenerationPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartTemplateRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.ProductSizeChartProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftApplyProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftRemoteProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftValidationProcessor;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeCell;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeChart;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeRow;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplate;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplateColumn;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** H2 verifies real deletes/inserts, revisions, uniqueness and rollback through the transactional apply proxy. */
@DataJpaTest(properties = "spring.datasource.url=jdbc:h2:mem:size-chart-apply;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({QuerydslConfigurer.class, ProductServiceFeaturesConfig.class, ProductSizeChartRepositoryAdapter.class,
    com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor.class,
    com.commercefoundry.domainlayer.sizechart.adapter.in.admin.presenter.SizeChartDraftAdminPresenter.class,
    SizeChartTemplateRepositoryAdapter.class, SizeChartDraftRepositoryAdapter.class, ProductSizeChartProcessor.class,
    SizeChartDraftApplyProcessor.class, SizeChartDraftValidationProcessor.class, SizeChartDraftRemoteProcessor.class, SizeChartDraftFacade.class,
    com.commercefoundry.global.config.SizeChartDraftProperties.class, SizeChartDraftApplyPersistenceTest.TimeConfig.class})
class SizeChartDraftApplyPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private static final String SOURCE = "{\"text\":\"private source never belongs in receipt\"}";
    private static final String TEMPLATE = "{\"unit\":\"CM\",\"columns\":[{\"id\":30,\"name\":\"Chest\"}]}";
    private static final String CANDIDATE = "{\"unit\":\"CM\",\"rows\":[{\"sizeLabel\":\"S\",\"cells\":[{\"columnId\":30,\"value\":50}]}],\"warnings\":[]}";

    @Autowired private SizeChartDraftApplyProcessor apply;
    @Autowired private SizeChartDraftFacade facade;
    @Autowired private ProductSizeChartProcessor chartProcessor;
    @Autowired private ProductSizeChartRepositoryPort charts;
    @Autowired private SizeChartTemplateRepositoryPort templates;
    @Autowired private SizeChartDraftRepositoryPort drafts;
    @Autowired private SizeChartDraftValidationProcessor validation;
    @Autowired private com.commercefoundry.domainlayer.sizechart.adapter.in.admin.presenter.SizeChartDraftAdminPresenter presenter;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ProductSizeChartRepository chartRepository;
    @Autowired private ProductSizeCellRepository cells;
    @Autowired private SizeChartDraftRepository draftRepository;
    @Autowired private SizeChartTemplateColumnRepository columns;
    @Autowired private SizeChartTemplateRepository templateRepository;
    @Autowired private ProductSizeRowRepository rows;
    @MockitoSpyBean private SizeChartDraftRepositoryAdapter draftAdapter;
    @MockitoBean private ProductLookupProcessor products;
    @MockitoBean private SnowflakeIdGenerator ids;
    @MockitoBean private Clock clock;
    @MockitoBean private SizeChartDraftProcessor draftProcessor;
    @MockitoBean private SizeChartGenerationPort generation;
    @MockitoBean private com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartImageSourceProcessor imageSources;
    @MockitoBean private com.commercefoundry.domainlayer.product.application.service.processor.ProductImageLookupProcessor productImages;
    private final AtomicLong sequence = new AtomicLong(1000);

    @BeforeEach
    void setup() {
        tx(() -> {
            cells.deleteAllInBatch(); rows.deleteAllInBatch(); chartRepository.deleteAllInBatch(); draftRepository.deleteAllInBatch();
            columns.deleteAllInBatch(); templateRepository.deleteAllInBatch();
            return null;
        });
        when(clock.instant()).thenReturn(NOW);
        when(ids.generateId()).thenAnswer(i -> sequence.incrementAndGet());
        when(products.findAllIncludingHidden(List.of(10L))).thenReturn(Map.of(10L, mock(Product.class)));
        tx(() -> {
            templates.insert(new SizeChartTemplate(20, "Top", SizeChartUnit.CM),
                List.of(new SizeChartTemplateColumn(30, 20, "Chest", null, 0)));
            return null;
        });
    }

    @Test
    void applyAtomicallyStoresReviewedChartAndReceiptThenReplaysOnlyBeforeExpiry() {
        var ready = ready(false, CANDIDATE);
        var corrected = command("51.0", "private operator note");
        var original = applyWithProducts(request(ready, corrected, Set.of()));
        var stored = drafts.findById(ready.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(SizeChartDraftStatus.APPLIED);
        assertThat(stored.version()).isEqualTo(ready.version() + 1);
        assertThat(stored.candidate().json()).contains("51.00", "\"sizeLabel\":\"S\"", "\"warnings\":[]")
            .doesNotContain("private source", "private operator note");
        assertThat(presenter.detail(com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftInfo.from(stored))
            .candidate().rows().getFirst().cells().getFirst().value())
            .isEqualByComparingTo("51.00");
        assertThat(stored.applyReceipt().receiptJson()).doesNotContain("private", "source", "note", "Chest", "sizeLabel");
        assertThat(stored.applyReceipt().toString()).doesNotContain("private");
        assertThat(original.chartRevision()).isZero();
        manual("60");
        when(clock.instant()).thenReturn(ready.expiresAt().minusNanos(1));
        reset(products);
        when(draftProcessor.get(ready.id())).thenAnswer(ignored -> drafts.findById(ready.id()).orElseThrow());
        assertThat(presenter.detail(facade.get(ready.id())).candidate().rows().getFirst().cells().getFirst().value())
            .isEqualByComparingTo("51.00");
        assertThat(presenter.detail(facade.refresh(ready.id(), 40)).candidate().warnings()).isEmpty();
        var replay = facade.apply(ready.id(), -1, command("51.00", " private operator note "), Set.of(), 999);
        assertThat(replay).isEqualTo(original);
        assertThat(charts.findByProductId(10).orElseThrow().revision()).isEqualTo(1);
        assertThat(value()).isEqualByComparingTo("60");
        verifyNoInteractions(products, generation);
        when(clock.instant()).thenReturn(ready.expiresAt());
        rejects(() -> applyWithProducts(request(ready, corrected, Set.of())), SizeChartDraftTransportErrorCode.EXPIRED);
        rejects(() -> apply.replayApplied(request(ready, corrected, Set.of())), SizeChartDraftTransportErrorCode.EXPIRED);
        assertThat(value()).isEqualByComparingTo("60");
        assertThat(drafts.findById(ready.id())).contains(stored);
    }

    @Test
    void changedContentOrAcknowledgmentReplayConflicts() {
        var candidate = CANDIDATE.replace("50}", "400}").replace("\"warnings\":[]", "\"warnings\":[{\"code\":\"MEASUREMENT_OUTLIER\",\"rowIndex\":0,\"columnId\":30}]");
        var ready = ready(false, candidate);
        var ack = Set.of("MEASUREMENT_OUTLIER:0:30");
        applyWithProducts(request(ready, command("400", null), ack));
        rejects(() -> applyWithProducts(request(ready, command("401", null), ack)), SizeChartDraftTransportErrorCode.APPLY_CONFLICT);
        rejects(() -> applyWithProducts(request(ready, command("400", null), Set.of())), SizeChartDraftTransportErrorCode.APPLY_CONFLICT);
    }

    @Test
    void staleCellOnlyManualEditIsDetectedBeforeChildrenAreDeleted() {
        manual("50");
        var ready = ready(true, CANDIDATE);
        manual("52");
        var beforeRows = charts.findRowsByChartId(50);
        rejects(() -> applyWithProducts(request(ready, command("53", null), Set.of())), SizeChartDraftTransportErrorCode.CHART_CHANGED);
        assertThat(charts.findRowsByChartId(50)).isEqualTo(beforeRows);
        assertThat(value()).isEqualByComparingTo("52");
        assertReady(ready);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void staleHeaderOrColumnOnlyTemplateEditConflicts(boolean columnOnly) {
        var ready = ready(false, CANDIDATE);
        tx(() -> {
            var old = templates.lockById(20).orElseThrow();
            templates.updateColumns(new SizeChartTemplate(20, columnOnly ? old.name() : "Changed", old.baseUnit()),
                List.of(new SizeChartTemplateColumn(30, 20, columnOnly ? "Length" : "Chest", null, 0)));
            return null;
        });
        rejects(() -> applyWithProducts(request(ready, command("51", null), Set.of())), SizeChartDraftTransportErrorCode.TEMPLATE_CHANGED);
        assertThat(charts.findByProductId(10)).isEmpty();
        assertReady(ready);
    }

    @Test
    void capturedColumnIdsMustMatchEvenIfRevisionWasNotAdvanced() {
        var ready = ready(false, CANDIDATE);
        tx(() -> {
            columns.deleteAllInBatch();
            columns.saveAndFlush(com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity.SizeChartTemplateColumnEntity.builder()
                .id(31L).templateId(20L).name("Chest").displayOrder(0).build());
            return null;
        });
        rejects(() -> applyWithProducts(request(ready, command("51", null), Set.of())), SizeChartDraftTransportErrorCode.TEMPLATE_CHANGED);
        assertReady(ready);
    }

    @Test
    void chartCreatedAfterCapturedAbsenceCannotBeOverwritten() {
        var ready = ready(false, CANDIDATE);
        manual("55");
        rejects(() -> applyWithProducts(request(ready, command("51", null), Set.of())), SizeChartDraftTransportErrorCode.CHART_CHANGED);
        assertThat(value()).isEqualByComparingTo("55");
        assertReady(ready);
    }

    @Test
    void concurrentAbsentInsertAcrossDifferentTemplateLocksKeepsOnlyOneChart() throws Exception {
        tx(() -> {
            templates.insert(new SizeChartTemplate(21, "Other", SizeChartUnit.CM),
                List.of(new SizeChartTemplateColumn(31, 21, "Length", null, 0)));
            return null;
        });
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> insertRacer(20, 501, 601, barrier));
            var two = executor.submit(() -> insertRacer(21, 502, 602, barrier));
            assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("saved", "unique-conflict");
        }
        assertThat(chartRepository.count()).isEqualTo(1);
        assertThat(rows.count()).isEqualTo(1);
    }

    @Test
    void missingWarningAckAndForgedWarningIdsFailButCorrectingGeneratedOutlierNeedsNoAck() {
        var candidate = CANDIDATE.replace("50}", "400}").replace("\"warnings\":[]", "\"warnings\":[{\"code\":\"MEASUREMENT_OUTLIER\",\"rowIndex\":0,\"columnId\":30}]");
        var ready = ready(false, candidate);
        rejects(() -> applyWithProducts(request(ready, command("400", null), Set.of())), SizeChartDraftTransportErrorCode.WARNING_ACK_REQUIRED);
        rejects(() -> applyWithProducts(request(ready, command("400", null), Set.of("provider says okay"))), SizeChartDraftTransportErrorCode.WARNING_ACK_REQUIRED);
        assertThat(charts.findByProductId(10)).isEmpty();
        applyWithProducts(request(ready, command("50", null), Set.of()));
        assertThat(value()).isEqualByComparingTo("50");
    }

    @Test
    void newOperatorOutlierFollowsManualBoundsAndDoesNotInventUndiscoverableWarningIds() {
        var ready = ready(false, CANDIDATE);
        // Explicit policy: acknowledgments apply only to stored generated warnings exposed by GET.
        // Corrected operator input has the same limits as #216 manual PUT, which permits 400.
        applyWithProducts(request(ready, command("400", null), Set.of()));
        assertThat(value()).isEqualByComparingTo("400");
    }

    @Test
    void incompleteOrNullCellsCannotBeAcknowledgedIntoExistence() {
        var candidate = CANDIDATE.replace("50}", "null}").replace("\"warnings\":[]", "\"warnings\":[{\"code\":\"MISSING_CELL\",\"rowIndex\":0,\"columnId\":30}]");
        var ready = ready(false, candidate);
        var empty = new ProductSizeChartUpsertCommand(20, null, List.of(new ProductSizeChartUpsertCommand.Row("S", 0, List.of())));
        assertThatThrownBy(() -> applyWithProducts(request(ready, empty, Set.of("MISSING_CELL:0:30"))))
            .isInstanceOfSatisfying(SizeChartException.class, e -> assertThat(e.getErrorCode()).isEqualTo(SizeChartErrorCode.CELL_COLUMN_MISSING));
        rejects(() -> applyWithProducts(request(ready, command(null, null), Set.of("MISSING_CELL:0:30"))), SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        assertReady(ready);
        applyWithProducts(request(ready, command("50", null), Set.of()));
        assertThat(value()).isEqualByComparingTo("50");
    }

    @Test
    void failedReceiptSaveRollsBackFlushedChartHeaderRowsCellsAndDraft() {
        manual("50");
        var ready = ready(true, CANDIDATE);
        var before = charts.findRowsByChartId(50);
        tx(() -> {
            doThrow(new IllegalStateException("simulated receipt failure")).when(draftAdapter).update(any(), any());
            return null;
        });
        assertThatThrownBy(() -> applyWithProducts(request(ready, command("51", "reviewed"), Set.of())))
            .isInstanceOf(IllegalStateException.class).hasMessage("simulated receipt failure");
        assertThat(charts.findByProductId(10).orElseThrow().revision()).isZero();
        assertThat(charts.findRowsByChartId(50)).isEqualTo(before);
        assertThat(value()).isEqualByComparingTo("50");
        assertReady(ready);
    }

    @Test
    void matchingVersionAndUnexpiredReadyAreRequired() {
        var ready = ready(false, CANDIDATE);
        rejects(() -> applyWithProducts(new SizeChartDraftApplyCommand(ready.id(), ready.version() - 1, command("51", null), Set.of(), 40)),
            SizeChartDraftTransportErrorCode.BINDING_CONFLICT);
        when(clock.instant()).thenReturn(ready.expiresAt());
        rejects(() -> applyWithProducts(request(ready, command("51", null), Set.of())), SizeChartDraftTransportErrorCode.EXPIRED);
        assertReady(ready);
    }

    @Test
    void applyExistingChartBumpsRevisionAndIdenticalConcurrentApplyReplaysOriginalReceipt() throws Exception {
        manual("50");
        var ready = ready(true, CANDIDATE);
        when(draftProcessor.get(ready.id())).thenAnswer(i -> drafts.findById(ready.id()).orElseThrow());
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return facade.apply(ready.id(), ready.version(), command("51", null), Set.of(), 40);
            });
            var two = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return facade.apply(ready.id(), ready.version(), command("51.00", null), Set.of(), 40);
            });
            var receipt = one.get(20, TimeUnit.SECONDS);
            assertThat(two.get(20, TimeUnit.SECONDS)).isEqualTo(receipt);
            assertThat(receipt.chartRevision()).isEqualTo(1);
        }
        assertThat(charts.findByProductId(10).orElseThrow().revision()).isEqualTo(1);
        assertThat(value()).isEqualByComparingTo("51");
    }

    @Test
    void generatedMeasurementAndSizeOrderWarningsMustBeAcknowledgedWhileUnresolved() {
        String candidate = """
            {"unit":"CM","rows":[
              {"sizeLabel":"M","cells":[{"columnId":30,"value":60}]},
              {"sizeLabel":"S","cells":[{"columnId":30,"value":50}]}],
              "warnings":[{"code":"SIZE_ORDER","rowIndex":1,"columnId":0},
                          {"code":"MEASUREMENT_ORDER","rowIndex":1,"columnId":30}]}
            """;
        var ready = ready(false, candidate);
        var corrected = new ProductSizeChartUpsertCommand(20, null, List.of(
            new ProductSizeChartUpsertCommand.Row("M", 0, List.of(new ProductSizeChartUpsertCommand.Cell(30, new BigDecimal("60")))),
            new ProductSizeChartUpsertCommand.Row("S", 1, List.of(new ProductSizeChartUpsertCommand.Cell(30, new BigDecimal("50"))))));
        rejects(() -> applyWithProducts(request(ready, corrected, Set.of("SIZE_ORDER:1:0"))), SizeChartDraftTransportErrorCode.WARNING_ACK_REQUIRED);
        applyWithProducts(request(ready, corrected, Set.of("SIZE_ORDER:1:0", "MEASUREMENT_ORDER:1:30")));
        assertThat(drafts.findById(ready.id()).orElseThrow().status()).isEqualTo(SizeChartDraftStatus.APPLIED);
    }

    @Test
    void newOperatorOrderAnomaliesAlsoFollowManualPutPolicy() {
        var ready = ready(false, CANDIDATE);
        var corrected = new ProductSizeChartUpsertCommand(20, null, List.of(
            new ProductSizeChartUpsertCommand.Row("M", 0, List.of(new ProductSizeChartUpsertCommand.Cell(30, new BigDecimal("60")))),
            new ProductSizeChartUpsertCommand.Row("S", 1, List.of(new ProductSizeChartUpsertCommand.Cell(30, new BigDecimal("50"))))));
        applyWithProducts(request(ready, corrected, Set.of()));
        assertThat(charts.findRowsByChartId(charts.findByProductId(10).orElseThrow().id())).extracting(ProductSizeRow::sizeLabel)
            .containsExactly("M", "S");
    }

    @Test
    void duplicateLabelsAndOrdersReuse216ValidationAndBadNumericBoundsFailBeforeMutation() {
        var ready = ready(false, CANDIDATE);
        var row = command("50", null).rows().getFirst();
        var duplicate = new ProductSizeChartUpsertCommand(20, null, List.of(row, row));
        assertThatThrownBy(() -> applyWithProducts(request(ready, duplicate, Set.of())))
            .isInstanceOfSatisfying(SizeChartException.class, e -> assertThat(e.getErrorCode()).isEqualTo(SizeChartErrorCode.ROW_LABEL_DUPLICATED));
        for (String bad : List.of("0", "-1", "10000", "1.001")) {
            rejects(() -> applyWithProducts(request(ready, command(bad, null), Set.of())), SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        }
        assertThat(charts.findByProductId(10)).isEmpty();
        assertReady(ready);
    }

    private String insertRacer(long templateId, long chartId, long rowId, CyclicBarrier barrier) {
        try {
            tx(() -> {
                templates.lockById(templateId).orElseThrow();
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError("concurrent chart insert barrier failed", exception);
                }
                return charts.replaceIfUnchanged(new ProductSizeChart(chartId, 10, templateId, null),
                    List.of(new ProductSizeRow(rowId, chartId, "S", 0)), List.of(), null, null);
            });
            return "saved";
        } catch (SizeChartDraftTransportException exception) {
            assertThat(exception.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.CHART_CHANGED);
            return "unique-conflict";
        } catch (SizeChartException e) {
            assertThat(e.getErrorCode()).isEqualTo(SizeChartErrorCode.CHART_CONFLICT);
            return "unique-conflict";
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Shared ConstraintViolations deliberately does not normalize H2's generated index suffix.
            // Real H2 proves uniqueness/rollback; the exact production constraint-to-409 mapping is tested separately.
            return "unique-conflict";
        }
    }

    @Test
    void recognizedProductUniqueConstraintMapsTo409() {
        var chartRepo = mock(ProductSizeChartRepository.class);
        var rowRepo = mock(ProductSizeRowRepository.class);
        var cellRepo = mock(ProductSizeCellRepository.class);
        when(chartRepo.findByProductIdForUpdate(10)).thenReturn(java.util.Optional.empty());
        var cause = new org.hibernate.exception.ConstraintViolationException("unique", new java.sql.SQLException("unique"),
            "uk_product_size_chart_product_id");
        doThrow(new org.springframework.dao.DataIntegrityViolationException("unique", cause)).when(chartRepo).flush();
        var adapter = new ProductSizeChartRepositoryAdapter(chartRepo, rowRepo, cellRepo, mock(jakarta.persistence.EntityManager.class));
        assertThatThrownBy(() -> adapter.replaceIfUnchanged(new ProductSizeChart(50, 10, 20, null), List.of(), List.of(), null, null))
            .isInstanceOfSatisfying(SizeChartException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(SizeChartErrorCode.CHART_CONFLICT);
                assertThat(e.getErrorCode().getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
            });
    }

    private com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftApplyInfo applyWithProducts(SizeChartDraftApplyCommand command) {
        return apply.apply(command, Map.of(10L, mock(Product.class)));
    }

    private void manual(String value) {
        tx(() -> {
            templates.lockById(20).orElseThrow();
            if (charts.findByProductId(10).isEmpty()) {
                long rowId = sequence.incrementAndGet();
                charts.replace(new ProductSizeChart(50, 10, 20, null), List.of(new ProductSizeRow(rowId, 50, "S", 0)),
                    List.of(new ProductSizeCell(sequence.incrementAndGet(), rowId, 30, new BigDecimal(value))));
                return null;
            }
            return chartProcessor.replace(10, Map.of(10L, mock(Product.class)), command(value, null));
        });
    }

    private SizeChartDraft ready(boolean existing, String candidate) {
        return tx(() -> {
            var chart = existing ? charts.findByProductId(10).orElseThrow() : null;
            var input = new SizeChartDraft.Input(10, 20, templates.findById(20).orElseThrow().revision(), 40, UUID.randomUUID(),
                "a".repeat(64), "b".repeat(64), SOURCE, TEMPLATE, chart == null ? null : chart.id(), chart == null ? null : chart.revision());
            var requested = drafts.insert(SizeChartDraft.request(sequence.incrementAndGet(), input, NOW.plusSeconds(604800)));
            var bound = drafts.update(requested.bindJob(200, NOW), NOW);
            return drafts.update(bound.ready(validation.candidate(candidate, TEMPLATE), NOW), NOW);
        });
    }

    private ProductSizeChartUpsertCommand command(String value, String note) {
        return new ProductSizeChartUpsertCommand(20, note, List.of(new ProductSizeChartUpsertCommand.Row("S", 0,
            List.of(new ProductSizeChartUpsertCommand.Cell(30, value == null ? null : new BigDecimal(value))))));
    }

    private SizeChartDraftApplyCommand request(SizeChartDraft draft, ProductSizeChartUpsertCommand chart, Set<String> ack) {
        return new SizeChartDraftApplyCommand(draft.id(), draft.version(), chart, ack, 40);
    }

    private void assertReady(SizeChartDraft ready) {
        assertThat(drafts.findById(ready.id()).orElseThrow()).isEqualTo(ready);
    }

    private BigDecimal value() {
        var chart = charts.findByProductId(10).orElseThrow();
        return charts.findCellsByRowIds(charts.findRowsByChartId(chart.id()).stream().map(ProductSizeRow::id).toList()).getFirst().cellValue();
    }

    private <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(ignored -> work.get());
    }

    private void rejects(Runnable work, SizeChartDraftTransportErrorCode code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(SizeChartDraftTransportException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
