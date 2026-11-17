package com.commercefoundry.domainlayer.picking.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.picking.domain.model.PickingCheck.Failure;
import com.commercefoundry.domainlayer.picking.domain.model.PickingExclusion.Reason;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PickingBatchTest {

    /** 상품 옵션 10 을 2 + 3 줄로, 옵션 20 을 1 개 — 주문 안 합계는 {10=5, 20=1}. */
    private static PickingSnapshot order(long id) {
        return new PickingSnapshot(id, "PREPARING", "KR", List.of(
            new PickingSnapshot.Line(10, 2, false), new PickingSnapshot.Line(10, 3, false), new PickingSnapshot.Line(20, 1, false)));
    }

    private static PickingBatch batch(PickingSnapshot... orders) {
        List<PickingSnapshot> list = List.of(orders);
        return PickingBatch.create(1, PickingSelection.judge(list.stream().map(PickingSnapshot::orderId).toList(), list));
    }

    @Nested
    @DisplayName("대상 판정")
    class Judge {

        @ParameterizedTest(name = "{0} · {1} · 제작 {2} · 항목 {3}개 → {4}")
        @MethodSource("com.commercefoundry.domainlayer.picking.domain.model.PickingBatchTest#exclusions")
        @DisplayName("대상이 아닌 주문은 업무 사유로 제외된다 — 상태 → 국가 → 항목 → 제작 순으로 하나만")
        void excludesWithReason(String status, String country, boolean customOrder, int lineCount, Reason reason) {
            List<PickingSnapshot.Line> lines = new ArrayList<>();
            for (int i = 0; i < lineCount; i++) {
                lines.add(new PickingSnapshot.Line(10 + i, 1, customOrder && i == lineCount - 1));
            }
            assertThat(new PickingSnapshot(7, status, country, lines).exclusionReason()).contains(reason);
        }

        @Test
        @DisplayName("상품 준비 중 · KR · 일반 상품 1줄 이상이면 대상이다")
        void eligible() {
            assertThat(order(1).exclusionReason()).isEmpty();
            assertThat(order(1).eligible()).isTrue();
        }

        @Test
        @DisplayName("요청했지만 응답에 없는 주문은 '찾을 수 없는 주문' 이고, 대상과 제외가 요청 순서대로 나뉜다")
        void splitsTargetsAndExclusions() {
            PickingSnapshot shipped = new PickingSnapshot(2, "SHIPPING", "KR", order(2).lines());
            PickingSnapshot empty = new PickingSnapshot(4, "PREPARING", "KR", List.of());

            PickingSelection selection = PickingSelection.judge(List.of(1L, 2L, 3L, 4L, 5L), List.of(empty, order(5), shipped, order(1)));

            assertThat(selection.targets()).extracting(PickingSnapshot::orderId).containsExactly(1L, 5L);
            assertThat(selection.exclusions()).containsExactly(
                new PickingExclusion(2, Reason.ALREADY_SHIPPED), new PickingExclusion(3, Reason.ORDER_NOT_FOUND), new PickingExclusion(4, Reason.NO_ORDER_ITEMS));
            assertThat(selection.hasTarget()).isTrue();
        }

        @Test
        @DisplayName("모두 제외되면 대상이 없고, 대상 없는 판정으로는 묶음을 만들 수 없다")
        void noTarget() {
            PickingSelection selection = PickingSelection.judge(List.of(1L, 2L),
                List.of(new PickingSnapshot(1, "PENDING_PAYMENT", "KR", order(1).lines())));

            assertThat(selection.hasTarget()).isFalse();
            assertThat(selection.exclusions()).extracting(PickingExclusion::reason).containsExactly(Reason.PAYMENT_PENDING, Reason.ORDER_NOT_FOUND);
            assertThatThrownBy(() -> PickingBatch.create(1, selection)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("요청 모양 — 1~20건 · 양수 · 중복 없음")
        void validatesRequest() {
            PickingSelection.validateRequest(LongStream.rangeClosed(1, 20).boxed().toList());
            assertThatThrownBy(() -> PickingSelection.validateRequest(List.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PickingSelection.validateRequest(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PickingSelection.validateRequest(LongStream.rangeClosed(1, 21).boxed().toList()))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PickingSelection.validateRequest(List.of(1L, 1L))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PickingSelection.validateRequest(List.of(0L))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PickingSelection.validateRequest(java.util.Arrays.asList(1L, null))).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("요청하지 않았거나 중복된 스냅숏은 계약 위반이다")
        void rejectsUnexpectedSnapshots() {
            assertThatThrownBy(() -> PickingSelection.judge(List.of(1L), List.of(order(2)))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PickingSelection.judge(List.of(1L), List.of(order(1), order(1)))).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("대상 수량 합이 long 을 넘으면 ArithmeticException — 입력 목록을 바꿔도 스냅숏은 그대로다")
        void overflowAndImmutability() {
            List<PickingSnapshot.Line> lines = new ArrayList<>(List.of(new PickingSnapshot.Line(10, Long.MAX_VALUE, false)));
            PickingSnapshot huge = new PickingSnapshot(1, "PREPARING", "KR", lines);
            lines.clear();

            assertThat(huge.lines()).hasSize(1);
            assertThatThrownBy(() -> PickingSelection.judge(List.of(1L, 2L), List.of(huge, order(2)))).isInstanceOf(ArithmeticException.class);
            assertThatThrownBy(() -> new PickingSnapshot.Line(1, 0, false)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    static Stream<Arguments> exclusions() {
        return Stream.of(
            Arguments.of("PENDING_PAYMENT", "KR", false, 1, Reason.PAYMENT_PENDING),
            Arguments.of("EXPIRED", "KR", false, 1, Reason.CANCELED_OR_EXPIRED),
            Arguments.of("CANCEL_REQUESTED", "KR", false, 1, Reason.CANCELED_OR_EXPIRED),
            Arguments.of("CANCELED", "KR", false, 1, Reason.CANCELED_OR_EXPIRED),
            Arguments.of("SHIPPING", "KR", false, 1, Reason.ALREADY_SHIPPED),
            Arguments.of("DELIVERED", "KR", false, 1, Reason.ALREADY_SHIPPED),
            Arguments.of("PURCHASE_CONFIRMED", "KR", false, 1, Reason.ALREADY_SHIPPED),
            Arguments.of("RETURN_REQUESTED", "KR", false, 1, Reason.ALREADY_SHIPPED),
            Arguments.of("RETURNED", "KR", false, 1, Reason.ALREADY_SHIPPED),
            Arguments.of("PAID", "KR", false, 1, Reason.NOT_READY_TO_SHIP),
            Arguments.of("MAKING", "KR", true, 1, Reason.NOT_READY_TO_SHIP),
            Arguments.of("SOMETHING_NEW", "KR", false, 1, Reason.NOT_READY_TO_SHIP),
            Arguments.of("PREPARING", "US", false, 1, Reason.OVERSEAS_SHIPPING),
            Arguments.of("PREPARING", null, false, 1, Reason.OVERSEAS_SHIPPING),
            Arguments.of("PREPARING", "US", false, 0, Reason.OVERSEAS_SHIPPING),
            Arguments.of("PREPARING", "KR", false, 0, Reason.NO_ORDER_ITEMS),
            Arguments.of("PREPARING", "KR", true, 2, Reason.CUSTOM_ORDER_INCLUDED)
        );
    }

    @Nested
    @DisplayName("피킹 합계 · 묶음 불변식")
    class Totals {

        @Test
        @DisplayName("주문 안의 같은 옵션 줄과 여러 주문의 같은 옵션을 합친다 — 제외 주문은 합계에 들어가지 않는다")
        void aggregates() {
            PickingSnapshot other = new PickingSnapshot(300, "PREPARING", "KR", List.of(new PickingSnapshot.Line(30, 4, false)));
            PickingSelection selection = PickingSelection.judge(List.of(100L, 200L, 300L, 400L),
                List.of(order(100), order(200), other, new PickingSnapshot(400, "PREPARING", "JP", order(400).lines())));
            PickingBatch batch = PickingBatch.create(1, selection);

            assertThat(batch.orders().getFirst().quantities()).isEqualTo(Map.of(10L, 5L, 20L, 1L));
            assertThat(batch.pickingQuantities()).isEqualTo(Map.of(10L, 10L, 20L, 2L, 30L, 4L));
            assertThat(batch.totalQuantity()).isEqualTo(16);
            assertThat(batch.exclusions()).containsExactly(new PickingExclusion(400, Reason.OVERSEAS_SHIPPING));
            assertThat(batch.inspectionComplete()).isFalse();
            assertThat(batch.version()).isNull();
        }

        @Test
        @DisplayName("대상과 제외에 같은 주문이 있거나, 합쳐 20건을 넘거나, 대상에 부적격 주문이 있으면 묶음이 아니다")
        void invariants() {
            List<PickingExclusion> duplicate = List.of(new PickingExclusion(1, Reason.ORDER_NOT_FOUND));
            assertThatThrownBy(() -> new PickingBatch(1, List.of(order(1)), duplicate, List.of(), null)).isInstanceOf(IllegalArgumentException.class);
            List<PickingExclusion> twenty = LongStream.rangeClosed(2, 21).mapToObj(id -> new PickingExclusion(id, Reason.ORDER_NOT_FOUND)).toList();
            assertThatThrownBy(() -> new PickingBatch(1, List.of(order(1)), twenty, List.of(), null)).isInstanceOf(IllegalArgumentException.class);
            PickingSnapshot custom = new PickingSnapshot(1, "PREPARING", "KR", List.of(new PickingSnapshot.Line(1, 1, true)));
            assertThatThrownBy(() -> new PickingBatch(1, List.of(custom), List.of(), List.of(), null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PickingBatch(1, List.of(), List.of(), List.of(), null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("포장 검수")
    class Check {

        @ParameterizedTest(name = "{0} {1} → {2}")
        @MethodSource("com.commercefoundry.domainlayer.picking.domain.model.PickingBatchTest#failedChecks")
        @DisplayName("실패도 기록하고 검수 완료가 되지 않는다 — 같은 수량 재요청은 기록을 더하지 않는다")
        void recordsFailure(long orderId, Map<Long, Long> quantities, Failure failure) {
            PickingBatch checked = batch(order(100)).check(orderId, quantities);

            assertThat(checked.checks()).singleElement().extracting(PickingCheck::failure).isEqualTo(failure);
            assertThat(checked.inspectionComplete()).isFalse();
            assertThat(checked.check(orderId, quantities)).isSameAs(checked);
        }

        @Test
        @DisplayName("제외된 주문을 검수하면 '묶음에 없는 주문' 실패다")
        void excludedOrderIsNotInBatch() {
            PickingBatch withExclusion = PickingBatch.create(1, PickingSelection.judge(List.of(100L, 200L), List.of(order(100))));

            assertThat(withExclusion.check(200, Map.of(10L, 5L, 20L, 1L)).checks().getLast().failure()).isEqualTo(Failure.ORDER_NOT_IN_BATCH);
        }

        @Test
        @DisplayName("성공 뒤 같은 수량은 그대로, 다른 수량은 '이미 검수됨' 으로 남고 앞선 성공은 유지된다 — 실패 이력도 보존된다")
        void successAndRetries() {
            PickingBatch failed = batch(order(100), order(200)).check(100, Map.of(10L, 1L));
            PickingBatch first = failed.check(100, Map.of(10L, 5L, 20L, 1L));

            assertThat(first.checked(100)).isTrue();
            assertThat(first.inspectionComplete()).isFalse();
            assertThat(first.check(100, Map.of(20L, 1L, 10L, 5L))).as("순서만 다른 같은 수량").isSameAs(first);

            PickingBatch complete = first.check(200, Map.of(10L, 5L, 20L, 1L));
            assertThat(complete.inspectionComplete()).isTrue();
            assertThat(complete.checks()).extracting(PickingCheck::failure).containsExactly(Failure.SHORTAGE, Failure.NONE, Failure.NONE);

            PickingBatch rejected = complete.check(100, Map.of(10L, 6L));
            assertThat(rejected.checks().getLast().failure()).isEqualTo(Failure.ALREADY_CHECKED);
            assertThat(rejected.inspectionComplete()).isTrue();
            assertThat(complete.check(100, Map.of(10L, 1L))).as("앞선 실패와 같은 수량").isSameAs(complete);
        }

        @Test
        @DisplayName("검수는 주문 스냅숏(상태 · 국가)을 바꾸지 않는다 — 검수 완료는 출고가 아니다")
        void doesNotTouchOrderState() {
            PickingBatch complete = batch(order(100)).check(100, Map.of(10L, 5L, 20L, 1L));

            assertThat(complete.inspectionComplete()).isTrue();
            assertThat(complete.orders()).extracting(PickingSnapshot::status).containsOnly("PREPARING");
        }

        @Test
        @DisplayName("주문 아이디가 양수가 아니거나 수량 맵 · 옵션 · 수량이 null 이면 IllegalArgumentException")
        void rejectsMalformedInput() {
            PickingBatch batch = batch(order(100));
            Map<Long, Long> nullValue = new HashMap<>();
            nullValue.put(10L, null);

            assertThatThrownBy(() -> batch.check(0, Map.of(10L, 5L))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> batch.check(100, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> batch.check(100, nullValue)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    static Stream<Arguments> failedChecks() {
        return Stream.of(
            Arguments.of(100L, Map.of(10L, 4L, 20L, 1L), Failure.SHORTAGE),
            Arguments.of(100L, Map.of(10L, 6L, 20L, 1L), Failure.EXCESS),
            Arguments.of(100L, Map.of(99L, 5L), Failure.WRONG_SKU),
            Arguments.of(100L, Map.of(10L, 5L, 20L, 1L, 99L, 1L), Failure.WRONG_SKU),
            Arguments.of(100L, Map.of(), Failure.SHORTAGE),
            Arguments.of(100L, Map.of(10L, 0L), Failure.INVALID_QUANTITY),
            Arguments.of(100L, Map.of(10L, -1L), Failure.INVALID_QUANTITY),
            Arguments.of(999L, Map.of(10L, 5L), Failure.ORDER_NOT_IN_BATCH));
    }
}
