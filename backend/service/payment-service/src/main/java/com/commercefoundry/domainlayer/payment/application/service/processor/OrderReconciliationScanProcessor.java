package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.port.out.OrderReconciliationScanPort;
import com.commercefoundry.domainlayer.payment.domain.model.OrderReconciliationLane;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderReconciliationScanProcessor {
    private final OrderReconciliationScanPort scan;

    public List<Long> reserveBatch(OrderReconciliationLane lane, Instant approvedBefore, int limit) {
        return scan.reserveBatch(lane, approvedBefore, limit);
    }
}
