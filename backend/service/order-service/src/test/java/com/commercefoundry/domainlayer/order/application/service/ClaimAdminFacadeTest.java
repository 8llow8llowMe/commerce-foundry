package com.commercefoundry.domainlayer.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.ClaimAdminResponse;
import com.commercefoundry.domainlayer.order.adapter.in.admin.presenter.ClaimAdminPresenter;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminDetailInfo;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimAdminActionProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimAdminAuditProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimAdminProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationPipelineProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * 어드민 클레임 수습 Facade (#17 PR-A) — 원격 단계 연결, 버전 충돌의 바꾸지 않은 200 · ORDER_025(스케줄러 선점과 재시도 · 거절과 재시도의 경합),
 * 성공 · 실패 감사.
 */
class ClaimAdminFacadeTest {

    private static final long CLAIM = 400L;
    private static final long ADMIN = 9L;
    private static final String REASON = "PG 거절 확인";

    private final ClaimAdminProcessor processor = mock(ClaimAdminProcessor.class);
    private final ClaimAdminActionProcessor actions = mock(ClaimAdminActionProcessor.class);
    private final OrderCancellationProcessor cancellations = mock(OrderCancellationProcessor.class);
    private final OrderCancellationPipelineProcessor pipeline = mock(OrderCancellationPipelineProcessor.class);
    private final ClaimAdminPresenter presenter = mock(ClaimAdminPresenter.class);
    private final ClaimAdminAuditProcessor audit = mock(ClaimAdminAuditProcessor.class);
    private final ClaimAdminFacade facade = new ClaimAdminFacade(processor, actions, cancellations, pipeline, presenter, audit);
    private final ClaimAdminResponse response = mock(ClaimAdminResponse.class);

    @BeforeEach
    void presentDetail() {
        ClaimAdminDetailInfo detail = mock(ClaimAdminDetailInfo.class);
        when(processor.getDetail(CLAIM)).thenReturn(detail);
        when(presenter.toResponse(detail)).thenReturn(response);
    }

    private static Claim claimIn(Claim.Status status) {
        Claim claim = mock(Claim.class);
        when(claim.status()).thenReturn(status);
        when(claim.id()).thenReturn(CLAIM);
        when(claim.orderId()).thenReturn(1L);
        return claim;
    }

    private static ObjectOptimisticLockingFailureException lost() {
        return new ObjectOptimisticLockingFailureException("ClaimEntity", CLAIM);
    }

    private static void assertCode(Throwable thrown, OrderErrorCode code) {
        assertThat(thrown).isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("재시도 — 다시 열거나 선점한 클레임으로 원격 단계를 이어 돌린 뒤 상세를 돌려주고 성공을 감사한다")
    void retryProgressesPreparedClaim() {
        Claim prepared = claimIn(Claim.Status.REQUESTED);
        when(actions.prepareRetry(CLAIM)).thenReturn(new ClaimAdminActionProcessor.Prepared(true, prepared));

        assertThat(facade.retry(CLAIM, ADMIN)).isSameAs(response);

        var ordered = inOrder(actions, pipeline, audit, processor);
        ordered.verify(actions).prepareRetry(CLAIM);
        ordered.verify(pipeline).progress(prepared);
        ordered.verify(audit).retried(ADMIN, CLAIM, true);
        ordered.verify(processor).getDetail(CLAIM);
    }

    @Test
    @DisplayName("재시도 — 이미 완료면 원격 단계를 돌리지 않고 200 이다")
    void retryOfCompletedDoesNothing() {
        Claim completed = claimIn(Claim.Status.COMPLETED);
        when(actions.prepareRetry(CLAIM)).thenReturn(new ClaimAdminActionProcessor.Prepared(false, completed));

        assertThat(facade.retry(CLAIM, ADMIN)).isSameAs(response);

        verifyNoInteractions(pipeline);
        verify(audit).retried(ADMIN, CLAIM, true);
    }

    @Test
    @DisplayName("재시도 — 스케줄러 · 고객 요청이 먼저 선점했으면(버전 충돌) 409 ORDER_025 이고 원격을 부르지 않으며 실패를 감사한다")
    void retryLosingHoldIsConflict() {
        when(actions.prepareRetry(CLAIM)).thenThrow(lost());
        Claim held = claimIn(Claim.Status.REQUESTED);
        when(actions.get(CLAIM)).thenReturn(held);

        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> facade.retry(CLAIM, ADMIN)), OrderErrorCode.ORDER_CONCURRENTLY_CHANGED);

        verifyNoInteractions(pipeline);
        verify(audit).retried(ADMIN, CLAIM, false);
    }

    @Test
    @DisplayName("재시도 — 전제 오류(거절된 클레임 409 ORDER_036 등)는 그대로 나가고 실패를 감사한다")
    void retryPreconditionFailureIsAudited() {
        when(actions.prepareRetry(CLAIM)).thenThrow(new OrderException(OrderErrorCode.CLAIM_STATUS_INVALID, "취소 · 거절"));

        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> facade.retry(CLAIM, ADMIN)), OrderErrorCode.CLAIM_STATUS_INVALID);

        verify(audit).retried(ADMIN, CLAIM, false);
        verify(processor, never()).getDetail(CLAIM);
    }

    @Test
    @DisplayName("재시도 — 커밋 뒤 원격 단계가 DB 오류로 끊기면 미확정 한 번으로 세고(스케줄러가 잇는다) 그 시점의 상세로 200 이다")
    void retryInterruptedAfterCommitCountsAttempt() {
        Claim prepared = claimIn(Claim.Status.REFUNDED);
        when(actions.prepareRetry(CLAIM)).thenReturn(new ClaimAdminActionProcessor.Prepared(true, prepared));
        when(pipeline.progress(prepared)).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(facade.retry(CLAIM, ADMIN)).isSameAs(response);

        verify(cancellations).recordAttemptFailure(1L);
        verify(audit).retried(ADMIN, CLAIM, true);
    }

    @Test
    @DisplayName("거절 — 성공을 감사하고 상세를 돌려준다. 버전에서 졌어도 다시 읽은 클레임이 이미 거절이면 바꾸지 않은 200 이다")
    void rejectSucceedsAndSameResultAfterLostVersionIsOk() {
        when(actions.reject(CLAIM, REASON, ADMIN)).thenReturn(true);
        assertThat(facade.reject(CLAIM, REASON, ADMIN)).isSameAs(response);

        when(actions.reject(CLAIM, REASON, ADMIN)).thenThrow(lost());
        Claim rejected = claimIn(Claim.Status.REJECTED);
        when(actions.get(CLAIM)).thenReturn(rejected);
        assertThat(facade.reject(CLAIM, REASON, ADMIN)).isSameAs(response);

        verify(audit, org.mockito.Mockito.times(2)).rejected(ADMIN, CLAIM, true);
        verifyNoInteractions(pipeline);
    }

    @Test
    @DisplayName("거절 vs 재시도 — 재시도가 먼저 다시 열었으면(버전 충돌 뒤 환불 대기) 거절은 409 ORDER_025 이고 실패를 감사한다")
    void rejectLosingToRetryIsConflict() {
        when(actions.reject(CLAIM, REASON, ADMIN)).thenThrow(lost());
        Claim reopened = claimIn(Claim.Status.REQUESTED);
        when(actions.get(CLAIM)).thenReturn(reopened);

        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> facade.reject(CLAIM, REASON, ADMIN)), OrderErrorCode.ORDER_CONCURRENTLY_CHANGED);

        verify(audit).rejected(ADMIN, CLAIM, false);
    }

    @Test
    @DisplayName("거절 — 미확정 실패(ORDER_038) · 원상태 없음(ORDER_041)은 그대로 나가고 실패를 감사한다")
    void rejectFailuresAreAudited() {
        when(actions.reject(CLAIM, REASON, ADMIN)).thenThrow(new OrderException(OrderErrorCode.REFUND_UNVERIFIED))
            .thenThrow(new OrderException(OrderErrorCode.CANCELLATION_ORIGIN_NOT_FOUND));

        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> facade.reject(CLAIM, REASON, ADMIN)), OrderErrorCode.REFUND_UNVERIFIED);
        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> facade.reject(CLAIM, REASON, ADMIN)), OrderErrorCode.CANCELLATION_ORIGIN_NOT_FOUND);

        verify(audit, org.mockito.Mockito.times(2)).rejected(ADMIN, CLAIM, false);
    }

    @Test
    @DisplayName("수동 환불 확정 — 확정했으면 재고 복원을 이어 돌리고, 이미 확정 뒤면(빈 값) 원격을 부르지 않는다. 둘 다 성공 감사")
    void confirmRefundProgressesOnlyWhenChanged() {
        Claim refunded = claimIn(Claim.Status.REFUNDED);
        when(actions.confirmRefund(CLAIM, REASON, ADMIN)).thenReturn(Optional.of(refunded)).thenReturn(Optional.empty());

        assertThat(facade.confirmRefund(CLAIM, REASON, ADMIN)).isSameAs(response);
        assertThat(facade.confirmRefund(CLAIM, REASON, ADMIN)).isSameAs(response);

        verify(pipeline).progress(refunded);
        verify(audit, org.mockito.Mockito.times(2)).refundConfirmed(ADMIN, CLAIM, true);
    }

    @Test
    @DisplayName("수동 환불 확정 — 버전에서 졌는데 늦게 온 성공으로 이미 환불 완료면 200(원격 안 부름), 아니면 409 ORDER_025")
    void confirmRefundLostVersion() {
        when(actions.confirmRefund(CLAIM, REASON, ADMIN)).thenThrow(lost());
        Claim refunded = claimIn(Claim.Status.REFUNDED);
        Claim reopened = claimIn(Claim.Status.REQUESTED);
        when(actions.get(CLAIM)).thenReturn(refunded).thenReturn(reopened);

        assertThat(facade.confirmRefund(CLAIM, REASON, ADMIN)).isSameAs(response);
        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> facade.confirmRefund(CLAIM, REASON, ADMIN)), OrderErrorCode.ORDER_CONCURRENTLY_CHANGED);

        verifyNoInteractions(pipeline);
        verify(audit).refundConfirmed(ADMIN, CLAIM, true);
        verify(audit).refundConfirmed(ADMIN, CLAIM, false);
    }

    @Test
    @DisplayName("복원 생략 종결 — 성공 감사, 버전에서 졌어도 이미 완료면 200, 전제 오류는 실패 감사")
    void complete() {
        when(actions.complete(CLAIM, REASON, ADMIN)).thenReturn(true).thenThrow(lost())
            .thenThrow(new OrderException(OrderErrorCode.CLAIM_STATUS_INVALID, "취소 · 접수"));
        Claim completed = claimIn(Claim.Status.COMPLETED);
        when(actions.get(CLAIM)).thenReturn(completed);

        assertThat(facade.complete(CLAIM, REASON, ADMIN)).isSameAs(response);
        assertThat(facade.complete(CLAIM, REASON, ADMIN)).isSameAs(response);
        assertThatThrownBy(() -> facade.complete(CLAIM, REASON, ADMIN)).isInstanceOf(OrderException.class);

        verify(audit, org.mockito.Mockito.times(2)).completed(ADMIN, CLAIM, true);
        verify(audit).completed(ADMIN, CLAIM, false);
        verifyNoInteractions(pipeline);
    }

    @Test
    @DisplayName("원격 단계가 실패해 미확정 기록마저 실패해도 응답은 200 이다(다음 시각 lease 뒤 스케줄러가 다시 집는다)")
    void attemptFailureCountFailureIsSwallowed() {
        Claim prepared = claimIn(Claim.Status.REQUESTED);
        when(actions.prepareRetry(CLAIM)).thenReturn(new ClaimAdminActionProcessor.Prepared(true, prepared));
        when(pipeline.progress(any())).thenThrow(new DataAccessResourceFailureException("down"));
        doThrow(new DataAccessResourceFailureException("down")).when(cancellations).recordAttemptFailure(1L);

        assertThat(facade.retry(CLAIM, ADMIN)).isSameAs(response);
    }
}
