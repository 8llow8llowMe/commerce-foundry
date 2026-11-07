package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import static com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyException.Reason.*;

import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyException.Reason;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException;
import com.commercefoundry.domainlayer.payment.application.port.out.OrderPaymentReplyFailurePort;
import com.commercefoundry.persistence.util.ConstraintViolations;
import jakarta.persistence.OptimisticLockException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.hibernate.StaleStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionSystemException;

/** commit 래퍼를 벗겨 구조로만 분류한다. SQL·메시지·식별자·원인 사슬을 결과에 싣지 않는다. */
@Component
public class OrderPaymentReplyFailureAdapter implements OrderPaymentReplyFailurePort {
    @Override
    public Reason classify(RuntimeException failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        boolean integrity = false;
        boolean unique = false;
        var roots = new ArrayList<Throwable>();
        roots.add(failure);
        for (int index = 0; index < roots.size(); index++) {
            Throwable root = roots.get(index);
            var chain = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
            for (Throwable cause = root; cause != null; cause = cause.getCause()) {
                // 손상된 순환/과도한 사슬을 ConstraintViolations에 넘겨 무한 순회하지 않는다.
                if (!chain.add(cause) || seen.size() > 64) {
                    return STORAGE_UNAVAILABLE;
                }
                if (!seen.add(cause)) { continue; }
                if (cause instanceof TransactionSystemException tx && tx.getOriginalException() != null && !seen.contains(tx.getOriginalException())) {
                    roots.add(tx.getOriginalException());
                }
                if (cause instanceof PaymentPersistenceConflictException conflict) {
                    return conflict.getKind() == PaymentPersistenceConflictException.Kind.VERSION ? PAYMENT_VERSION : STORAGE_CONTRACT;
                }
                if (cause instanceof OptimisticLockingFailureException || cause instanceof OptimisticLockException || cause instanceof StaleStateException) {
                    return PAYMENT_VERSION;
                }
                integrity |= cause instanceof DataIntegrityViolationException || cause instanceof ConstraintViolationException;
                if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                    integrity |= sql.getSQLState().startsWith("23") || sql.getSQLState().startsWith("22");
                    unique |= "23505".equals(sql.getSQLState());
                }
            }
        }
        if (integrity) {
            return unique && roots.stream().anyMatch(root -> ConstraintViolations.violates(root, "uk_processed_event_consumer_group_event_id"))
                ? INBOX_DUPLICATE : STORAGE_CONTRACT;
        }
        return STORAGE_UNAVAILABLE;
    }
}
