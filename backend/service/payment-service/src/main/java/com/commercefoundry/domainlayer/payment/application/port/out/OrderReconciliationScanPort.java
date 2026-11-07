package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.domain.model.OrderReconciliationLane;
import java.time.Instant;
import java.util.List;

public interface OrderReconciliationScanPort {
    /** 독립 TX에서 배치를 예약하고 커서를 먼저 커밋한다. 중단된 배치는 다음 cycle에서 재방문한다. */
    List<Long> reserveBatch(OrderReconciliationLane lane, Instant approvedBefore, int limit);
}
