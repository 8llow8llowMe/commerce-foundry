package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockAdjustmentHistoryRepository;
import com.commercefoundry.domainlayer.product.application.mapper.StockAdjustmentHistoryMapper;
import com.commercefoundry.domainlayer.product.application.port.out.StockAdjustmentHistoryRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.StockAdjustmentHistoryQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockAdjustmentHistoryRepositoryAdapter implements StockAdjustmentHistoryRepositoryPort {

    private final StockAdjustmentHistoryRepository stockAdjustmentHistoryRepository;
    private final StockAdjustmentHistoryMapper stockAdjustmentHistoryMapper;

    @Override
    public void append(StockAdjustmentHistory history) {
        stockAdjustmentHistoryRepository.save(stockAdjustmentHistoryMapper.toEntity(history));
    }

    @Override
    public Page<StockAdjustmentHistoryQueryResult> findPageBySkuId(long skuId, int page, int size) {
        return stockAdjustmentHistoryRepository.findAllBySkuIdOrderByIdDesc(skuId, PageRequest.of(page, size))
            .map(stockAdjustmentHistoryMapper::toQueryResult);
    }
}
