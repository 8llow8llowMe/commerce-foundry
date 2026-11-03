package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.StockAlertScanInternalResponse;
import com.commercefoundry.domainlayer.product.adapter.in.internal.presenter.StockAlertInternalPresenter;
import com.commercefoundry.domainlayer.product.application.port.in.StockAlertInternalUseCase;
import com.commercefoundry.domainlayer.product.application.service.processor.StockAlertProcessor;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockAlertInternalFacade implements StockAlertInternalUseCase {
    private final StockAlertProcessor processor;
    private final StockAlertInternalPresenter presenter;
    private final Clock clock;

    /** 시각은 이 페이지의 SELECT 직전에 기록한다. 페이지 간 스냅숏은 공유하지 않는다. */
    @Override
    @Transactional(readOnly = true)
    public StockAlertScanInternalResponse getLowStocks(long afterSkuId, int size) {
        var observedAt = clock.instant();
        return presenter.toResponse(observedAt, processor.getLowStocksAfter(afterSkuId, size));
    }
}
