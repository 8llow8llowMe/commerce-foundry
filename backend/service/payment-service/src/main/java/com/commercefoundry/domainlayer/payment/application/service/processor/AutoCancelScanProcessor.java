package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.port.out.AutoCancelScanPort;
import com.commercefoundry.domainlayer.payment.domain.model.AutoCancelLane;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AutoCancelScanProcessor {
    private final AutoCancelScanPort scan;
    public List<Long> reserveBatch(AutoCancelLane lane, Instant cutoff, int limit) {
        return scan.reserveBatch(lane, cutoff, limit);
    }
}
