package com.commercefoundry.domainlayer.productdraft.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.presenter.ProductDraftAdminPresenter;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftProcessor;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** 거절 — 응답 상태는 Facade 가 잡은 한 시각으로 판정하고, 낙관적 락 충돌은 409 · PRODUCT_DRAFT_025 다. */
class ProductDraftAdminFacadeTest {

    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");

    private final ProductDraftProcessor processor = mock(ProductDraftProcessor.class);
    private final ProductDraftAdminFacade facade = new ProductDraftAdminFacade(processor, new ProductDraftAdminPresenter());

    @Test
    @DisplayName("거절 응답은 REJECTED 와 거절 시각을 낸다")
    void reject() {
        ProductDraft rejected = new ProductDraft(7L, ProductDraftStatus.REJECTED, 100L,
            new ProductDraftFacts(null, null, null, null, null, null, null, null, null, null), List.of(), 200L, NOW, NOW.plusSeconds(60), NOW.minusSeconds(60));
        given(processor.now()).willReturn(NOW);
        given(processor.reject(7L, 200L, NOW)).willReturn(rejected);

        var response = facade.reject(7L, 200L);

        assertThat(response.status().code()).isEqualTo("REJECTED");
        assertThat(response.rejectedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("다른 변경이 먼저 커밋돼 낙관적 락이 깨지면 PRODUCT_DRAFT_025 다")
    void optimisticLockIsBusy() {
        given(processor.now()).willReturn(NOW);
        given(processor.reject(7L, 200L, NOW)).willThrow(new ObjectOptimisticLockingFailureException("ProductDraftEntity", 7L));

        assertThatThrownBy(() -> facade.reject(7L, 200L))
            .isInstanceOfSatisfying(ProductDraftException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(ProductDraftErrorCode.DRAFT_BUSY));
    }
}
