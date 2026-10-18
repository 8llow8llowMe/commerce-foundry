package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAdminResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.presenter.StockAdminPresenter;
import com.commercefoundry.domainlayer.product.application.command.StockAdjustmentCommand;
import com.commercefoundry.domainlayer.product.application.port.in.StockAdminUseCase;
import com.commercefoundry.domainlayer.product.application.service.processor.StockAdjustmentProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockAdminFacade implements StockAdminUseCase {

    private final StockAdjustmentProcessor stockAdjustmentProcessor;
    private final StockAdminPresenter stockAdminPresenter;

    /** 외부 I/O 가 없어 Facade 에 트랜잭션을 건다 — 재고 변경과 조정 이력이 함께 커밋되거나 함께 롤백된다. */
    @Override
    @Transactional
    public StockAdminResponse adjustStock(StockAdjustmentCommand command) {
        return stockAdminPresenter.toResponse(stockAdjustmentProcessor.adjust(command));
    }
}
