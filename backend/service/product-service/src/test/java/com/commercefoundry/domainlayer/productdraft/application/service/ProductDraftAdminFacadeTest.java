package com.commercefoundry.domainlayer.productdraft.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.presenter.ProductDraftAdminPresenter;
import com.commercefoundry.domainlayer.productdraft.application.command.ProductDraftCreateCommand;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftImageProcessor;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftProcessor;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageSource;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageType;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거절 — 응답 상태는 Facade 가 잡은 한 시각으로 판정하고, 낙관적 락 충돌은 409 · PRODUCT_DRAFT_025 다. 생성 · 거절은 성공 · 실패를 감사에 남긴다(#163 PR-C′).
 * 생성은 판정 → 키 · ETag 고정(트랜잭션 밖) → 저장 순이다(#359 PR-2a).
 */
class ProductDraftAdminFacadeTest {

    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");
    private static final ProductDraftFacts FACTS = new ProductDraftFacts(null, null, null, null, null, null, null, null, null, null);

    private final ProductDraftProcessor processor = mock(ProductDraftProcessor.class);
    private final ProductDraftImageProcessor imageProcessor = mock(ProductDraftImageProcessor.class);
    private final ProductAdminAuditProcessor audit = mock(ProductAdminAuditProcessor.class);
    private final ProductDraftAdminFacade facade = new ProductDraftAdminFacade(processor, imageProcessor, new ProductDraftAdminPresenter(), audit);

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
