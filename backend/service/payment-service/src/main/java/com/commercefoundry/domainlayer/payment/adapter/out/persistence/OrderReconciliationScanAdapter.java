package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.OrderReconciliationCursorRepository;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentRepository;
import com.commercefoundry.domainlayer.payment.application.port.out.OrderReconciliationScanPort;
import com.commercefoundry.domainlayer.payment.domain.model.OrderReconciliationLane;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OrderReconciliationScanAdapter implements OrderReconciliationScanPort {
    private static final List<PaymentStatus> STATUSES = List.of(PaymentStatus.DONE, PaymentStatus.PARTIAL_CANCELED);
    private final OrderReconciliationCursorRepository cursors;
    private final PaymentRepository payments;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public List<Long> reserveBatch(OrderReconciliationLane lane, Instant approvedBefore, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid reconciliation batch limit");
        var cursor = cursors.findLocked(lane).orElseThrow(() -> new IllegalStateException("Reconciliation cursor DDL seed is missing"));
        if (cursor.cycleUpperId() == 0) {
            cursor.beginCycle(payments.findReconciliationUpperId(), approvedBefore);
        }
        var page = PageRequest.of(0, limit);
        List<Long> ids = lane == OrderReconciliationLane.RECONCILE
            ? payments.findReconciliationIds(STATUSES, cursor.approvedBefore(), cursor.lastId(), cursor.cycleUpperId(), page)
            : payments.findRejectedReconciliationIds(STATUSES, cursor.lastId(), cursor.cycleUpperId(), page);
        if (ids.isEmpty()) cursor.finishCycle();
        else cursor.advance(ids.getLast());
        // 후보 처리 성공과 독립적으로 진행한다. 실패 후보도 다음 유한 cycle에서 재시도한다.
        cursors.flush();
        return ids;
    }
}
