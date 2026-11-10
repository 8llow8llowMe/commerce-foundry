package com.commercefoundry.domainlayer.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.ClaimAdminResponse;
import com.commercefoundry.domainlayer.order.adapter.in.admin.presenter.ClaimAdminPresenter;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminDetailInfo;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimAdminAuditProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimAdminProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimAdminRecoveryProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationPipelineProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.CannotCreateTransactionException;

/** 어드민 다시 시도의 단계 순서 · 실패 처리 · 감사 (#17 A2b). 선점 커밋 → 원격(트랜잭션 밖) → 현재 상세 순이다. */
class ClaimAdminFacadeTest {
    private static final long ADMIN = 9;

    private final ClaimAdminProcessor processor = mock(ClaimAdminProcessor.class);
    private final ClaimAdminRecoveryProcessor recovery = mock(ClaimAdminRecoveryProcessor.class);
    private final OrderCancellationProcessor cancellation = mock(OrderCancellationProcessor.class);
    private final OrderCancellationPipelineProcessor pipeline = mock(OrderCancellationPipelineProcessor.class);
    private final ClaimAdminPresenter presenter = mock(ClaimAdminPresenter.class);
    private final ClaimAdminAuditProcessor audit = mock(ClaimAdminAuditProcessor.class);
    private final ClaimAdminFacade facade = new ClaimAdminFacade(processor, recovery, cancellation, pipeline, presenter, audit);

    private final Claim held = mock(Claim.class);
    private final ClaimAdminDetailInfo detail = mock(ClaimAdminDetailInfo.class);
    private final ClaimAdminResponse response = mock(ClaimAdminResponse.class);

    private void prepared() {
        when(recovery.prepareRetry(2, 4)).thenReturn(held);
        when(processor.getDetail(2)).thenReturn(detail);
        when(presenter.toResponse(detail)).thenReturn(response);
    }

    @Test
    @DisplayName("선점한 클레임으로 원격 단계를 잇고, 끝난 뒤 다시 읽은 현재 상세를 내며 성공 감사를 남긴다")
    void retriesWithHeldClaimThenReturnsFreshDetail() {
        prepared();

        assertThat(facade.retry(2, 4, ADMIN)).isSameAs(response);

        InOrder order = inOrder(recovery, pipeline, processor, audit);
        order.verify(recovery).prepareRetry(2, 4);
        order.verify(pipeline).progress(held);
        order.verify(audit).retried(ADMIN, 2, true);
        order.verify(processor).getDetail(2);
        verify(cancellation, never()).recordAttemptFailure(any());
    }

    @Test
    @DisplayName("선점이 거절되면(409 · 503 · 404) 원격을 부르지 않고 실패 감사를 남긴 뒤 그대로 던진다")
    void prepareFailureIsAuditedAndRethrown() {
        OrderException conflict = new OrderException(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        when(recovery.prepareRetry(2, 4)).thenThrow(conflict);

        assertThatThrownBy(() -> facade.retry(2, 4, ADMIN)).isSameAs(conflict);

        verify(pipeline, never()).progress(any());
        verify(audit).retried(ADMIN, 2, false);
    }

    @Test
    @DisplayName("원격 단계가 반영하지 못하고 끊기면 스케줄러처럼 미확정 한 번으로 세고 현재 상세 200 이다 — 클레임은 lease 로 남아 스케줄러가 잇는다")
    void interruptedProgressIsCountedOnce() {
        prepared();
        when(pipeline.progress(held)).thenThrow(new CannotCreateTransactionException("db down"));

        assertThat(facade.retry(2, 4, ADMIN)).isSameAs(response);

        verify(cancellation).recordAttemptFailure(held);
        verify(audit).retried(ADMIN, 2, true);
    }

    @Test
    @DisplayName("일반 OrderException 도 미확정 한 번으로 센다 — 실패 횟수 저장마저 실패해도 상세를 낸다")
    void countFailureFailureDoesNotBreakResponse() {
        prepared();
        when(pipeline.progress(held)).thenThrow(new OrderException(OrderErrorCode.NOT_FOUND_ORDER));
        org.mockito.Mockito.doThrow(new CannotCreateTransactionException("db down")).when(cancellation).recordAttemptFailure(held);

        assertThat(facade.retry(2, 4, ADMIN)).isSameAs(response);
    }

    @Test
    @DisplayName("확정 환불 미기록(503 ORDER_036)은 실패 횟수로 세지 않고 실패 감사와 함께 그대로 전달한다")
    void unrecordedConfirmedRefundIsPropagated() {
        prepared();
        OrderException unrecorded = new OrderException(OrderErrorCode.CANCELLATION_REFUND_RECORDING_UNAVAILABLE);
        when(pipeline.progress(held)).thenThrow(unrecorded);

        assertThatThrownBy(() -> facade.retry(2, 4, ADMIN)).isSameAs(unrecorded);

        verify(cancellation, never()).recordAttemptFailure(any());
        verify(audit).retried(ADMIN, 2, false);
    }
}
