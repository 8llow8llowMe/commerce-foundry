package com.commercefoundry.domainlayer.productdraft.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.presenter.ProductDraftAdminPresenter;
import com.commercefoundry.domainlayer.productdraft.application.command.ProductDraftCreateCommand;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftGenerationProcessor;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftGenerationResultProcessor;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftImageProcessor;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftProcessor;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftGeneration;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageSource;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageType;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftStatus;
import com.commercefoundry.global.properties.ProductDraftProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 거절 — 응답 상태는 Facade 가 잡은 한 시각으로 판정하고, 낙관적 락 충돌은 409 · PRODUCT_DRAFT_025 다. 생성 · 거절은 성공 · 실패를 감사에 남긴다(#163 PR-C′).
 * 생성은 판정 → 키 · ETag 고정(트랜잭션 밖) → 저장 순이다(#359 PR-2a).
 */
class ProductDraftAdminFacadeTest {

    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");
    private static final ProductDraftFacts FACTS = new ProductDraftFacts(null, null, null, null, null, null, null, null, null, null);

    private static final String HASH = "f".repeat(64);

    private final ProductDraftProcessor processor = mock(ProductDraftProcessor.class);
    private final ProductDraftImageProcessor imageProcessor = mock(ProductDraftImageProcessor.class);
    private final ProductDraftGenerationProcessor generationProcessor = mock(ProductDraftGenerationProcessor.class);
    private final ProductDraftGenerationResultProcessor resultProcessor = mock(ProductDraftGenerationResultProcessor.class);
    private final ProductAdminAuditProcessor audit = mock(ProductAdminAuditProcessor.class);
    private final ProductDraftAdminFacade facade = facade(false);

    private ProductDraftAdminFacade facade(boolean generationEnabled) {
        return new ProductDraftAdminFacade(processor, imageProcessor, generationProcessor, resultProcessor,
            new ProductDraftProperties(Duration.ofDays(7), generationEnabled), new ProductDraftAdminPresenter(), audit);
    }

    private static ProductDraft requested() {
        return new ProductDraft(9L, ProductDraftStatus.REQUESTED, 100L, FACTS, List.of(), null, null, NOW.plusSeconds(60), NOW);
    }

    private static ProductDraft claimed() {
        return new ProductDraft(9L, ProductDraftStatus.REQUESTED, 100L, FACTS, List.of(), null, null, NOW.plusSeconds(60), NOW,
            new ProductDraftGeneration(1, 200L, NOW, HASH, null));
    }

    private static ProductDraft generating() {
        return new ProductDraft(9L, ProductDraftStatus.GENERATING, 100L, FACTS, List.of(), null, null, NOW.plusSeconds(60), NOW,
            new ProductDraftGeneration(1, 200L, NOW, HASH, 31L));
    }


    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ProductDraftErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProductDraftException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    @Test
    void detailReadsPersistedStateEvenWhenGenerationIsEnabled() {
        given(processor.now()).willReturn(NOW);
        given(processor.getDraft(9L)).willReturn(generating());

        assertThat(facade(true).getDraft(9L).status().code()).isEqualTo("GENERATING");
        verifyNoInteractions(generationProcessor, resultProcessor, audit);
    }

    @Test
    void refreshWithClosedGateDoesNotQueryAndAuditsFailure() {
        given(processor.now()).willReturn(NOW);
        given(processor.getDraft(9L)).willReturn(generating());

        assertCode(() -> facade.refresh(9L, 200L), ProductDraftErrorCode.GENERATION_START_DISABLED);
        verifyNoInteractions(generationProcessor, resultProcessor);
        verify(audit).productDraftRefreshed(200L, 9L, false);
    }

    @Test
    void refreshWithInitialDatabaseFailureAuditsFailureWithoutAQuery() {
        given(processor.getDraft(9L)).willThrow(new QueryTimeoutException("db"));

        assertCode(() -> facade(true).refresh(9L, 200L), ProductDraftErrorCode.GENERATION_UNAVAILABLE);
        verifyNoInteractions(resultProcessor);
        verify(audit).productDraftRefreshed(200L, 9L, false);
    }

    @Test
    void refreshWithAmbientTransactionDoesNotQuery() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertCode(() -> facade(true).refresh(9L, 200L), ProductDraftErrorCode.GENERATION_UNAVAILABLE);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(processor, generationProcessor, resultProcessor);
        verify(audit).productDraftRefreshed(200L, 9L, false);
    }

    @Nested
    @DisplayName("생성 시작 (#359 PR-B1)")
    class Generate {

        private final ProductDraftAdminFacade enabled = facade(true);

        @Test
        @DisplayName("claim(짧은 쓰기) → 등록(트랜잭션 밖) → 작업 연결(짧은 쓰기) 순이고 생성 중 초안을 돌려준다. 성공을 감사에 남긴다")
        void startsInOrder() {
            given(processor.now()).willReturn(NOW);
            given(generationProcessor.claim(9L, 200L)).willReturn(claimed());
            given(generationProcessor.register(claimed())).willReturn(31L);
            given(generationProcessor.bind(claimed(), 31L)).willReturn(generating());

            var response = enabled.generate(9L, 200L);

            assertThat(response.status().code()).isEqualTo("GENERATING");
            InOrder order = inOrder(generationProcessor);
            order.verify(generationProcessor).claim(9L, 200L);
            order.verify(generationProcessor).register(claimed());
            order.verify(generationProcessor).bind(claimed(), 31L);
            verify(audit).productDraftGenerated(200L, 9L, true);
        }

        @Test
        @DisplayName("이미 생성 중이면 등록하지 않고 그대로 200 이다")
        void alreadyGenerating() {
            given(processor.now()).willReturn(NOW);
            given(generationProcessor.claim(9L, 200L)).willReturn(generating());

            assertThat(enabled.generate(9L, 200L).status().code()).isEqualTo("GENERATING");
            verify(generationProcessor, never()).register(any());
            verify(generationProcessor, never()).bind(any(), anyLong());
            verify(audit).productDraftGenerated(200L, 9L, true);
        }

        @Test
        @DisplayName("플래그가 닫혀 있으면 050 이고 claim 하지 않는다 — 실패를 감사에 남긴다")
        void disabled() {
            assertCode(() -> facade.generate(9L, 200L), ProductDraftErrorCode.GENERATION_START_DISABLED);
            verifyNoInteractions(generationProcessor);
            verify(audit).productDraftGenerated(200L, 9L, false);
        }

        @Test
        @DisplayName("등록 실패(502 · 504 · 022)는 그대로 던지고 작업을 연결하지 않는다 — claim 은 되돌리지 않는다(재시도가 같은 값으로 재등록)")
        void registrationFailureKeepsClaim() {
            given(generationProcessor.claim(9L, 200L)).willReturn(claimed());
            for (ProductDraftErrorCode code : List.of(ProductDraftErrorCode.GENERATION_UNAVAILABLE, ProductDraftErrorCode.GENERATION_CONTRACT_VIOLATION,
                ProductDraftErrorCode.DRAFT_EXPIRED)) {
                doThrow(new ProductDraftException(code)).when(generationProcessor).register(claimed());
                assertCode(() -> enabled.generate(9L, 200L), code);
            }
            verify(generationProcessor, never()).bind(any(), anyLong());
            verify(audit, times(3)).productDraftGenerated(200L, 9L, false);
        }

        @Test
        @DisplayName("claim 의 낙관적 락 충돌은 025 이고 등록하지 않는다")
        void claimBusy() {
            given(generationProcessor.claim(9L, 200L)).willThrow(new ObjectOptimisticLockingFailureException("ProductDraftEntity", 9L));

            assertCode(() -> enabled.generate(9L, 200L), ProductDraftErrorCode.DRAFT_BUSY);
            verify(generationProcessor, never()).register(any());
        }

        @Test
        @DisplayName("작업 연결의 낙관적 락 충돌은 한 번 다시 읽어 판정한다 — 두 번째도 충돌이면 025, 저장 실패는 502(등록은 됐을 수 있다)")
        void bindRetryAndFailures() {
            given(processor.now()).willReturn(NOW);
            given(generationProcessor.claim(9L, 200L)).willReturn(claimed());
            given(generationProcessor.register(claimed())).willReturn(31L);
            given(generationProcessor.bind(claimed(), 31L))
                .willThrow(new ObjectOptimisticLockingFailureException("ProductDraftEntity", 9L)).willReturn(generating());
            assertThat(enabled.generate(9L, 200L).status().code()).isEqualTo("GENERATING");

            doThrow(new ObjectOptimisticLockingFailureException("ProductDraftEntity", 9L)).when(generationProcessor).bind(claimed(), 31L);
            assertCode(() -> enabled.generate(9L, 200L), ProductDraftErrorCode.DRAFT_BUSY);

            doThrow(new QueryTimeoutException("down")).when(generationProcessor).bind(claimed(), 31L);
            assertCode(() -> enabled.generate(9L, 200L), ProductDraftErrorCode.GENERATION_UNAVAILABLE);
        }

        @Test
        @DisplayName("저장 뒤 자동 시작 — 플래그가 열려 있으면 만든 어드민으로 시작해 생성 중 초안을 돌려준다")
        void createStarts() {
            ProductDraftCreateCommand command = ProductDraftCreateCommand.builder().requestedByMemberId(100L).facts(FACTS).images(List.of()).build();
            given(processor.now()).willReturn(NOW);
            given(imageProcessor.pin(List.of())).willReturn(List.of());
            given(processor.create(command)).willReturn(requested());
            given(generationProcessor.claim(9L, 100L)).willReturn(claimed());
            given(generationProcessor.register(claimed())).willReturn(31L);
            given(generationProcessor.bind(claimed(), 31L)).willReturn(generating());

            assertThat(enabled.create(command).status().code()).isEqualTo("GENERATING");
            verify(audit).productDraftCreated(100L, 9L, true);
            verify(audit, never()).productDraftGenerated(anyLong(), anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("저장 뒤 자동 시작이 실패해도 생성 대기 초안을 돌려준다(201 유지) — 업무 예외도 예상 못 한 예외도 삼킨다")
        void createSurvivesStartFailure() {
            ProductDraftCreateCommand command = ProductDraftCreateCommand.builder().requestedByMemberId(100L).facts(FACTS).images(List.of()).build();
            given(processor.now()).willReturn(NOW);
            given(imageProcessor.pin(List.of())).willReturn(List.of());
            given(processor.create(command)).willReturn(requested());
            given(generationProcessor.claim(9L, 100L)).willReturn(claimed());
            given(generationProcessor.register(claimed())).willThrow(new ProductDraftException(ProductDraftErrorCode.GENERATION_UNAVAILABLE));

            assertThat(enabled.create(command).status().code()).isEqualTo("REQUESTED");

            given(generationProcessor.claim(9L, 100L)).willThrow(new IllegalStateException("unexpected"));
            assertThat(enabled.create(command).status().code()).isEqualTo("REQUESTED");
            verify(audit, times(2)).productDraftCreated(100L, 9L, true);
        }

        @Test
        @DisplayName("플래그가 닫혀 있으면 저장 뒤 시작하지 않는다")
        void createWithoutStart() {
            ProductDraftCreateCommand command = ProductDraftCreateCommand.builder().requestedByMemberId(100L).facts(FACTS).images(List.of()).build();
            given(processor.now()).willReturn(NOW);
            given(imageProcessor.pin(List.of())).willReturn(List.of());
            given(processor.create(command)).willReturn(requested());

            assertThat(facade.create(command).status().code()).isEqualTo("REQUESTED");
            verifyNoInteractions(generationProcessor);
        }

        @Test
        @DisplayName("상위 트랜잭션 안에서 저장하면 원격 등록하지 않고 명시 재시도용 REQUESTED 를 돌려준다")
        void createInsideTransactionDoesNotRegister() {
            ProductDraftCreateCommand command = ProductDraftCreateCommand.builder().requestedByMemberId(100L).facts(FACTS).images(List.of()).build();
            given(processor.now()).willReturn(NOW);
            given(imageProcessor.pin(List.of())).willReturn(List.of());
            given(processor.create(command)).willReturn(requested());
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThat(enabled.create(command).status().code()).isEqualTo("REQUESTED");
                verifyNoInteractions(generationProcessor);
            } finally {
                TransactionSynchronizationManager.clear();
            }
        }

        @Test
        @DisplayName("명시 생성 시작은 상위 트랜잭션 안에서 원격 등록을 시도하지 않는다")
        void generateRefusesAmbientTransaction() {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertCode(() -> enabled.generate(9L, 200L), ProductDraftErrorCode.GENERATION_UNAVAILABLE);
                verifyNoInteractions(generationProcessor);
            } finally {
                TransactionSynchronizationManager.clear();
            }
        }

        @Test
        @DisplayName("생성 시작은 원격 등록이 섞여 Facade 에 트랜잭션을 걸지 않는다")
        void generateHasNoFacadeTransaction() throws NoSuchMethodException {
            assertThat(ProductDraftAdminFacade.class.getMethod("generate", long.class, long.class).isAnnotationPresent(Transactional.class)).isFalse();
        }
    }

    @Test
    @DisplayName("거절 응답은 REJECTED 와 거절 시각을 내고, 거절한 어드민 · 초안으로 성공을 감사에 남긴다")
    void reject() {
        ProductDraft rejected = new ProductDraft(7L, ProductDraftStatus.REJECTED, 100L, FACTS, List.of(), 200L, NOW, NOW.plusSeconds(60), NOW.minusSeconds(60));
        given(processor.now()).willReturn(NOW);
        given(processor.reject(7L, 200L, NOW)).willReturn(rejected);

        var response = facade.reject(7L, 200L);

        assertThat(response.status().code()).isEqualTo("REJECTED");
        assertThat(response.rejectedAt()).isEqualTo(NOW);
        verify(audit).productDraftRejected(200L, 7L, true);
    }

    @Test
    @DisplayName("다른 변경이 먼저 커밋돼 낙관적 락이 깨지면 PRODUCT_DRAFT_025 이고, 바꾼 예외도 실패로 감사에 남긴다")
    void optimisticLockIsBusy() {
        given(processor.now()).willReturn(NOW);
        given(processor.reject(7L, 200L, NOW)).willThrow(new ObjectOptimisticLockingFailureException("ProductDraftEntity", 7L));

        assertThatThrownBy(() -> facade.reject(7L, 200L))
            .isInstanceOfSatisfying(ProductDraftException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(ProductDraftErrorCode.DRAFT_BUSY));
        verify(audit).productDraftRejected(200L, 7L, false);
        verify(audit, never()).productDraftRejected(200L, 7L, true);
    }

    @Test
    @DisplayName("생성은 만든 초안 아이디로 성공을, 아이디를 받기 전 실패는 대상 없이(null) 실패를 남기고 같은 예외를 다시 던진다")
    void createIsAudited() {
        ProductDraftCreateCommand command = ProductDraftCreateCommand.builder().requestedByMemberId(100L).facts(FACTS).images(List.of()).build();
        given(processor.now()).willReturn(NOW);
        given(imageProcessor.pin(List.of())).willReturn(List.of());
        given(processor.create(command)).willReturn(new ProductDraft(9L, ProductDraftStatus.REQUESTED, 100L, FACTS, List.of(), null, null, NOW.plusSeconds(60), NOW));

        facade.create(command);
        verify(audit).productDraftCreated(100L, 9L, true);

        ProductDraftException failure = new ProductDraftException(ProductDraftErrorCode.IMAGE_DUPLICATED);
        given(processor.create(command)).willThrow(failure);
        assertThatThrownBy(() -> facade.create(command)).isSameAs(failure);
        verify(audit).productDraftCreated(100L, null, false);
    }

    @Test
    @DisplayName("생성은 판정 → 원본 고정 → 고정한 이미지로 저장 순이고, 고정이 실패하면 저장하지 않고 실패를 감사에 남긴다")
    void createPinsImagesBeforeSaving() {
        ProductDraftImage requested = new ProductDraftImage("https://cdn.example.com/media/a.jpg", ProductDraftImageType.THUMBNAIL, null, null);
        ProductDraftImage pinned = requested.pinned(new ProductDraftImageSource("products/media/a.jpg", "\"e\"", 10L, "image/jpeg"));
        ProductDraftCreateCommand command = ProductDraftCreateCommand.builder().requestedByMemberId(100L).facts(FACTS).images(List.of(requested)).build();
        given(processor.now()).willReturn(NOW);
        given(imageProcessor.pin(List.of(requested))).willReturn(List.of(pinned));
        given(processor.create(command.withImages(List.of(pinned))))
            .willReturn(new ProductDraft(9L, ProductDraftStatus.REQUESTED, 100L, FACTS, List.of(pinned), null, null, NOW.plusSeconds(60), NOW));

        facade.create(command);

        InOrder order = inOrder(processor, imageProcessor);
        order.verify(processor).validateImages(List.of(requested));
        order.verify(imageProcessor).pin(List.of(requested));
        order.verify(processor).create(command.withImages(List.of(pinned)));

        ProductDraftException storageDown = new ProductDraftException(ProductDraftErrorCode.IMAGE_STORAGE_UNAVAILABLE);
        given(imageProcessor.pin(List.of(requested))).willThrow(storageDown);
        assertThatThrownBy(() -> facade.create(command)).isSameAs(storageDown);
        verify(processor).create(any());
        verify(audit).productDraftCreated(100L, null, false);
    }

    @Test
    @DisplayName("생성은 저장소 HEAD 가 섞여 Facade 에 트랜잭션을 걸지 않는다 — 저장 트랜잭션은 Processor 에만 있다")
    void createHasNoFacadeTransaction() throws NoSuchMethodException {
        assertThat(ProductDraftAdminFacade.class.getMethod("create", ProductDraftCreateCommand.class).isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(ProductDraftAdminFacade.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(ProductDraftProcessor.class.getMethod("create", ProductDraftCreateCommand.class).getAnnotation(Transactional.class).propagation())
            .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRED);
    }
}
