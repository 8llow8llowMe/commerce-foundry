package com.commercefoundry.domainlayer.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.product.adapter.in.admin.presenter.StockAlertAdminPresenter;
import com.commercefoundry.domainlayer.product.application.exception.StockAlertErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.StockAlertException;
import com.commercefoundry.domainlayer.product.application.info.StockAlertInfo;
import com.commercefoundry.domainlayer.product.application.service.processor.StockAlertAuditProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.StockAlertProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

class StockAlertAdminFacadeTest {

    private final StockAlertProcessor processor = mock(StockAlertProcessor.class);
    private final StockAlertAuditProcessor audit = mock(StockAlertAuditProcessor.class);
    private final StockAlertAdminFacade facade = new StockAlertAdminFacade(processor, audit, new StockAlertAdminPresenter());

    @Test
    void successfulSetUsesJwtActorAndAuditsAfterBusinessCall() {
        given(processor.setPolicy(1, 0)).willReturn(new StockAlertInfo.Policy(1, true, 0L, null));
        assertThat(facade.setPolicy(1, 0, 7).thresholdQuantity()).isZero();
        var order = inOrder(processor, audit);
        order.verify(processor).setPolicy(1, 0);
        order.verify(audit).policySet(7, 1, true);
    }

    @Test
    void busyFailureIs409AndAuditedAsFailure() {
        given(processor.setPolicy(1, 5)).willThrow(new QueryTimeoutException("internal"));
        assertThatThrownBy(() -> facade.setPolicy(1, 5, 7)).isInstanceOfSatisfying(StockAlertException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(StockAlertErrorCode.POLICY_BUSY));
        verify(audit).policySet(7, 1, false);
    }

    @Test
    void clearFailureStillAuditsAndDomainRejectsNegativeThreshold() {
        org.mockito.Mockito.doThrow(new StockAlertException(StockAlertErrorCode.NOT_FOUND_SKU)).when(processor).clearPolicy(1);
        assertThatThrownBy(() -> facade.clearPolicy(1, 7)).isInstanceOf(StockAlertException.class);
        verify(audit).policyCleared(7, 1, false);
        assertThatThrownBy(() -> com.commercefoundry.domainlayer.product.domain.model.StockAlertPolicy.configure(1, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
