package com.commercefoundry.domainlayer.sizechart.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductImageLookupProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductLookupProcessor;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftBatchResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.presenter.SizeChartDraftAdminPresenter;
import com.commercefoundry.domainlayer.sizechart.application.command.ProductSizeChartUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftApplyCommand;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartException;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftApplyInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftBatchLimits;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftImage;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftUnavailableException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationRegistration;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationResult;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationStatus;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartGenerationPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartGenerationRegistrationQueryResult;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartGenerationResultQueryResult;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftApplyProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftBatchProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftRemoteProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftValidationProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartImageSourceProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartTextSourceProcessor;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import com.commercefoundry.global.config.SizeChartDraftProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class SizeChartDraftFacadeTest {
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private final SizeChartDraftApplyProcessor applyProcessor = mock(SizeChartDraftApplyProcessor.class);
    private final SizeChartDraftProcessor processor = mock(SizeChartDraftProcessor.class);
    private final SizeChartGenerationPort generation = mock(SizeChartGenerationPort.class);
    private final SizeChartDraftValidationProcessor validation = new SizeChartDraftValidationProcessor();
    private final ProductLookupProcessor products = mock(ProductLookupProcessor.class);
    private final SizeChartImageSourceProcessor imageSources = mock(SizeChartImageSourceProcessor.class);
    private final ProductImageLookupProcessor productImages = mock(ProductImageLookupProcessor.class);
    private final SizeChartDraftProperties properties = new SizeChartDraftProperties();
    private final SizeChartDraftFacade facade = new SizeChartDraftFacade(
        applyProcessor, processor, validation, new SizeChartDraftRemoteProcessor(generation), products, Clock.fixed(NOW, ZoneOffset.UTC),
        new ProductAdminAuditProcessor(),
        new SizeChartDraftAdminPresenter(), imageSources, productImages, properties, new SizeChartTextSourceProcessor(),
        new SizeChartDraftBatchProcessor());
    private final SizeChartDraftCreateCommand command = new SizeChartDraftCreateCommand(10, 20, 40, UUID.randomUUID(), "TEXT", "S 50 cm");
    private final SizeChartDraftCreateCommand imageCommand = new SizeChartDraftCreateCommand(10, 20, 40, UUID.randomUUID(), "IMAGE", null,
        List.of(new SizeChartDraftCreateCommand.Image("IMG-4611686018427387905", "c".repeat(64))), true);

    @Test
    void imageInputIsRejectedWhileFlagIsClosedWithoutReadingStorage() {
        assertThatThrownBy(() -> facade.create(imageCommand)).isInstanceOfSatisfying(SizeChartDraftTransportException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.IMAGE_INPUT_DISABLED));
        assertThatThrownBy(() -> facade.imageSourcesAdmin(10)).isInstanceOfSatisfying(SizeChartDraftTransportException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.IMAGE_INPUT_DISABLED));
        assertThatThrownBy(() -> facade.inspectImageAdmin(10, "IMG-1")).isInstanceOfSatisfying(SizeChartDraftTransportException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.IMAGE_INPUT_DISABLED));
        verifyNoInteractions(imageSources, productImages, generation);
        verify(processor, never()).replay(any(), any());
    }

    @Test
    void imageInputRevalidatesBytesOutsideTransactionThenStoresApprovedSnapshot() {
        properties.setImageEnabled(true);
        var product = org.mockito.Mockito.mock(Product.class);
        when(products.findAllIncludingHidden(List.of(10L))).thenReturn(java.util.Map.of(10L, product));
        when(productImages.findStillImagesIncludingHidden(10)).thenReturn(List.of());
        var approved = List.of(new SizeChartDraftImage("products/10/4611686018427387905.png", "c".repeat(64), "image/png", 12));
        when(imageSources.approve(10, product, List.of(), imageCommand.images())).thenReturn(approved);
        when(processor.replay(any(), any())).thenReturn(Optional.empty());
        when(processor.createOrReplay(any(), any(), any(), any())).thenReturn(draft().bindJob(200, NOW));
        assertThat(facade.create(imageCommand).jobId()).isEqualTo(200);
        var order = inOrder(processor, imageSources);
        order.verify(processor).replay(any(), any());
        order.verify(imageSources).approve(10, product, List.of(), imageCommand.images());
        order.verify(processor).createOrReplay(org.mockito.ArgumentMatchers.eq(imageCommand), any(), any(), org.mockito.ArgumentMatchers.eq(approved));
    }

    @Test
    void imageApprovalMismatchCreatesNoDraft() {
        properties.setImageEnabled(true);
        var product = org.mockito.Mockito.mock(Product.class);
        when(products.findAllIncludingHidden(List.of(10L))).thenReturn(java.util.Map.of(10L, product));
        when(processor.replay(any(), any())).thenReturn(Optional.empty());
        when(imageSources.approve(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any()))
            .thenThrow(new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.IMAGE_APPROVAL_MISMATCH));
        assertThatThrownBy(() -> facade.create(imageCommand)).isInstanceOfSatisfying(SizeChartDraftTransportException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.IMAGE_APPROVAL_MISMATCH));
        verify(processor, never()).createOrReplay(any(), any(), any(), any());
        verifyNoInteractions(generation);
    }

    @Test
    void imageReplayReturnsExistingDraftWithoutRereadingStorage() {
        properties.setImageEnabled(true);
        when(processor.replay(any(), any())).thenReturn(Optional.of(draft().bindJob(200, NOW)));
        assertThat(facade.create(imageCommand).jobId()).isEqualTo(200);
        verifyNoInteractions(imageSources, generation);
    }

    @Test
    void unknownRegistrationResponseKeepsRecoveryIdAndSameStoredDraftIsRetried() {
        var requested = draft();
        when(processor.createOrReplay(any(), any(), any(), any())).thenReturn(requested);
        when(generation.register(requested)).thenThrow(new IllegalStateException("private provider body"));
        assertThatThrownBy(() -> facade.create(command)).isInstanceOfSatisfying(SizeChartDraftUnavailableException.class, e -> {
            assertThat(e.getDraftId()).isEqualTo(requested.id());
            assertThat(e.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.REGISTRATION_UNAVAILABLE);
            assertThat(e.getCause()).isNull();
            assertThat(e.getMessage()).doesNotContain("private");
        });
        verify(processor, never()).bind(any(), any());
        var receipt = registration();
        doReturn(receipt).when(generation).register(requested);
        when(processor.bind(requested, registrationValue(receipt))).thenReturn(requested.bindJob(200, NOW));
        assertThat(facade.create(command).jobId()).isEqualTo(200);
        verify(generation, times(2)).register(requested);
    }

    @Test
    void uniqueInsertFailureReplaysWinnerAfterFailedCallHasExited() {
        var bound = draft().bindJob(200, NOW);
        when(processor.createOrReplay(any(), any(), any(), any())).thenThrow(new DataIntegrityViolationException("unique"));
        when(processor.replay(any(), any())).thenReturn(Optional.empty(), Optional.of(bound));
        assertThat(facade.create(command).draftId()).isEqualTo(bound.id());
        var order = inOrder(processor);
        order.verify(processor).replay(any(), any());
        order.verify(processor).createOrReplay(any(), any(), any(), any());
        order.verify(processor).replay(any(), any());
        verifyNoInteractions(generation);
    }

    @Test
    void malformedRegistrationCannotBindAndReturnsRecoverable503() {
        when(processor.createOrReplay(any(), any(), any(), any())).thenReturn(draft());
        when(generation.register(any())).thenReturn(new SizeChartGenerationRegistrationQueryResult(999, 200, "a".repeat(64)));
        assertThatThrownBy(() -> facade.create(command)).isInstanceOf(SizeChartDraftUnavailableException.class);
        verify(processor, never()).bind(any(), any());
    }

    @Test
    void localGetAndListNeverRegisterFetchOrMutate() {
        when(processor.get(100)).thenReturn(draft());
        when(processor.list(null, 40L, 0, 20)).thenReturn(Page.empty());
        assertThat(facade.get(100).status()).isEqualTo(SizeChartDraftStatus.REQUESTED);
        assertThat(facade.list(null, 40L, 0, 20)).isEmpty();
        verifyNoInteractions(generation);
        verify(processor, never()).bind(any(), any());
        verify(processor, never()).complete(any(), any(), any());
    }

    @Test
    void explicitRefreshReregistersUnboundThenFetchesResult() {
        var requested = draft();
        var bound = requested.bindJob(200, NOW);
        var receipt = registration();
        var result = new SizeChartGenerationResultQueryResult(100, 200, "a".repeat(64), SizeChartGenerationStatus.PENDING, null, null);
        when(processor.get(100)).thenReturn(requested);
        when(generation.register(requested)).thenReturn(receipt);
        when(processor.bind(requested, registrationValue(receipt))).thenReturn(bound);
        when(generation.result(bound)).thenReturn(result);
        when(processor.complete(bound, resultValue(result), null)).thenReturn(bound);
        assertThat(facade.refresh(100, 40).status()).isEqualTo(SizeChartDraftStatus.REQUESTED);
        var order = inOrder(generation, processor);
        order.verify(processor).get(100);
        order.verify(generation).register(requested);
        order.verify(processor).bind(requested, registrationValue(receipt));
        order.verify(generation).result(bound);
        order.verify(processor).verifyResult(bound, resultValue(result));
        order.verify(processor).complete(bound, resultValue(result), null);
    }

    @Test
    void optimisticBindingRaceRereadsWinnerWithoutSecondRemoteRegistration() {
        var requested = draft();
        var receipt = registration();
        when(processor.createOrReplay(any(), any(), any(), any())).thenReturn(requested);
        when(generation.register(requested)).thenReturn(receipt);
        when(processor.bind(requested, registrationValue(receipt))).thenThrow(new ObjectOptimisticLockingFailureException(SizeChartDraft.class, 100L))
            .thenReturn(requested.bindJob(200, NOW));
        assertThat(facade.create(command).jobId()).isEqualTo(200);
        verify(generation).register(requested);
        verify(processor, times(2)).bind(requested, registrationValue(receipt));
    }

    @Test
    void refreshOnTerminalDraftIsLocalAndNeverApplies() {
        var failed = draft().bindJob(200, NOW).fail(NOW);
        when(processor.get(100)).thenReturn(failed);
        assertThat(facade.refresh(100, 40).status()).isEqualTo(SizeChartDraftStatus.FAILED);
        verifyNoInteractions(generation);
    }

    @Test
    void requestedApplyRefreshesBeforeEnteringApplyTransactionAndDoesNotSubstituteExpectedVersion() {
        var requested = draft().bindJob(200, NOW);
        var result = new SizeChartGenerationResultQueryResult(100, 200, "a".repeat(64), SizeChartGenerationStatus.PENDING, null, null);
        when(processor.get(100)).thenReturn(requested);
        when(generation.result(requested)).thenReturn(result);
        when(processor.complete(requested, resultValue(result), null)).thenReturn(requested);
        var chart = new ProductSizeChartUpsertCommand(20, null, List.of(new ProductSizeChartUpsertCommand.Row("S", 0,
            List.of(new ProductSizeChartUpsertCommand.Cell(30, new BigDecimal("50"))))));
        var receipt = new SizeChartDraftApplyInfo(100, 10, 50, 0, 40, NOW);
        when(applyProcessor.apply(any(), any())).thenAnswer(i -> {
            SizeChartDraftApplyCommand apply = i.getArgument(0);
            assertThat(apply.expectedDraftVersion()).isEqualTo(7);
            return receipt;
        });
        assertThat(facade.apply(100, 7, chart, Set.of(), 40)).isEqualTo(receipt);
        var order = inOrder(generation, processor, applyProcessor);
        order.verify(generation).result(requested);
        order.verify(processor).verifyResult(requested, resultValue(result));
        order.verify(processor).complete(requested, resultValue(result), null);
        order.verify(applyProcessor).apply(any(), any());
    }

    @Test
    void batchRejectsDuplicateProductsKeysAndOversizeBeforeAnyLookupOrWrite() {
        var sameProduct = new SizeChartDraftCreateCommand(10, 20, 40, UUID.randomUUID(), "TEXT", "S 50 cm");
        var sameKey = new SizeChartDraftCreateCommand(11, 20, 40, command.idempotencyKey(), "TEXT", "S 50 cm");
        // 대소문자만 다른 UUID 문자열도 같은 키다 — UUID 로 바꾼 뒤 비교한다.
        var caseOnlyKey = new SizeChartDraftCreateCommand(11, 20, 40,
            UUID.fromString(command.idempotencyKey().toString().toUpperCase(java.util.Locale.ROOT)), "TEXT", "S 50 cm");
        var oversized = LongStream.rangeClosed(1, SizeChartDraftBatchLimits.MAX_ITEMS + 1)
            .mapToObj(id -> new SizeChartDraftCreateCommand(id, 20, 40, UUID.randomUUID(), "TEXT", "S 50 cm")).toList();
        assertThat(oversized).hasSize(11);
        for (var commands : List.of(List.of(command, sameProduct), List.of(command, sameKey), List.of(command, caseOnlyKey), oversized,
            List.<SizeChartDraftCreateCommand>of())) {
            assertThatThrownBy(() -> facade.createBatchAdmin(commands)).isInstanceOfSatisfying(SizeChartDraftTransportException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.INVALID_BATCH);
                assertThat(e.getErrorCode().getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
            });
        }
        // 조회 · 초안 저장 · 원격 등록 · 이미지 확인이 한 번도 일어나지 않았다 — 어떤 초안도 만들지 않는다.
        verifyNoInteractions(products, productImages, processor, generation, imageSources);
    }

    @Test
    void bodyChangedDuringBatchIsReReadPerSelectedItemAndIsolatedAsConflictWhileUnchangedItemProceeds() {
        String table = "<table><tr><th>사이즈</th><th>가슴</th></tr><tr><td>S</td><td>50</td></tr></table>";
        var atStart10 = product(10, table);
        var changed10 = product(10, table.replace("50", "51"));
        var current11 = product(11, table);
        var source = new SizeChartTextSourceProcessor().sources(atStart10).getFirst();
        var selection = new SizeChartDraftCreateCommand.TextSelection(source.sourceId(), source.bodyHash());
        var stale = new SizeChartDraftCreateCommand(10, 20, 40, command.idempotencyKey(), "TEXT", source.sourceText(), List.of(), false, selection);
        var fresh = new SizeChartDraftCreateCommand(11, 20, 40, UUID.randomUUID(), "TEXT", source.sourceText(), List.of(), false, selection);
        // 벌크 스냅숏에서는 두 상품 모두 검토한 본문 그대로다. 그 뒤(앞 항목 처리 중) 10번 본문만 바뀌었다.
        when(products.findAllIncludingHidden(List.of(10L, 11L))).thenReturn(java.util.Map.of(10L, atStart10, 11L, current11));
        when(products.findAllIncludingHidden(List.of(10L))).thenReturn(java.util.Map.of(10L, changed10));
        when(products.findAllIncludingHidden(List.of(11L))).thenReturn(java.util.Map.of(11L, current11));
        when(processor.createOrReplay(org.mockito.ArgumentMatchers.eq(fresh), any(), any(), any())).thenReturn(draft().bindJob(200, NOW));
        var results = facade.createBatchAdmin(List.of(stale, fresh)).items();
        assertThat(results).extracting(SizeChartDraftBatchResponse.Item::productId).containsExactly("10", "11");
        assertThat(results.getFirst().errorCode()).isEqualTo(SizeChartDraftTransportErrorCode.TEXT_SELECTION_CHANGED.getCode());
        assertThat(SizeChartDraftTransportErrorCode.TEXT_SELECTION_CHANGED.getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(results.getFirst().retryable()).isFalse();
        assertThat(results.getFirst().draftId()).isNull();
        assertThat(results.get(1).errorCode()).isNull();
        assertThat(results.get(1).jobId()).isEqualTo("200");
        verify(products).findAllIncludingHidden(List.of(10L));
        verify(products).findAllIncludingHidden(List.of(11L));
        verify(processor, never()).createOrReplay(org.mockito.ArgumentMatchers.eq(stale), any(), any(), any());
        verifyNoInteractions(generation);
    }

    @Test
    void sizeChartItemFailuresUseSingleApiCodesAndRetryOnlyConcurrentChartChanges() {
        var busy = new SizeChartDraftCreateCommand(10, 20, 40, UUID.randomUUID(), "TEXT", "S 50 cm");
        var conflict = new SizeChartDraftCreateCommand(11, 20, 40, UUID.randomUUID(), "TEXT", "S 50 cm");
        var noTemplate = new SizeChartDraftCreateCommand(12, 20, 40, UUID.randomUUID(), "TEXT", "S 50 cm");
        var noProduct = new SizeChartDraftCreateCommand(13, 20, 40, UUID.randomUUID(), "TEXT", "S 50 cm");
        when(products.findAllIncludingHidden(List.of(10L, 11L, 12L, 13L))).thenReturn(java.util.Map.of());
        when(processor.createOrReplay(org.mockito.ArgumentMatchers.eq(busy), any(), any(), any()))
            .thenThrow(new SizeChartException(SizeChartErrorCode.SIZE_CHART_BUSY));
        when(processor.createOrReplay(org.mockito.ArgumentMatchers.eq(conflict), any(), any(), any()))
            .thenThrow(new SizeChartException(SizeChartErrorCode.CHART_CONFLICT));
        when(processor.createOrReplay(org.mockito.ArgumentMatchers.eq(noTemplate), any(), any(), any()))
            .thenThrow(new SizeChartException(SizeChartErrorCode.NOT_FOUND_TEMPLATE));
        when(processor.createOrReplay(org.mockito.ArgumentMatchers.eq(noProduct), any(), any(), any()))
            .thenThrow(new SizeChartException(SizeChartErrorCode.PRODUCT_NOT_FOUND));
        var results = facade.createBatchAdmin(List.of(busy, conflict, noTemplate, noProduct)).items();
        assertThat(results).extracting(SizeChartDraftBatchResponse.Item::errorCode)
            .containsExactly("SIZE_CHART_DRAFT_003", "SIZE_CHART_DRAFT_003", "SIZE_CHART_DRAFT_001", "SIZE_CHART_DRAFT_001");
        assertThat(results).extracting(SizeChartDraftBatchResponse.Item::retryable).containsExactly(true, true, false, false);
        verifyNoInteractions(generation);
    }

    @Test
    void unexpectedItemFailureBeforeRemoteCallIsRetryable505AndDoesNotStopOtherItems() {
        var other = new SizeChartDraftCreateCommand(11, 20, 40, UUID.randomUUID(), "TEXT", "S 50 cm");
        when(products.findAllIncludingHidden(List.of(10L, 11L))).thenReturn(java.util.Map.of());
        when(processor.replay(org.mockito.ArgumentMatchers.eq(command), any())).thenThrow(new IllegalStateException("private db diagnostics"));
        when(processor.replay(org.mockito.ArgumentMatchers.eq(other), any())).thenReturn(Optional.of(draft().bindJob(200, NOW)));
        var results = facade.createBatchAdmin(List.of(command, other)).items();
        assertThat(results.getFirst().errorCode()).isEqualTo("SIZE_CHART_DRAFT_505");
        assertThat(results.getFirst().retryable()).isTrue();
        assertThat(results.getFirst().draftId()).isNull();
        assertThat(results.get(1).jobId()).isEqualTo("200");
        assertThat(results.toString()).doesNotContain("private");
        verifyNoInteractions(generation);
    }

    @Test
    void bulkLookupFailureRejectsWholeBatchAs503BeforeAnyItem() {
        var other = new SizeChartDraftCreateCommand(11, 20, 40, UUID.randomUUID(), "TEXT", "S 50 cm");
        when(products.findAllIncludingHidden(List.of(10L, 11L))).thenThrow(new IllegalStateException("private pool diagnostics"));
        assertThatThrownBy(() -> facade.createBatchAdmin(List.of(command, other))).isInstanceOfSatisfying(SizeChartDraftTransportException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.BATCH_ITEM_UNAVAILABLE);
            assertThat(e.getErrorCode().getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getMessage()).doesNotContain("private");
        });
        verifyNoInteractions(processor, generation, productImages, imageSources);
    }

    private static Product product(long id, String html) {
        var product = mock(Product.class);
        when(product.id()).thenReturn(id);
        when(product.descriptionHtml()).thenReturn(html);
        return product;
    }

    @Test
    void batchPreservesSuccessfulItemsAndRecoveryIdThenRetriesSameFailedKeyWithoutNewDraft() {
        var other = new SizeChartDraftCreateCommand(11, 20, 40, UUID.randomUUID(), "TEXT", "S 50 cm");
        var requested = draft();
        when(products.findAllIncludingHidden(List.of(10L, 11L))).thenReturn(java.util.Map.of());
        when(processor.replay(org.mockito.ArgumentMatchers.eq(other), any())).thenReturn(Optional.of(requested.bindJob(200, NOW)));
        when(processor.createOrReplay(org.mockito.ArgumentMatchers.eq(command), any(), any(), any())).thenReturn(requested);
        when(generation.register(requested)).thenThrow(new IllegalStateException("private remote diagnostics"));
        var results = facade.createBatchAdmin(List.of(command, other)).items();
        assertThat(results).hasSize(2);
        assertThat(results.getFirst().productId()).isEqualTo("10");
        assertThat(results.getFirst().idempotencyKey()).isEqualTo(command.idempotencyKey().toString());
        assertThat(results.getFirst().draftId()).isEqualTo("100");
        assertThat(results.getFirst().errorCode()).isEqualTo("SIZE_CHART_DRAFT_501");
        assertThat(results.getFirst().retryable()).isTrue();
        assertThat(results.get(1).jobId()).isEqualTo("200");
        assertThat(results.get(1).errorCode()).isNull();
        assertThat(results.toString()).doesNotContain("private", "S 50 cm");
        when(products.findAllIncludingHidden(List.of(10L))).thenReturn(java.util.Map.of());
        when(processor.replay(org.mockito.ArgumentMatchers.eq(command), any())).thenReturn(Optional.of(requested));
        // 첫 시도의 throw 스텁을 부르지 않도록 doReturn 으로 바꾼다 — 이번에는 operations 등록이 성공한다.
        org.mockito.Mockito.doReturn(registration()).when(generation).register(requested);
        when(processor.bind(requested, registrationValue(registration()))).thenReturn(requested.bindJob(200, NOW));
        var retry = facade.createBatchAdmin(List.of(command)).items().getFirst();
        assertThat(retry.draftId()).isEqualTo("100");
        assertThat(retry.jobId()).isEqualTo("200");
        assertThat(retry.idempotencyKey()).isEqualTo(results.getFirst().idempotencyKey());
        verify(processor).createOrReplay(org.mockito.ArgumentMatchers.eq(command), any(), any(), any());
        verifyNoInteractions(productImages, imageSources);
    }

    @Test
    void disabledImageIsPermanentItemFailureAndDoesNotPreventTextReplay() {
        var other = new SizeChartDraftCreateCommand(11, 20, 40, UUID.randomUUID(), "TEXT", "S 50 cm");
        when(products.findAllIncludingHidden(List.of(10L, 11L))).thenReturn(java.util.Map.of());
        when(processor.replay(org.mockito.ArgumentMatchers.eq(other), any())).thenReturn(Optional.of(draft().bindJob(200, NOW)));
        var results = facade.createBatchAdmin(List.of(imageCommand, other)).items();
        assertThat(results.getFirst().errorCode()).isEqualTo("SIZE_CHART_DRAFT_011");
        assertThat(results.getFirst().retryable()).isFalse();
        assertThat(results.get(1).jobId()).isEqualTo("200");
        verifyNoInteractions(productImages, imageSources, generation);
    }

    @Test
    void tenProductsAtTheLimitAreAcceptedAndReadInOneBulkLookup() {
        var commands = LongStream.rangeClosed(1, SizeChartDraftBatchLimits.MAX_ITEMS)
            .mapToObj(id -> new SizeChartDraftCreateCommand(id, 20, 40, UUID.randomUUID(), "TEXT", "S 50 cm")).toList();
        var productIds = commands.stream().map(SizeChartDraftCreateCommand::productId).toList();
        when(products.findAllIncludingHidden(productIds)).thenReturn(java.util.Map.of());
        when(processor.replay(any(), any())).thenReturn(Optional.of(draft().bindJob(200, NOW)));
        assertThat(facade.createBatchAdmin(commands).items()).hasSize(10).allSatisfy(item -> {
            assertThat(item.jobId()).isEqualTo("200");
            assertThat(item.errorCode()).isNull();
        });
        verify(products).findAllIncludingHidden(productIds);
        verifyNoInteractions(productImages, imageSources, generation);
    }

    @Test
    void imageBatchUsesOneBulkLookupAndExistingApprovalForEachItem() {
        properties.setImageEnabled(true);
        var second = new SizeChartDraftCreateCommand(11, 20, 40, UUID.randomUUID(), "IMAGE", null, imageCommand.images(), true);
        var product = mock(Product.class);
        var other = mock(Product.class);
        when(products.findAllIncludingHidden(List.of(10L, 11L))).thenReturn(java.util.Map.of(10L, product, 11L, other));
        when(productImages.findStillImagesIncludingHidden(List.of(10L, 11L))).thenReturn(List.of());
        var approved = List.of(new SizeChartDraftImage("products/10/1.png", "c".repeat(64), "image/png", 12));
        when(imageSources.approve(org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(product), any(), any())).thenReturn(approved);
        when(imageSources.approve(org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.eq(other), any(), any())).thenReturn(approved);
        when(processor.createOrReplay(any(), any(), any(), any())).thenReturn(draft().bindJob(200, NOW));
        assertThat(facade.createBatchAdmin(List.of(imageCommand, second)).items()).allSatisfy(item -> assertThat(item.errorCode()).isNull());
        verify(productImages).findStillImagesIncludingHidden(List.of(10L, 11L));
        verify(productImages, never()).findStillImagesIncludingHidden(org.mockito.ArgumentMatchers.anyLong());
        verify(imageSources).approve(10, product, List.of(), imageCommand.images());
        verify(imageSources).approve(11, other, List.of(), second.images());
    }

    @Test
    void staleSelectedTextNeverCreatesDraftButExistingInputCanReplayAfterBodyChanges() {
        var selected = new SizeChartDraftCreateCommand(10, 20, 40, command.idempotencyKey(), "TEXT", "S 50 cm", List.of(), false,
            new SizeChartDraftCreateCommand.TextSelection("TEXT-KO-1", "0".repeat(64)));
        var product = mock(Product.class);
        when(product.id()).thenReturn(10L);
        when(products.findAllIncludingHidden(List.of(10L))).thenReturn(java.util.Map.of(10L, product));
        assertThatThrownBy(() -> facade.create(selected)).isInstanceOfSatisfying(SizeChartDraftTransportException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.TEXT_SELECTION_CHANGED));
        verify(processor, never()).createOrReplay(any(), any(), any(), any());
        verifyNoInteractions(generation);
        when(processor.replay(org.mockito.ArgumentMatchers.eq(selected), any())).thenReturn(Optional.of(draft().bindJob(200, NOW)));
        assertThat(facade.create(selected).jobId()).isEqualTo(200);
    }

    private static SizeChartGenerationRegistration registrationValue(
        SizeChartGenerationRegistrationQueryResult result) {
        return new SizeChartGenerationRegistration(result.draftId(), result.jobId(), result.inputHash());
    }

    private static SizeChartGenerationResult resultValue(SizeChartGenerationResultQueryResult result) {
        return new SizeChartGenerationResult(result.draftId(), result.jobId(), result.inputHash(), result.status(), result.failureCode(), result.resultJson());
    }

    private SizeChartDraft draft() {
        return SizeChartDraft.request(100, new SizeChartDraft.Input(10, 20, 3, 40, command.idempotencyKey(),
            validation.normalizedRequestHash(command), "a".repeat(64), "{\"mode\":\"TEXT\",\"text\":\"S 50 cm\"}",
            "{\"unit\":\"CM\",\"columns\":[{\"id\":30,\"name\":\"Chest\"}]}", null, null), NOW.plusSeconds(3600));
    }

    private SizeChartGenerationRegistrationQueryResult registration() {
        return new SizeChartGenerationRegistrationQueryResult(100, 200, "a".repeat(64));
    }
}
