package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockRepository;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.mapper.StockMapper;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.StockQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Stock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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

    @Override
    public List<StockQueryResult> findAllWithUpdatedAtBySkuIds(Collection<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return List.of();
        }
        return stockRepository.findAllById(skuIds).stream().map(stockMapper::toQueryResult).toList();
    }

    @Override
    public Optional<StockQueryResult> findBySkuId(long skuId) {
        return stockRepository.findById(skuId).map(stockMapper::toQueryResult);
    }

    /**
     * 바로 flush 해 PK 충돌을 이 자리에서 드러낸다 — 커밋 시점까지 미루면 트랜잭션 밖에서 터져 도메인 409 로 바꿀 수 없다.
     * 입력이 검증을 거쳐 수량이 0 이상이므로 여기서 나는 무결성 위반은 PK(sku_id) 충돌뿐이다.
     */
    @Override
    public void create(Stock stock) {
        try {
            stockRepository.saveAndFlush(stockMapper.toEntity(stock));
        } catch (DataIntegrityViolationException exception) {
            throw new ProductException(ProductErrorCode.STOCK_ADJUSTMENT_CONFLICT, exception);
        }
    }

    @Override
    public boolean deduct(long skuId, long quantity) {
        return stockRepository.deduct(skuId, quantity, Instant.now()) > 0;
    }

    @Override
    public boolean restore(long skuId, long quantity) {
        return stockRepository.restore(skuId, quantity, Instant.now()) > 0;
    }

    @Override
    public boolean updateQuantityIfMatches(long skuId, long expectedQuantity, long quantity) {
        return stockRepository.updateQuantityIfMatches(skuId, expectedQuantity, quantity, Instant.now()) > 0;
    }

    @Override
    public boolean updateStockManaged(long skuId, boolean stockManaged) {
        return stockRepository.updateStockManaged(skuId, stockManaged, Instant.now()) > 0;
    }
}
