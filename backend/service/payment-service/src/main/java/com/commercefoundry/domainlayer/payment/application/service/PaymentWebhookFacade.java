package com.commercefoundry.domainlayer.payment.application.service;

import com.commercefoundry.domainlayer.payment.application.command.PaymentWebhookCommand;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentWebhookConflictException;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentWebhookErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentWebhookException;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentWebhookUseCase;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentWebhookReceiptProcessor;
import com.commercefoundry.global.properties.PaymentWebhookProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentWebhookFacade implements PaymentWebhookUseCase {
    private final PaymentWebhookProperties properties;
    private final PaymentWebhookReceiptProcessor processor;

    /** 200은 REQUIRES_NEW 수신 커밋이 끝난 뒤에만 반환한다. HTTP 경로에는 PG I/O가 없다. */
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void receive(PaymentWebhookCommand command) {
        if (!properties.enabled()) { throw new PaymentWebhookException(PaymentWebhookErrorCode.DISABLED); }
        if (!"PAYMENT_STATUS_CHANGED".equals(command.eventType())) { throw new PaymentWebhookException(PaymentWebhookErrorCode.INVALID); }
        String fingerprint = processor.fingerprint(command);
        try {
            try { processor.receive(command, fingerprint); }
            catch (PaymentWebhookConflictException duplicate) {
                if (!processor.exists(fingerprint)) { throw new PaymentWebhookException(PaymentWebhookErrorCode.UNAVAILABLE); }
            }
        } catch (DataAccessException | TransactionException unavailable) {
            throw new PaymentWebhookException(PaymentWebhookErrorCode.UNAVAILABLE);
        }
    }
}
