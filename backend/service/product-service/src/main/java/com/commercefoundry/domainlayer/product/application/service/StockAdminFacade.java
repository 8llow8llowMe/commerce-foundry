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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockAdminFacade implements StockAdminUseCase {

    private final StockAdjustmentProcessor stockAdjustmentProcessor;
    private final StockAdminQueryProcessor stockAdminQueryProcessor;
    private final StockAdminPresenter stockAdminPresenter;

    /**
     * 재고 행과 선점 합을 같은 스냅숏에서 읽는다. 읽기 전용이라 직렬화 실패가 없다.
     *
     * <p>READ COMMITTED 면 재고 행과 선점 합을 문장마다 다른 스냅숏으로 읽는다. 그 사이 선점 · 해제가 커밋되면 한 번도 존재하지 않은 조합
     * (차감 전 판매 가능 수량 + 차감 후 선점 합)이 나와, 어드민이 "실물 = 판매 가능 + 선점" 으로 계산한 실사 수량이 틀어진다.
     * 조정 자체는 기대 수량 CAS 가 지키므로 이 격리는 화면에 보이는 두 수가 서로 맞게 하려는 것이다. 이력 조회는 한 테이블이라 기본 격리다.
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
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
