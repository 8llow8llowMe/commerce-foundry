package com.commercefoundry.domainlayer.payment.adapter.out.policy;

import static org.mockito.Mockito.*;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAutoCancelProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DeferredPaymentCancelPolicyTest {
    @Test
    void defaultOffAndEnabledOnlyPersistsIntent() {
        var processor = mock(PaymentAutoCancelProcessor.class);
        var policy = new DeferredPaymentCancelPolicy(processor);
        policy.onRejected(123);
        verifyNoInteractions(processor);
        ReflectionTestUtils.setField(policy, "enabled", true);
        policy.onRejected(123);
        verify(processor).enqueue(123);
        verifyNoMoreInteractions(processor);
    }
}
