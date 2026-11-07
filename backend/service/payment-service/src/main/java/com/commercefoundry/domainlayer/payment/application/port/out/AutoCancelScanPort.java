package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.domain.model.AutoCancelLane;
import java.time.Instant;
import java.util.List;

public interface AutoCancelScanPort {
    List<Long> reserveBatch(AutoCancelLane lane, Instant dueBefore, int limit);
}
