package com.commercefoundry.domainlayer.picking.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.picking.application.exception.PickingErrorCode;
import com.commercefoundry.domainlayer.picking.application.exception.PickingException;
import com.commercefoundry.domainlayer.picking.application.port.out.OrderPickingSnapshotQueryPort;
import com.commercefoundry.domainlayer.picking.application.port.out.PickingBatchRepositoryPort;
import com.commercefoundry.domainlayer.picking.domain.model.PickingBatch;
import com.commercefoundry.domainlayer.picking.domain.model.PickingExclusion;
import com.commercefoundry.domainlayer.picking.domain.model.PickingSelection;
import com.commercefoundry.domainlayer.picking.domain.model.PickingSnapshot;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class PickingProcessorTest {

    private final OrderPickingSnapshotQueryPort remote = mock(OrderPickingSnapshotQueryPort.class);
    private final PickingBatchRepositoryPort repository = mock(PickingBatchRepositoryPort.class);
    private final OrderPickingSnapshotProcessor snapshots = new OrderPickingSnapshotProcessor(remote);
    private final PickingBatchProcessor batches = new PickingBatchProcessor(repository, new SnowflakeIdGenerator(1, 1));

    private static PickingSnapshot snapshot(long id) {
        return new PickingSnapshot(id, "PREPARING", "KR", List.of(new PickingSnapshot.Line(10, 2, false)));
    }

    private static void assertCode(ThrowingCallable call, PickingErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PickingException.class, error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }

    @Nested
    @DisplayName("묶음 만들기")
    class Create {

        @Test
        @DisplayName("벌크 조회 한 번으로 판정하고, 제외 주문과 함께 한 번 저장한다")
        void createsWithExclusions() {
            given(remote.findAll(List.of(1L, 2L, 3L))).willReturn(List.of(snapshot(1), new PickingSnapshot(2, "CANCELED", "KR", snapshot(2).lines())));
            given(repository.insert(any())).willAnswer(invocation -> invocation.getArgument(0));

            PickingSelection selection = snapshots.load(List.of(1L, 2L, 3L));
            PickingBatch batch = batches.create(selection);

            assertThat(batch.totalQuantity()).isEqualTo(2);
            assertThat(batch.exclusions()).extracting(PickingExclusion::reason)
                .containsExactly(PickingExclusion.Reason.CANCELED_OR_EXPIRED, PickingExclusion.Reason.ORDER_NOT_FOUND);
            verify(remote).findAll(List.of(1L, 2L, 3L));
            verify(repository).insert(any());
        }

        @Test
        @DisplayName("대상이 0건이면 NO_ELIGIBLE_ORDER 이고 저장하지 않는다")
        void noEligibleOrder() {
            given(remote.findAll(List.of(1L, 2L))).willReturn(List.of(new PickingSnapshot(1, "PREPARING", "US", snapshot(1).lines())));

            assertCode(() -> snapshots.load(List.of(1L, 2L)), PickingErrorCode.NO_ELIGIBLE_ORDER);
            assertCode(() -> batches.create(new PickingSelection(List.of(), List.of())), PickingErrorCode.NO_ELIGIBLE_ORDER);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("항목이 없는 주문은 예외가 아니라 '상품이 없는 주문' 으로 제외된다")
        void emptyLinesAreExcluded() {
            given(remote.findAll(List.of(1L, 2L))).willReturn(List.of(snapshot(1), new PickingSnapshot(2, "PREPARING", "KR", List.of())));

            assertThat(snapshots.load(List.of(1L, 2L)).exclusions())
                .containsExactly(new PickingExclusion(2, PickingExclusion.Reason.NO_ORDER_ITEMS));
        }

        @Test
        @DisplayName("요청 모양(빈 목록 · 21건 · 중복 · 0 이하)은 원격 조회 전에 INVALID_BATCH 다")
        void rejectsBeforeRemoteCall() {
            assertCode(() -> snapshots.load(List.of()), PickingErrorCode.INVALID_BATCH);
            assertCode(() -> snapshots.load(List.of(1L, 1L)), PickingErrorCode.INVALID_BATCH);
            assertCode(() -> snapshots.load(List.of(0L)), PickingErrorCode.INVALID_BATCH);
            assertCode(() -> snapshots.load(java.util.stream.LongStream.rangeClosed(1, 21).boxed().toList()), PickingErrorCode.INVALID_BATCH);
            verifyNoInteractions(remote, repository);
        }

        @Test
        @DisplayName("요청하지 않은 · 중복된 스냅숏은 계약 위반이라 SNAPSHOT_UNAVAILABLE, 조회 실패는 그대로 전한다")
        void contractViolationAndRemoteFailure() {
            given(remote.findAll(List.of(1L))).willReturn(List.of(snapshot(2)), List.of(snapshot(1), snapshot(1)));
            assertCode(() -> snapshots.load(List.of(1L)), PickingErrorCode.SNAPSHOT_UNAVAILABLE);
            assertCode(() -> snapshots.load(List.of(1L)), PickingErrorCode.SNAPSHOT_UNAVAILABLE);

            given(remote.findAll(List.of(9L))).willThrow(new PickingException(PickingErrorCode.SNAPSHOT_UNAVAILABLE));
            assertCode(() -> snapshots.load(List.of(9L)), PickingErrorCode.SNAPSHOT_UNAVAILABLE);
            verifyNoInteractions(repository);
        }
    }

    @Nested
    @DisplayName("상세 · 검수")
    class Check {

        private final PickingBatch stored = new PickingBatch(1, List.of(snapshot(1)), List.of(), List.of(), 0L);

        @Test
        @DisplayName("상세와 같은 수량 재검수는 저장소만 읽고 저장하지 않는다 — 원격 조회도 없다")
        void detailAndIdempotentCheck() {
            PickingBatch checked = stored.check(1, Map.of(10L, 2L));
            given(repository.findById(1)).willReturn(Optional.of(checked));

            assertThat(batches.detail(1)).isSameAs(checked);
            assertThat(batches.check(1, 1, Map.of(10L, 2L))).isSameAs(checked);
            verify(repository, never()).addCheck(any());
            verifyNoInteractions(remote);
        }

        @Test
        @DisplayName("새 검수는 기록이 한 건 늘어난 묶음으로 저장한다")
        void addsCheck() {
            given(repository.findById(1)).willReturn(Optional.of(stored));
            given(repository.addCheck(any())).willAnswer(invocation -> invocation.getArgument(0));

            assertThat(batches.check(1, 1, Map.of(10L, 2L)).inspectionComplete()).isTrue();
            verify(repository).addCheck(any());
        }

        @Test
        @DisplayName("동시 검수로 버전이 부딪히면 CONCURRENTLY_CHANGED 로 바꾼다")
        void concurrentCheck() {
            given(repository.findById(1)).willReturn(Optional.of(stored));
            given(repository.addCheck(any())).willThrow(new ObjectOptimisticLockingFailureException(PickingBatch.class, 1L));

            assertCode(() -> batches.check(1, 1, Map.of(10L, 2L)), PickingErrorCode.CONCURRENTLY_CHANGED);
        }

        @Test
        @DisplayName("없는 묶음은 BATCH_NOT_FOUND, 깨진 수량은 INVALID_CHECK")
        void notFoundAndInvalid() {
            given(repository.findById(2)).willReturn(Optional.empty());
            given(repository.findById(1)).willReturn(Optional.of(stored));

            assertCode(() -> batches.detail(2), PickingErrorCode.BATCH_NOT_FOUND);
            assertCode(() -> batches.check(2, 1, Map.of(10L, 2L)), PickingErrorCode.BATCH_NOT_FOUND);
            assertCode(() -> batches.check(1, 1, null), PickingErrorCode.INVALID_CHECK);
            verify(repository, never()).addCheck(any());
        }
    }
}
