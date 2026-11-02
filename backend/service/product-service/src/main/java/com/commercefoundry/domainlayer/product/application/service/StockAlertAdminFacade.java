package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.StockAlertAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAlertPolicyResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.presenter.StockAlertAdminPresenter;
import com.commercefoundry.domainlayer.product.application.exception.StockAlertErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.StockAlertException;
import com.commercefoundry.domainlayer.product.application.port.in.StockAlertAdminUseCase;
import com.commercefoundry.domainlayer.product.application.service.processor.StockAlertAuditProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.StockAlertProcessor;
import com.commercefoundry.persistence.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockAlertAdminFacade implements StockAlertAdminUseCase {

    private final StockAlertProcessor processor;
    private final StockAlertAuditProcessor auditProcessor;
    private final StockAlertAdminPresenter presenter;

    @Override
    @Transactional(readOnly = true)
    public StockAlertPolicyResponse getPolicy(long skuId) {
        return presenter.toPolicyResponse(processor.getPolicy(skuId));
    }

    /** 목록·count의 정책과 판매 가능 수량을 같은 스냅숏으로 읽는다. */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<StockAlertAdminItem> getLowStocks(int page, int size) {
        return presenter.toPageResponse(processor.getLowStocks(page, size));
    }

    @Override
    @Transactional(timeout = 5)
    public StockAlertPolicyResponse setPolicy(long skuId, long thresholdQuantity, long actorMemberId) {
        boolean succeeded = false;
        try {
            StockAlertPolicyResponse response = presenter.toPolicyResponse(processor.setPolicy(skuId, thresholdQuantity));
            succeeded = true;
            return response;
        } catch (QueryTimeoutException | TransactionTimedOutException | PessimisticLockingFailureException exception) {
            throw new StockAlertException(StockAlertErrorCode.POLICY_BUSY, exception);
        } finally {
            auditProcessor.policySet(actorMemberId, skuId, succeeded);
        }
    }

    @Override
    @Transactional(timeout = 5)
    public void clearPolicy(long skuId, long actorMemberId) {
        boolean succeeded = false;
        try {
            processor.clearPolicy(skuId);
            succeeded = true;
        } catch (QueryTimeoutException | TransactionTimedOutException | PessimisticLockingFailureException exception) {
            throw new StockAlertException(StockAlertErrorCode.POLICY_BUSY, exception);
        } finally {
            auditProcessor.policyCleared(actorMemberId, skuId, succeeded);
        }
    }
}
