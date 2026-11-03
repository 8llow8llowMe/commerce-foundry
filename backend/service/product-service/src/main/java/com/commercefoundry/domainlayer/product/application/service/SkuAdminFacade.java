package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.SkuAdminAuditProcessor;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductDetailAdminResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.presenter.ProductAdminPresenter;
import com.commercefoundry.domainlayer.product.application.command.SkuAddCommand;
import com.commercefoundry.domainlayer.product.application.command.SkuBulkUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminException;
import com.commercefoundry.domainlayer.product.application.port.in.SkuAdminUseCase;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductAdminDetailProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.SkuAddProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.SkuBulkUpdateProcessor;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SkuAdminFacade implements SkuAdminUseCase {
    private final SkuBulkUpdateProcessor processor;
    private final ProductAdminDetailProcessor details;
    private final ProductAdminPresenter presenter;
    private final SkuAdminAuditProcessor audit;
    private final SkuAddProcessor addProcessor;

    /** 외부 I/O가 없다. 실제 커밋·롤백 결과는 공통 감사 helper가 afterCompletion에서 기록한다. */
    @Override
    @Transactional(timeout = 5)
    public ProductDetailAdminResponse replace(SkuBulkUpdateCommand command) {
        boolean succeeded = false;
        try {
            processor.replace(command);
            var response = presenter.toDetailResponse(details.getProduct(command.productId()));
            succeeded = true;
            return response;
        } catch (RuntimeException exception) {
            if (isLockConflict(exception)) {
                throw new SkuAdminException(SkuAdminErrorCode.LOCK_CONFLICT, exception);
            }
            throw exception;
        } finally {
            audit.replaced(command.actorMemberId(), command.productId(), succeeded);
        }
    }

    @Override
    @Transactional(timeout = 5)
    public ProductDetailAdminResponse add(SkuAddCommand command) {
        boolean succeeded = false;
        try {
            addProcessor.add(command);
            var response = presenter.toDetailResponse(details.getProduct(command.productId()));
            succeeded = true;
            return response;
        } catch (RuntimeException exception) {
            if (isLockConflict(exception)) {
                throw new SkuAdminException(SkuAdminErrorCode.LOCK_CONFLICT, exception);
            }
            throw exception;
        } finally {
            audit.added(command.actorMemberId(), command.productId(), succeeded);
        }
    }

    private static boolean isLockConflict(Throwable exception) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = exception; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof PessimisticLockingFailureException || cause instanceof QueryTimeoutException
                || cause instanceof TransactionTimedOutException || cause instanceof jakarta.persistence.LockTimeoutException
                || cause instanceof jakarta.persistence.PessimisticLockException || cause instanceof jakarta.persistence.QueryTimeoutException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null && Set.of("55P03", "57014", "40001", "40P01", "HYT00").contains(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
