package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockRepository;
import com.commercefoundry.domainlayer.product.application.mapper.StockMapper;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.Stock;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockRepositoryAdapter implements StockRepositoryPort {

    private final StockRepository stockRepository;
    private final StockMapper stockMapper;

    @Override
    public List<Stock> findAllBySkuIds(Collection<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return List.of();
        }
        return stockMapper.toDomains(stockRepository.findAllById(skuIds));
    }
}
