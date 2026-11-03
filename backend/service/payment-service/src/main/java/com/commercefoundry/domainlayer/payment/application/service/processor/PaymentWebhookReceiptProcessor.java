package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.command.PaymentWebhookCommand;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentWebhookErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentWebhookException;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentWebhookInboxPort;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentGateway;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentWebhookReceiptProcessor {
    private final PaymentRepositoryPort payments;
    private final PaymentWebhookInboxPort inbox;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;

    public String fingerprint(PaymentWebhookCommand command) {
        String canonical = part(command.eventType()) + part(command.pgOrderId()) + part(command.paymentKey()) + part(command.createdAt());
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static String part(String value) { return value.length() + ":" + value; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void receive(PaymentWebhookCommand command, String fingerprint) {
        var payment = payments.findByPgOrderId(command.pgOrderId()).orElseThrow(() -> new PaymentWebhookException(PaymentWebhookErrorCode.NOT_FOUND));
        if (payment.gateway() != PaymentGateway.TOSS || payment.paymentKey() == null || !payment.paymentKey().equals(command.paymentKey())) {
            throw new PaymentWebhookException(PaymentWebhookErrorCode.BINDING);
        }
        if (inbox.findByFingerprint(fingerprint).isEmpty()) {
            inbox.insert(PaymentWebhookInbox.received(ids.generateId(), payment.id(), fingerprint, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
        }
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean exists(String fingerprint) { return inbox.findByFingerprint(fingerprint).isPresent(); }
}
