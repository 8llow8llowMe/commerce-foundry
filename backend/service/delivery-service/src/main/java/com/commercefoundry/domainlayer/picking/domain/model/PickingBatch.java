package com.commercefoundry.domainlayer.picking.domain.model;

import static com.commercefoundry.domainlayer.picking.domain.model.PickingCheck.Failure.ALREADY_CHECKED;
import static com.commercefoundry.domainlayer.picking.domain.model.PickingCheck.Failure.EXCESS;
import static com.commercefoundry.domainlayer.picking.domain.model.PickingCheck.Failure.INVALID_QUANTITY;
import static com.commercefoundry.domainlayer.picking.domain.model.PickingCheck.Failure.NONE;
import static com.commercefoundry.domainlayer.picking.domain.model.PickingCheck.Failure.ORDER_NOT_IN_BATCH;
import static com.commercefoundry.domainlayer.picking.domain.model.PickingCheck.Failure.SHORTAGE;
import static com.commercefoundry.domainlayer.picking.domain.model.PickingCheck.Failure.UNKNOWN_BARCODE;
import static com.commercefoundry.domainlayer.picking.domain.model.PickingCheck.Failure.WRONG_SKU;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 출고 묶음(피킹 리스트) — 상품 준비 중인 국내 일반 주문을 모아 상품 옵션별로 몇 개를 꺼낼지 합치고, 주문별 포장 검수 이력을 쌓는다.
 *
 * <p><b>검수 완료는 출고 완료가 아니다.</b> 이 모델은 주문 상태 · 송장 · 재고를 바꾸지 않는다 — 출고는 송장 등록({@code order-flow-guide.md} §4)이 따로 한다.
 * 그래서 묶음을 만든 뒤 주문이 취소돼도 이 묶음은 그대로다(송장 등록이 그때 주문 상태로 거절한다).
 *
 * <p>불변이다. {@link #check} 는 새 기록을 더한 새 묶음을 돌려주고, 같은 주문 · 같은 수량의 재요청이면 자신을 그대로 돌려준다(이력을 다시 쓰지 않는다).
 * 동시 검수는 {@link #version} 으로 막는다 — 같은 버전에서 갈라진 두 검수 중 하나만 저장된다.
 *
 * @param orders     담은 주문(대상) 1~20건
 * @param exclusions 요청에 있었지만 사유와 함께 뺀 주문
 * @param checks     검수 기록(시간순)
 * @param version    저장된 버전. 아직 저장 전이면 null
 */
public record PickingBatch(long id, List<PickingSnapshot> orders, List<PickingExclusion> exclusions, List<PickingCheck> checks, Long version) {

    public PickingBatch {
        if (id <= 0) {
            throw new IllegalArgumentException("출고 묶음 아이디는 양수여야 합니다.");
        }
        Objects.requireNonNull(orders, "담은 주문이 필요합니다.");
        if (orders.isEmpty()) {
            throw new IllegalArgumentException("출고 묶음에는 대상 주문이 1건 이상 있어야 합니다.");
        }
        // 대상 적격 · 아이디 중복 · 20건 상한 · 수량 합 overflow 는 판정 결과와 같은 규칙이다
        PickingSelection selection = new PickingSelection(orders, Objects.requireNonNull(exclusions, "제외 목록이 필요합니다."));
        orders = selection.targets();
        exclusions = selection.exclusions();
        checks = List.copyOf(Objects.requireNonNull(checks, "검수 기록이 필요합니다."));
    }

    /**
     * 판정 결과로 새 묶음을 만든다.
     *
     * @throws IllegalArgumentException 대상이 없다
     */
    public static PickingBatch create(long id, PickingSelection selection) {
        return new PickingBatch(id, selection.targets(), selection.exclusions(), List.of(), null);
    }

    /** 상품 옵션 아이디 → 묶음 전체에서 꺼낼 수량(여러 주문의 같은 옵션을 합친다). */
    public Map<Long, Long> pickingQuantities() {
        return sum(orders);
    }

    public long totalQuantity() {
        return pickingQuantities().values().stream().reduce(0L, Math::addExact);
    }

    /** 이 주문이 검수에 성공한 적이 있는가. */
    public boolean checked(long orderId) {
        return checks.stream().anyMatch(check -> check.orderId() == orderId && check.successful());
    }

    /** 모든 대상 주문이 검수에 성공했는가. 출고 여부와 무관하다. */
    public boolean inspectionComplete() {
        return orders.stream().allMatch(order -> checked(order.orderId()));
    }

    /**
     * 주문 하나를 검수한다 — 운영자가 센 상품 옵션별 수량을 주문과 맞춰 본다. 실패도 기록한다(부족 · 초과 · 잘못된 옵션 등).
     * 같은 주문에 같은 수량을 다시 보내면 이전 결과(성공이든 실패든)를 그대로 쓰고 기록을 더하지 않는다 — 반환값이 {@code this} 다.
     *
     * @param quantities 상품 옵션 아이디 → 센 수량. 빈 맵은 하나도 꺼내지 않았다는 뜻이다(부족)
     * @throws IllegalArgumentException 주문 아이디가 양수가 아니거나 맵 · 옵션 · 수량이 null 이다
     */
    public PickingBatch check(long orderId, Map<Long, Long> quantities) {
        return checkSubmission(orderId, new PickingCheckSubmission(quantities, Map.of()));
    }

    public PickingBatch checkSubmission(long orderId, PickingCheckSubmission submission) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("주문 아이디는 양수여야 합니다.");
        }
        String key = submission.key();
        if (checks.stream().anyMatch(check -> check.orderId() == orderId && check.quantitiesKey().equals(key))) {
            return this;
        }
        PickingSnapshot order = orders.stream().filter(item -> item.orderId() == orderId).findFirst().orElse(null);
        PickingCheck.Failure failure = order == null ? ORDER_NOT_IN_BATCH : failure(order, submission);
        List<PickingCheck> next = new ArrayList<>(checks);
        next.add(new PickingCheck(orderId, key, failure));
        return new PickingBatch(id, orders, exclusions, next, version);
    }

    private PickingCheck.Failure failure(PickingSnapshot order, PickingCheckSubmission submission) {
        if (!submission.unknownBarcodes().isEmpty()) {
            return UNKNOWN_BARCODE;
        }
        Map<Long, Long> actual = submission.quantities();
        if (checked(order.orderId())) {
            return ALREADY_CHECKED;
        }
        if (actual.values().stream().anyMatch(quantity -> quantity <= 0)) {
            return INVALID_QUANTITY;
        }
        Map<Long, Long> expected = order.quantities();
        if (!expected.keySet().containsAll(actual.keySet())) {
            return WRONG_SKU;
        }
        if (actual.entrySet().stream().anyMatch(entry -> entry.getValue() > expected.get(entry.getKey()))) {
            return EXCESS;
        }
        if (expected.entrySet().stream().anyMatch(entry -> actual.getOrDefault(entry.getKey(), 0L) < entry.getValue())) {
            return SHORTAGE;
        }
        return NONE;
    }

    /** 주문들의 상품 옵션별 수량 합. 합이 long 을 넘으면 {@link ArithmeticException}. */
    static Map<Long, Long> sum(List<PickingSnapshot> orders) {
        Map<Long, Long> result = new TreeMap<>();
        orders.forEach(order -> order.quantities().forEach((sku, quantity) -> result.merge(sku, quantity, Math::addExact)));
        result.values().stream().reduce(0L, Math::addExact);
        return Map.copyOf(result);
    }
}
