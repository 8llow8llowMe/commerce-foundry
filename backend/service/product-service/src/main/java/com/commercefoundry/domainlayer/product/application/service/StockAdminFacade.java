package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.StockAdjustmentHistoryAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAdminListResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAdminResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.presenter.StockAdminPresenter;
import com.commercefoundry.domainlayer.product.application.command.StockAdjustmentCommand;
import com.commercefoundry.domainlayer.product.application.port.in.StockAdminUseCase;
import com.commercefoundry.domainlayer.product.application.service.processor.StockAdjustmentProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.StockAdminQueryProcessor;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockAdminFacade implements StockAdminUseCase {

    private final StockAdjustmentProcessor stockAdjustmentProcessor;
    private final StockAdminQueryProcessor stockAdminQueryProcessor;
    private final StockAdminPresenter stockAdminPresenter;

    /**
     * 기본 격리(READ COMMITTED)라 재고 행과 선점 합은 문장마다 다른 스냅숏일 수 있다 — 화면의 참고치이고, 조정은 기대 수량 CAS 가 막으므로
     * 엇갈려도 409 로 다시 읽게 될 뿐 재고가 틀어지지 않는다.
     */
    @Override
    @Transactional(readOnly = true)
    public StockAdminListResponse getStocks(List<Long> skuIds) {
        return stockAdminPresenter.toListResponse(stockAdminQueryProcessor.getStocks(skuIds));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StockAdjustmentHistoryAdminItem> getAdjustments(long skuId, int page, int size) {
        return stockAdminPresenter.toAdjustmentPageResponse(stockAdminQueryProcessor.getAdjustments(skuId, page, size));
    }

    /** 외부 I/O 가 없어 Facade 에 트랜잭션을 건다 — 재고 변경과 조정 이력이 함께 커밋되거나 함께 롤백된다. */
    @Override
    @Transactional
    public StockAdminResponse adjustStock(StockAdjustmentCommand command) {
        return stockAdminPresenter.toResponse(stockAdjustmentProcessor.adjust(command));
    }
}
