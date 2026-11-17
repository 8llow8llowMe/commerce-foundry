package com.commercefoundry.domainlayer.picking.application.service.processor;

import com.commercefoundry.domainlayer.picking.application.exception.PickingErrorCode;
import com.commercefoundry.domainlayer.picking.application.exception.PickingException;
import com.commercefoundry.domainlayer.picking.application.port.out.OrderPickingSnapshotQueryPort;
import com.commercefoundry.domainlayer.picking.domain.model.PickingSelection;
import com.commercefoundry.domainlayer.picking.domain.model.PickingSnapshot;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 출고 묶음 요청 주문을 order 에서 한 번에 읽고 주문별로 판정한다. <b>트랜잭션을 걸지 않는다</b> — 원격 호출(최대 3초) 동안 DB 연결을 잡지 않게, 저장은
 * {@link PickingBatchProcessor} 의 트랜잭션에서만 한다.
 */
@Service
@RequiredArgsConstructor
public class OrderPickingSnapshotProcessor {

    private final OrderPickingSnapshotQueryPort orderPickingSnapshotQueryPort;

    /**
     * @param orderIds 요청 주문 1~20건 · 양수 · 중복 없음
     * @return 대상 1건 이상과 제외 주문 · 사유
     * @throws PickingException {@code INVALID_BATCH}(요청 모양 — 원격 조회 전) · {@code SNAPSHOT_UNAVAILABLE}(조회 실패 · 계약 위반) ·
     *                          {@code NO_ELIGIBLE_ORDER}(모두 제외)
     */
    public PickingSelection load(List<Long> orderIds) {
        try {
            PickingSelection.validateRequest(orderIds);
        } catch (IllegalArgumentException exception) {
            throw new PickingException(PickingErrorCode.INVALID_BATCH, exception);
        }
        List<Long> requested = List.copyOf(orderIds);
        List<PickingSnapshot> snapshots = orderPickingSnapshotQueryPort.findAll(requested);
        PickingSelection selection;
        try {
            selection = PickingSelection.judge(requested, snapshots == null ? List.of() : snapshots);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            // 요청하지 않은 · 중복 스냅숏, 수량 합 overflow — 어댑터가 걸러야 하는 계약 위반이다
            throw new PickingException(PickingErrorCode.SNAPSHOT_UNAVAILABLE, exception);
        }
        if (!selection.hasTarget()) {
            throw new PickingException(PickingErrorCode.NO_ELIGIBLE_ORDER);
        }
        return selection;
    }
}
