package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.AutoCancelCandidateRepository;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.AutoCancelCursorRepository;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentAutoCancelRepository;
import com.commercefoundry.domainlayer.payment.application.port.out.AutoCancelScanPort;
import com.commercefoundry.domainlayer.payment.domain.model.AutoCancelLane;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAutoCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reserve bounded IDs and commit progress before any enqueue or PG execution. */
@Component
@RequiredArgsConstructor
public class AutoCancelScanAdapter implements AutoCancelScanPort {
    private final AutoCancelCursorRepository cursors;
    private final AutoCancelCandidateRepository candidates;
    private final PaymentAutoCancelRepository intents;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public List<Long> reserveBatch(AutoCancelLane lane, Instant dueBefore, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid automatic cancellation batch limit");
        var cursor = cursors.findLocked(lane).orElseThrow(() -> new IllegalStateException("Automatic cancellation cursor seed is missing"));
        if (cursor.cycleUpperId() == 0) {
            cursor.beginCycle(lane == AutoCancelLane.PENDING ? intents.upperId() : candidates.upperId(), dueBefore);
        }
        var page = PageRequest.of(0, limit);
        List<Long> ids = switch (lane) {
            case REJECTED -> candidates.rejected(List.of(PaymentStatus.DONE, PaymentStatus.PARTIAL_CANCELED), cursor.lastId(), cursor.cycleUpperId(), page);
            case LATE_APPROVED -> candidates.lateApproved(PaymentStatus.LATE_APPROVED, cursor.lastId(), cursor.cycleUpperId(), page);
            case PENDING -> intents.dueIds(PaymentAutoCancel.State.PENDING, cursor.dueBefore(), cursor.lastId(), cursor.cycleUpperId(), page);
        };
        if (ids.isEmpty()) cursor.finishCycle();
        else cursor.advance(ids.getLast());
        cursors.flush();
        return ids;
    }
}
