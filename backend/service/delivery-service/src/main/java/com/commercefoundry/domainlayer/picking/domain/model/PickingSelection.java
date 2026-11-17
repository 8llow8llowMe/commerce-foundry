package com.commercefoundry.domainlayer.picking.domain.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 출고 묶음 요청(주문 1~20건)을 주문별로 판정한 결과 — 담을 주문({@link #targets})과 사유와 함께 뺀 주문({@link #exclusions}).
 * 대상이 하나도 없으면 묶음을 만들지 않는다({@link #hasTarget()} 으로 호출자가 판단한다). 둘 다 요청 순서를 지킨다.
 */
public record PickingSelection(List<PickingSnapshot> targets, List<PickingExclusion> exclusions) {

    /** 한 묶음에 담을 수 있는 요청 주문 수 상한. */
    public static final int MAX_ORDERS = 20;

    public PickingSelection {
        targets = List.copyOf(targets);
        exclusions = List.copyOf(exclusions);
        if (targets.size() + exclusions.size() > MAX_ORDERS) {
            throw new IllegalArgumentException("한 묶음의 주문은 20건 이하입니다.");
        }
        if (targets.stream().anyMatch(target -> !target.eligible())) {
            throw new IllegalArgumentException("대상이 아닌 주문은 제외 목록에 둡니다.");
        }
        Set<Long> seen = new HashSet<>();
        targets.forEach(target -> requireNew(seen, target.orderId()));
        exclusions.forEach(exclusion -> requireNew(seen, exclusion.orderId()));
        PickingBatch.sum(targets);
    }

    /**
     * 요청한 주문 아이디마다 판정한다. 응답에 없는 주문은 {@link PickingExclusion.Reason#ORDER_NOT_FOUND} 다.
     *
     * @param requestedOrderIds 1~20건 · 양수 · 중복 없음
     * @param snapshots         order 가 돌려준 스냅숏 — 요청한 아이디만, 한 번씩이어야 한다(아니면 계약 위반 {@link IllegalArgumentException})
     * @throws ArithmeticException 대상 주문의 수량 합이 long 을 넘는다
     */
    public static PickingSelection judge(List<Long> requestedOrderIds, List<PickingSnapshot> snapshots) {
        validateRequest(requestedOrderIds);
        Map<Long, PickingSnapshot> byOrderId = new LinkedHashMap<>();
        for (PickingSnapshot snapshot : snapshots) {
            if (snapshot == null || !requestedOrderIds.contains(snapshot.orderId()) || byOrderId.putIfAbsent(snapshot.orderId(), snapshot) != null) {
                throw new IllegalArgumentException("요청하지 않았거나 중복된 주문 스냅숏입니다.");
            }
        }
        List<PickingSnapshot> targets = new ArrayList<>();
        List<PickingExclusion> exclusions = new ArrayList<>();
        for (long orderId : requestedOrderIds) {
            PickingSnapshot snapshot = byOrderId.get(orderId);
            if (snapshot == null) {
                exclusions.add(new PickingExclusion(orderId, PickingExclusion.Reason.ORDER_NOT_FOUND));
                continue;
            }
            snapshot.exclusionReason().ifPresentOrElse(
                reason -> exclusions.add(new PickingExclusion(orderId, reason)),
                () -> targets.add(snapshot));
        }
        return new PickingSelection(targets, exclusions);
    }

    /** 요청 주문 목록의 모양 — 1~20건 · 양수 · 중복 없음. 원격 조회 전에 부른다. */
    public static void validateRequest(List<Long> requestedOrderIds) {
        if (requestedOrderIds == null || requestedOrderIds.isEmpty() || requestedOrderIds.size() > MAX_ORDERS) {
            throw new IllegalArgumentException("주문은 1건 이상 20건 이하입니다.");
        }
        Set<Long> seen = new HashSet<>();
        for (Long orderId : requestedOrderIds) {
            if (orderId == null || orderId <= 0) {
                throw new IllegalArgumentException("주문 아이디는 양수여야 합니다.");
            }
            requireNew(seen, orderId);
        }
    }

    public boolean hasTarget() {
        return !targets.isEmpty();
    }

    private static void requireNew(Set<Long> seen, long orderId) {
        if (!seen.add(orderId)) {
            throw new IllegalArgumentException("같은 주문을 두 번 담을 수 없습니다.");
        }
    }
}
