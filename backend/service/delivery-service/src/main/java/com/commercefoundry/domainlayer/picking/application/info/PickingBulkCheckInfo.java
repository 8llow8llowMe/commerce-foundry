package com.commercefoundry.domainlayer.picking.application.info;

import com.commercefoundry.domainlayer.picking.domain.model.PickingCheck;
import java.util.List;

/** 요청 순서로 정렬한 주문별 저장·재사용 결과 또는 저장 충돌. */
public record PickingBulkCheckInfo(long batchId, List<OrderResult> results) {

    public sealed interface OrderResult permits Recorded, Conflict {

        long orderId();

        boolean successful();
    }

    public record Recorded(long orderId, PickingCheck.Failure reason) implements OrderResult {

        @Override
        public boolean successful() {
            return reason == PickingCheck.Failure.NONE;
        }
    }

    public record Conflict(long orderId) implements OrderResult {

        @Override
        public boolean successful() {
            return false;
        }
    }
}
