package com.commercefoundry.domainlayer.payment.application.service;

import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyException.Reason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 호출자는 고정 코드만 전달한다. 식별자·명령·예외·원격 본문·동적 태그를 기록하지 않는다. */
@Slf4j
@Component
public class OrderPaymentReplyObservation {
    public enum Closure { DUPLICATE, REJECTION_CLOSED, NO_CANDIDATE, HOOK_DEFERRED, HOOK_FAILED }
    public void record(Closure closure) {
        log.info("Order payment reply: {}", closure);
    }
    public void recordFailure(Reason reason) {
        log.warn("Order payment reply: {}", reason);
    }
}
